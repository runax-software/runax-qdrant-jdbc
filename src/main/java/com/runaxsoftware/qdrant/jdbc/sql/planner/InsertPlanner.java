package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Parameter;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.InsertStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.OnConflict;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ConflictAction;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.EmbedText;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.InsertPointsPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.NewPoint;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;

import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plans an INSERT as new Qdrant points, one per row. A row without an ID gets a random UUID,
 * which {@code getGeneratedKeys} returns. A payload field the collection doesn't have yet is
 * added; {@code _payload} gives the whole payload, and fields named beside it go into it. A
 * vector is written out ({@code [0.1, 0.2]}), given as JSON text as a tool sends a cell, or
 * embedded from text with {@code embed('text')}, by Qdrant or by the driver.
 *
 * <p>An {@code ON CONFLICT} clause decides what happens to a row whose ID is taken: without one
 * the statement fails, {@code DO NOTHING} leaves the point alone, and {@code DO UPDATE} replaces
 * it — with a {@code WHERE} that says which of the points already there may be replaced.
 */
final class InsertPlanner {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String UNKNOWN_COLUMN = "42S22";

    /**
     * Where one column's values go.
     *
     * @param column the collection's column, or {@code null} for a field it doesn't have yet
     * @param path   the payload path, for payload fields
     * @param name   the column's name, for errors
     */
    private record Target(ColumnDefinition column, String path, String name) {

        ColumnKind kind() {
            return column == null ? ColumnKind.PAYLOAD_FIELD : column.kind();
        }
    }

    Plan plan(InsertStatement statement, Catalog catalog) throws SQLException {
        TableSchema schema = catalog.table(WritePlanner.collection(statement.table(), "INSERT INTO"));
        List<Target> targets = targets(statement.columns(), schema);
        var points = new ArrayList<NewPoint>();
        Set<JsonNode> ids = new HashSet<>();

        for (List<Expression> row : statement.rows()) {
            if (row.size() != targets.size()) {
                throw new SQLSyntaxErrorException("A row of VALUES has " + row.size()
                    + (row.size() == 1 ? " value" : " values") + ", and the statement names "
                    + targets.size() + (targets.size() == 1 ? " column" : " columns"), "21S01");
            }

            NewPoint point = point(row, targets, schema);
            if (point.idGiven() && !ids.add(point.id())) {
                throw new SQLException("Point " + point.id() + " is given twice", "23505");
            }

            points.add(point);
        }

        return new InsertPointsPlan(schema.name(), points, action(statement.onConflict()),
            updateFilter(statement, schema));
    }

    private static ConflictAction action(OnConflict onConflict) {
        return switch (onConflict.action()) {
            case FAIL -> ConflictAction.FAIL;
            case NOTHING -> ConflictAction.NOTHING;
            case UPDATE -> ConflictAction.UPDATE;
        };
    }

    /**
     * The filter that says which points already there may be replaced, from a
     * {@code DO UPDATE ... WHERE}. It reads the same conditions a SELECT's WHERE does, over the
     * stored point rather than the row being inserted: Qdrant applies it to the points it finds
     * and inserts new ones whatever it says.
     */
    private static JsonNode updateFilter(InsertStatement statement, TableSchema schema)
        throws SQLException {
        OnConflict onConflict = statement.onConflict();

        if (onConflict.where() == null) {
            return null;
        }
        if (onConflict.action() != OnConflict.Action.UPDATE) {
            throw new SQLSyntaxErrorException("Only DO UPDATE takes a WHERE", "42000");
        }

        return FilterPlanner.plan(onConflict.where(), schema,
            column -> SelectPlanner.find(schema, column, null, false));
    }

    private static List<Target> targets(List<Column> columns, TableSchema schema)
        throws SQLException {
        var targets = new ArrayList<Target>();
        Set<String> names = new HashSet<>();

        for (Column column : columns) {
            Target target = target(column, schema);

            if (!names.add(target.name())) {
                throw new SQLSyntaxErrorException("Column '" + target.name() + "' is named twice",
                    "42000");
            }
            if (target.kind() == ColumnKind.SCORE) {
                throw new SQLSyntaxErrorException("Column '_score' is only in a search, and isn't "
                    + "stored", "42000");
            }

            targets.add(target);
        }

        return targets;
    }

