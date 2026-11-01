package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Assignment;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.UpdateStatement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.UpdatePointsPlan;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans an UPDATE as Qdrant payload changes, sent in one batch: {@code SET lang = 'de'} sets a
 * field, {@code SET "specs.color" = 'red'} sets one inside an object, {@code SET lang = NULL}
 * deletes it, and {@code SET _payload = '{...}'} replaces the whole payload. A field the
 * collection doesn't have yet is added, since a payload has no schema to change first.
 */
final class UpdatePlanner {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String UNKNOWN_COLUMN = "42S22";

    Plan plan(UpdateStatement statement, Catalog catalog) throws SQLException {
        TableSchema schema = catalog.table(WritePlanner.collection(statement.table(), "UPDATE"));

        if (statement.where() == null) {
            throw new SQLFeatureNotSupportedException("UPDATE without WHERE would change every "
                + "point of " + schema.name() + ": add a WHERE, such as WHERE _id = 7");
        }

        JsonNode filter = FilterPlanner.plan(statement.where(), schema.name(),
            column -> SelectPlanner.find(schema, column, statement.alias(), false));

        JsonNode replaced = null;
        boolean cleared = false;
        ObjectNode fields = JSON.objectNode();
        Map<String, ObjectNode> nested = new LinkedHashMap<>();
        List<String> deleted = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Assignment assignment : statement.assignments()) {
            ColumnDefinition column = column(schema, assignment.column(), statement.alias());
            String path = column == null ? newField(assignment.column(), statement.alias(), schema)
                : column.source();
            String name = column == null ? path : column.name();

            if (!seen.add(name)) {
                throw new SQLSyntaxErrorException("Column '" + name + "' is set twice", "42000");
            }

            if (column != null && column.kind() == ColumnKind.PAYLOAD) {
                replaced = PayloadValues.of(assignment.value(), column, name);
                cleared = replaced == null;

                if (replaced != null && !replaced.isObject()) {
                    throw new SQLException("_payload takes a JSON object, as '{\"lang\": \"en\"}'",
                        "22018");
                }
                continue;
            }

            JsonNode value = PayloadValues.of(assignment.value(), column, name);
            int dot = path.lastIndexOf('.');

            if (value == null) {
                deleted.add(path);
            } else if (dot < 0) {
                fields.set(path, value);
            } else {
                nested.computeIfAbsent(path.substring(0, dot), x -> JSON.objectNode())
                    .set(path.substring(dot + 1), value);
            }
        }

        // Replacing comes first, so the fields set beside it land in the new payload
        var operations = new ArrayList<JsonNode>();
        if (cleared) operations.add(operation("clear_payload", JSON.objectNode(), filter));
        if (replaced != null) operations.add(operation("overwrite_payload",
            JSON.objectNode().set("payload", replaced), filter));
        if (!fields.isEmpty()) operations.add(operation("set_payload",
            JSON.objectNode().set("payload", fields), filter));

        for (Map.Entry<String, ObjectNode> object : nested.entrySet()) {
            ObjectNode change = JSON.objectNode();
            change.set("payload", object.getValue());
            change.put("key", object.getKey());
            operations.add(operation("set_payload", change, filter));
        }

        if (!deleted.isEmpty()) {
            ArrayNode keys = JSON.arrayNode();
            deleted.forEach(keys::add);
            operations.add(operation("delete_payload", JSON.objectNode().set("keys", keys), filter));
        }

        return new UpdatePointsPlan(schema.name(), filter, operations);
    }

    /**
     * The column an assignment names, or {@code null} when it names a payload field the
     * collection doesn't have yet. The driver's own columns and the vectors aren't payload, so
     * they're errors.
     */
    private static ColumnDefinition column(TableSchema schema, Column column, String alias)
        throws SQLException {
        ColumnDefinition definition;

        try {
            definition = SelectPlanner.find(schema, column, alias, false);
        } catch (SQLException e) {
            if (!UNKNOWN_COLUMN.equals(e.getSQLState())) throw e;
            return null;
        }

        String reason = switch (definition.kind()) {
            case ID -> "a point's ID can't change";
            case VECTOR -> "changing a vector isn't supported yet";
            case SCORE -> "a search's score isn't stored";
            case PAYLOAD, PAYLOAD_FIELD -> null;
        };

        if (reason != null) {
            throw new SQLFeatureNotSupportedException("Can't SET '" + definition.name() + "' of "
                + schema.name() + ": " + reason);
        }

        return definition;
    }

    // The payload path of a field to add: the name under the table, or the whole dotted name
    private static String newField(Column column, String alias, TableSchema schema)
        throws SQLException {
        String table = alias != null ? alias : schema.name();
        String path = column.qualifier() == null || column.qualifier().equals(table)
            ? column.name()
            : column.qualified();

        if (path.startsWith("_")) {
            throw new SQLSyntaxErrorException("Column '" + path + "' starts with _, which the "
                + "driver keeps for its own columns", "42000");
        }

        return path;
    }

    private static JsonNode operation(String name, ObjectNode change, JsonNode filter) {
        change.set("filter", filter);
        return JSON.objectNode().set(name, change);
    }
}
