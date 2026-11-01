package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CreateIndexStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DropIndexStatement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateIndexPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropIndexPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Plans CREATE INDEX and DROP INDEX on Qdrant payload indexes. Qdrant names an index by its
 * field, and the driver reports it as {@code <field>_idx}, so either name finds it. The type
 * defaults to the column's own; options in WITH are checked against what Qdrant keeps for that
 * type, since it accepts and silently drops any other.
 */
final class IndexPlanner {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String UNKNOWN_COLUMN = "42S22";

    // What the driver reports an index as, in DatabaseMetaData.getIndexInfo
    static final String SUFFIX = "_idx";

    private enum Kind { BOOL, WHOLE, TEXT }

    private static final Map<String, Map<String, Kind>> OPTIONS = Map.of(
        "keyword", Map.of("is_tenant", Kind.BOOL, "on_disk", Kind.BOOL),
        "integer", Map.of("lookup", Kind.BOOL, "range", Kind.BOOL, "is_principal", Kind.BOOL,
            "on_disk", Kind.BOOL),
        "float", Map.of("is_principal", Kind.BOOL, "on_disk", Kind.BOOL),
        "datetime", Map.of("is_principal", Kind.BOOL, "on_disk", Kind.BOOL),
        "uuid", Map.of("is_tenant", Kind.BOOL, "on_disk", Kind.BOOL),
        "bool", Map.of("on_disk", Kind.BOOL),
        "geo", Map.of("on_disk", Kind.BOOL),
        "text", Map.of("tokenizer", Kind.TEXT, "min_token_len", Kind.WHOLE,
            "max_token_len", Kind.WHOLE, "lowercase", Kind.BOOL, "ascii_folding", Kind.BOOL,
            "phrase_matching", Kind.BOOL, "stopwords", Kind.TEXT, "stemmer", Kind.TEXT,
            "on_disk", Kind.BOOL));

    Plan plan(CreateIndexStatement statement, Catalog catalog) throws SQLException {
        TableSchema schema = catalog.table(WritePlanner.collection(statement.table(),
            "CREATE INDEX ON"));
        ColumnDefinition column = column(schema, statement.column());
        String field = column == null ? newField(statement.column(), schema) : column.source();

        if (statement.name() != null && !statement.name().equals(field)
            && !statement.name().equals(field + SUFFIX)) {
            throw new SQLSyntaxErrorException("Qdrant names an index by its field, so the index "
                + "on '" + field + "' is " + field + SUFFIX + ": use that name, or leave it out",
                "42000");
        }

        String type = type(statement, column, field, schema);
        Map<String, Kind> allowed = OPTIONS.get(type);

        if (statement.options().isEmpty()) {
            return new CreateIndexPlan(schema.name(), field, JSON.textNode(type),
                statement.ifNotExists());
        }

        ObjectNode index = JSON.objectNode().put("type", type);
        for (Map.Entry<String, Expression> option : statement.options().entrySet()) {
            Kind kind = allowed.get(option.getKey());

            if (kind == null) {
                throw new SQLSyntaxErrorException("A " + type + " index has no option '"
                    + option.getKey() + "': it takes " + String.join(", ",
                    new TreeSet<>(allowed.keySet())), "42000");
            }

            index.set(option.getKey(), value(option.getKey(), kind, option.getValue()));
        }

        return new CreateIndexPlan(schema.name(), field, index, statement.ifNotExists());
    }

    Plan plan(DropIndexStatement statement, Catalog catalog) throws SQLException {
        TableSchema schema = catalog.table(WritePlanner.collection(statement.table(),
            "DROP INDEX ON"));

        if (statement.column() != null) {
            ColumnDefinition column = column(schema, statement.column());
            String field = column == null ? newField(statement.column(), schema) : column.source();

            return new DropIndexPlan(schema.name(), List.of(field), field, statement.ifExists());
        }

        // lang_idx is the index on lang, as the driver reports it; lang names it too
        String name = statement.name();
        List<String> fields = name.endsWith(SUFFIX) && name.length() > SUFFIX.length()
            ? List.of(name.substring(0, name.length() - SUFFIX.length()), name)
            : List.of(name);

        return new DropIndexPlan(schema.name(), fields, name, statement.ifExists());
    }