    // The column a name gives a value for, or a payload field to add
    private static Target target(Column column, TableSchema schema) throws SQLException {
        try {
            ColumnDefinition definition = SelectPlanner.find(schema, column, null, false);
            return new Target(definition, definition.source(), definition.name());
        } catch (SQLException e) {
            if (!UNKNOWN_COLUMN.equals(e.getSQLState())) throw e;
        }

        String path = column.qualifier() == null || column.qualifier().equals(schema.name())
            ? column.name()
            : column.qualified();

        if (path.startsWith("_")) {
            throw new SQLSyntaxErrorException("Column '" + path + "' starts with _, which the "
                + "driver keeps for its own columns", "42000");
        }

        return new Target(null, path, path);
    }

    private static NewPoint point(List<Expression> row, List<Target> targets, TableSchema schema)
        throws SQLException {
        JsonNode id = null;
        ObjectNode payload = JSON.objectNode();
        Map<String, JsonNode> fields = new HashMap<>();
        Map<String, JsonNode> vectors = new HashMap<>();
        Map<String, EmbedText> embeds = new HashMap<>();
        var order = new ArrayList<String>();

        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            Expression value = row.get(i);

            switch (target.kind()) {
                case ID -> id = id(value);
                case PAYLOAD -> {
                    JsonNode whole = PayloadValues.of(value, target.column(), target.name());
                    if (whole != null && !whole.isObject()) {
                        throw new SQLException("_payload takes a JSON object, as "
                            + "'{\"lang\": \"en\"}'", "22018");
                    }
                    if (whole != null) payload = (ObjectNode) whole;
                }
                case VECTOR -> VectorValues.of(value, target.column(), schema, vectors, embeds);
                default -> {
                    JsonNode field = PayloadValues.of(value, target.column(), target.name());
                    if (field != null) {
                        fields.put(target.path(), field);
                        order.add(target.path());
                    }
                }
            }
        }

        // The fields go into the whole payload, if one was given, whatever the column order
        for (String path : order) put(payload, path, fields.get(path));

        boolean given = id != null;
        return new NewPoint(given ? id : JSON.textNode(UUID.randomUUID().toString()), given,
            payload, vectors, embeds);
    }

    // A point ID: a whole number of 0 or more, or a UUID, either of them possibly as text
    private static JsonNode id(Expression value) throws SQLException {
        Object constant = constant(value, "_id");

        if (constant == null) return null;
        if (constant instanceof Long number && number >= 0) return JSON.numberNode(number);

        if (constant instanceof String text) {
            String id = text.strip();

            if (id.matches("\\d{1,20}") && new BigInteger(id).bitLength() <= 64) {
                return number(new BigInteger(id));
            }
            try {
                return JSON.textNode(UUID.fromString(id).toString());
            } catch (IllegalArgumentException e) {
                // Neither: the error below
            }
        }

        throw new SQLException("A point ID is a whole number of 0 or more, or a UUID, not "
            + (constant instanceof String ? "'" + constant + "'" : constant), "22018");
    }

    // A whole number as one kind of node, so 1 and '1' are the same ID
    private static JsonNode number(BigInteger number) {
        return number.bitLength() < 64 ? JSON.numberNode(number.longValue()) : JSON.numberNode(number);
    }

    // A constant's value, or null for NULL; a placeholder reaches here only unbound
    private static Object constant(Expression value, String name) throws SQLException {
        if (value instanceof Literal literal) {
            return literal.value();
        }
        if (value instanceof Parameter) {
            throw new SQLFeatureNotSupportedException("Parameters are given through a "
                + "PreparedStatement");
        }

        throw new SQLFeatureNotSupportedException("'" + name + "' takes a constant, a vector, or "
            + "embed('text'), not a column or another call");
    }

    // Puts a value at a dotted path, making the objects on the way
    private static void put(ObjectNode payload, String path, JsonNode value) throws SQLException {
        String[] keys = path.split("\\.");
        ObjectNode node = payload;

        for (int i = 0; i < keys.length - 1; i++) {
            JsonNode next = node.get(keys[i]);

            if (next == null) {
                next = node.putObject(keys[i]);
            } else if (!next.isObject()) {
                throw new SQLException("Field '" + path + "' goes inside '" + keys[i] + "', which "
                    + "the row gives a value that isn't an object", "22018");
            }

            node = (ObjectNode) next;
        }

        node.set(keys[keys.length - 1], value);
    }
}