    /**
     * The column the index is on, or {@code null} for a payload field the collection doesn't
     * have yet: an index may come before the points that fill it. Only payload is indexed.
     */
    private static ColumnDefinition column(TableSchema schema, Column column) throws SQLException {
        ColumnDefinition definition;

        try {
            definition = SelectPlanner.find(schema, column, null, false);
        } catch (SQLException e) {
            if (!UNKNOWN_COLUMN.equals(e.getSQLState())) throw e;
            return null;
        }

        String reason = switch (definition.kind()) {
            case ID -> "points are found by ID without one";
            case PAYLOAD -> "an index is on one field of the payload: name it";
            case VECTOR -> "a vector's index is part of the collection's settings";
            case SCORE -> "a search's score isn't stored";
            case PAYLOAD_FIELD -> null;
        };

        if (reason != null) {
            throw new SQLFeatureNotSupportedException("Can't index '" + definition.name() + "' of "
                + schema.name() + ": " + reason);
        }

        return definition;
    }

    private static String newField(Column column, TableSchema schema) throws SQLException {
        String path = column.qualifier() == null || column.qualifier().equals(schema.name())
            ? column.name()
            : column.qualified();

        if (path.startsWith("_")) {
            throw new SQLSyntaxErrorException("Column '" + path + "' starts with _, which the "
                + "driver keeps for its own columns", "42000");
        }

        return path;
    }

    // The type USING names, or the column's own; a JSON column or a new field says nothing
    private static String type(CreateIndexStatement statement, ColumnDefinition column,
                               String field, TableSchema schema) throws SQLException {
        if (statement.using() != null) {
            String type = CreatePlanner.indexType(statement.using());

            if (type == null) {
                throw new SQLSyntaxErrorException("Unknown index type '" + statement.using()
                    + "': Qdrant has keyword, integer, float, bool, datetime, text, uuid and geo",
                    "42000");
            }

            return type;
        }

        String type = column == null ? null : switch (column.type()) {
            case TEXT -> "keyword";
            case INTEGER -> "integer";
            case FLOAT -> "float";
            case BOOLEAN -> "bool";
            case DATETIME -> "datetime";
            default -> null;
        };

        if (type == null) {
            throw new SQLSyntaxErrorException("Say which index '" + field + "' of " + schema.name()
                + " takes, as USING keyword: its type says nothing about it", "42000");
        }

        return type;
    }

    private static JsonNode value(String name, Kind kind, Expression given) throws SQLException {
        Object value = given instanceof Literal literal ? literal.value() : null;

        return switch (kind) {
            case BOOL -> {
                if (value instanceof Boolean bool) yield JSON.booleanNode(bool);
                if ("true".equalsIgnoreCase(String.valueOf(value))
                    || "false".equalsIgnoreCase(String.valueOf(value))) {
                    yield JSON.booleanNode(Boolean.parseBoolean(value.toString()));
                }
                throw invalid(name, "true or false", value);
            }
            case WHOLE -> {
                if (value instanceof Long number && number > 0) yield JSON.numberNode(number);
                throw invalid(name, "a whole number of 1 or more", value);
            }
            case TEXT -> {
                if (!(value instanceof String text)) throw invalid(name, "a word", value);

                // A stemmer is Qdrant's Snowball one, for the language the statement names
                yield name.equals("stemmer")
                    ? JSON.objectNode().put("type", "snowball").put("language", text)
                    : JSON.textNode(text);
            }
        };
    }

    private static SQLException invalid(String name, String expected, Object value) {
        return new SQLSyntaxErrorException("Option '" + name + "' takes " + expected + ", not "
            + (value instanceof String text ? "'" + text + "'" : value), "22023");
    }
}
