package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.And;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Comparison;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.FunctionCall;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.InList;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.IsNull;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Like;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Not;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Or;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Parameter;

import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A WHERE clause as a Qdrant filter, in the JSON Qdrant's REST API takes. Every condition
 * compares one column with constants, which is all a Qdrant filter can do: anything else is an
 * error rather than rows the driver would check itself.
 *
 * <p>SQL's rules for nulls hold. A comparison is never true for a point whose field is missing
 * or null, and neither is its negation, so {@code price <> 5} and {@code NOT (price > 5)} match
 * only points that have a price. NOT is pushed down to the conditions, through AND and OR, so
 * each negated condition can add that the field must be there.
 */
final class FilterPlanner {

    /**
     * Finds the column a condition names, as the select list's columns are found.
     */
    @FunctionalInterface
    interface Columns {
        ColumnDefinition find(Column column) throws SQLException;
    }

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private static final String MUST = "must";
    private static final String SHOULD = "should";
    private static final String MUST_NOT = "must_not";
    private static final Set<String> CLAUSES = Set.of(MUST, SHOULD, MUST_NOT);

    private static final Set<String> RANGES = Set.of("<", "<=", ">", ">=");

    /**
     * The full-text conditions, by the function that writes each: every word, any word, and the
     * words in order. Qdrant runs them through a text index, and without one by reading the
     * payload, so they need no index the way ORDER BY and GROUP BY do — except a phrase, which
     * needs an index built to match phrases.
     */
    private static final String TEXT_MATCH = "text_match";
    private static final String TEXT_ANY = "text_any";
    private static final String PHRASE_MATCH = "phrase_match";
    private static final Map<String, String> TEXT_CONDITIONS = Map.of(
        TEXT_MATCH, "text", TEXT_ANY, "text_any", PHRASE_MATCH, "phrase");

    private final TableSchema schema;
    private final String table;
    private final Columns columns;

    private FilterPlanner(TableSchema schema, Columns columns) {
        this.schema = schema;
        this.table = schema.name();
        this.columns = columns;
    }

    /**
     * The filter for a WHERE clause, or {@code null} when there's none.
     *
     * @param where   the statement's WHERE, or {@code null}
     * @param schema  the collection the statement reads, for error messages and for the text
     *                indexes a phrase match needs
     * @param columns finds the columns the conditions name
     */
    static JsonNode plan(Expression where, TableSchema schema, Columns columns)
        throws SQLException {
        if (where == null) {
            return null;
        }

        ObjectNode condition = new FilterPlanner(schema, columns).condition(where, false);

        return isFilter(condition) ? condition : clause(MUST, List.of(condition));
    }

    /**
     * One condition of a filter, which may itself be a filter.
     *
     * @param negated whether a NOT above it applies
     */
    private ObjectNode condition(Expression expression, boolean negated) throws SQLException {
        if (expression instanceof And and) {
            return combine(negated ? SHOULD : MUST,
                condition(and.left(), negated), condition(and.right(), negated));
        }
        if (expression instanceof Or or) {
            return combine(negated ? MUST : SHOULD,
                condition(or.left(), negated), condition(or.right(), negated));
        }
        if (expression instanceof Not not) {
            return condition(not.operand(), !negated);
        }
        if (expression instanceof Comparison comparison) {
            return comparison(comparison, negated);
        }
        if (expression instanceof InList in) {
            return in(in, negated != in.negated());
        }
        if (expression instanceof IsNull isNull) {
            return isNull(isNull, negated == isNull.negated());
        }
        if (expression instanceof Like like) {
            return like(like, negated != like.negated());
        }
        if (expression instanceof FunctionCall call) {
            return textCondition(call, negated);
        }

        throw new SQLSyntaxErrorException("Expected a condition in WHERE, such as price > 10",
            "42000");
    }

    /**
     * column LIKE 'pattern'. Qdrant matches the start of a value and a substring of it, so a
     * pattern is one of those or an error: the driver doesn't read points to check a pattern
     * itself. Both are case-sensitive, as Qdrant's exact match is.
     *
     * @param negated whether the condition, with any NOT above it, asks for no match
     */
    private ObjectNode like(Like like, boolean negated) throws SQLException {
        ColumnDefinition column = column(like.column());
        Object value = value(like.pattern(), column);

        if (!(value instanceof String pattern)) {
            throw new SQLSyntaxErrorException("LIKE takes a text pattern, as name LIKE 'Desk%', "
                + "not " + written(value), "42000");
        }

        payloadField(column, "matched");
        ObjectNode condition = matching(column, pattern);

        return negated ? notMatching(condition, column) : condition;
    }

    /**
     * The Qdrant match a LIKE pattern asks for: {@code 'Desk%'} the values starting with it,
     * {@code '%desk%'} the values holding it, and a pattern with no wildcard the values equal
     * to it. Every other pattern is an error naming what Qdrant can do instead, rather than a
     * broader search the driver would trim itself.
     */
    private ObjectNode matching(ColumnDefinition column, String pattern) throws SQLException {
        if (pattern.indexOf('_') >= 0) {
            throw new SQLFeatureNotSupportedException("LIKE '" + pattern + "' isn't supported: "
                + "Qdrant has no single-character wildcard, so _ can't stand for one. A literal "
                + "_ can't be escaped either, since the driver reads no ESCAPE clause");
        }

        boolean starts = pattern.startsWith("%");
        boolean ends = pattern.endsWith("%");

        // One '%' is both, and either way asks for every value the field has
        if (starts && pattern.length() == 1) {
            throw new SQLFeatureNotSupportedException("LIKE '%' matches every value of '"
                + column.name() + "': use IS NOT NULL to ask for the points that have it");
        }

        String text = pattern.substring(starts ? 1 : 0, ends ? pattern.length() - 1 : pattern.length());

        if (text.indexOf('%') >= 0) {
            throw new SQLFeatureNotSupportedException("LIKE '" + pattern + "' isn't supported: "
                + "Qdrant matches the start of a value ('Desk%') or a substring of it "
                + "('%desk%'), and a % in the middle of a pattern asks for neither");
        }
        if (text.isEmpty()) {   // '%%'
            throw new SQLFeatureNotSupportedException("LIKE '" + pattern + "' matches every value "
                + "of '" + column.name() + "': use IS NOT NULL to ask for the points that have it");
        }

        if (starts && !ends) {
            throw new SQLFeatureNotSupportedException("LIKE '" + pattern + "' isn't supported: "
                + "Qdrant has no match for the end of a value, so write '%" + text + "%' for the "
                + "values that hold '" + text + "' anywhere");
        }

        likeable(column);

        if (!starts && !ends) {   // no wildcard at all, which is what = asks for
            return equals(column, text);
        }

        return field(column).set("match", JSON.objectNode().put(starts ? "substring" : "prefix",
            text));
    }

    /**
     * Fails unless LIKE can match the column's values. Qdrant matches a prefix and a substring
     * of the text it stores, and answers with no points for a number or a boolean rather than
     * failing, so a pattern against one is an error here instead of an empty result.
     */
    private void likeable(ColumnDefinition column) throws SQLException {
        String reason = switch (column.type()) {
            case INTEGER, FLOAT -> "Qdrant matches a prefix and a substring of text, and "
                + "compares numbers with =, <, <=, > and >=";
            case BOOLEAN -> "a boolean is matched with = TRUE or = FALSE";
            default -> null;   // text, JSON (an array of keywords), and a datetime's own text
        };

        if (reason != null) {
            throw new SQLFeatureNotSupportedException("LIKE isn't supported on '" + column.name()
                + "' of " + table + ", which is " + column.type().name().toLowerCase(Locale.ROOT)
                + ": " + reason);
        }
    }

    /**
     * text_match(field, 'words'), text_any, and phrase_match: Qdrant's full-text conditions,
     * which a text index serves and which run without one by reading the payload.
     */
    private ObjectNode textCondition(FunctionCall call, boolean negated) throws SQLException {
        String match = TEXT_CONDITIONS.entrySet().stream()
            .filter(entry -> call.is(entry.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);

        if (match == null) {
            throw new SQLFeatureNotSupportedException("'" + call.name() + "' isn't a condition "
                + "this driver has: a condition compares a column with constants, or is one of "
                + TEXT_MATCH + "(field, 'words'), " + TEXT_ANY + "(field, 'words') and "
                + PHRASE_MATCH + "(field, 'words')");
        }
        if (call.arguments().size() != 2) {
            throw new SQLSyntaxErrorException(call.name() + " takes a column and the text to "
                + "match, as " + call.name() + "(title, 'refund policy')", "42000");
        }

        ColumnDefinition column = column(call.arguments().get(0));
        payloadField(column, "matched");
        Object text = value(call.arguments().get(1), column);

        if (!(text instanceof String words)) {
            throw new SQLSyntaxErrorException(call.name() + " takes the text to match in quotes, "
                + "as " + call.name() + "(title, 'refund policy'), not " + written(text), "42000");
        }
        if (words.isBlank()) {
            throw new SQLSyntaxErrorException(call.name() + " takes at least one word to match",
                "42000");
        }
        if (call.is(PHRASE_MATCH)) {
            phraseIndex(column);
        }

        ObjectNode condition = field(column).set("match",
            JSON.objectNode().put(match, words));

        return negated ? notMatching(condition, column) : condition;
    }

    /**
     * Fails unless the field's text index matches phrases. Qdrant answers a phrase match
     * against an index built without {@code phrase_matching} with no points at all, rather than
     * an error, so a statement that would quietly return nothing fails here instead.
     */
    private void phraseIndex(ColumnDefinition column) throws SQLException {
        if (schema.collection().phraseFields().contains(column.source())) {
            return;
        }

        PayloadIndexType index = schema.collection().payloadIndexes().get(column.source());
        String has = index == null ? "isn't indexed"
            : index == PayloadIndexType.TEXT
                ? "has a text index that wasn't built to match phrases"
                : "is indexed as " + index.qdrantName();

        throw new SQLFeatureNotSupportedException("Matching a phrase in '" + column.name()
            + "' of " + table + " needs a text index built with phrase_matching, and this field "
            + has + ": Qdrant answers a phrase match without one with no points rather than an "
            + "error. CREATE INDEX ON " + table + " (" + column.name()
            + ") USING text WITH (phrase_matching = true) builds one, or " + TEXT_MATCH
            + "() matches the same words in any order");
    }

    // A column compared with a constant
    private ObjectNode comparison(Comparison comparison, boolean negated) throws SQLException {
        String operator = comparison.operator();
        ColumnDefinition column = column(comparison.left());
        Object value = value(comparison.right(), column);
        boolean equality = operator.equals("=");

        if (!equality && !RANGES.contains(operator)) {   // <> and !=
            equality = true;
            negated = !negated;
        }

        if (column.kind() == ColumnKind.ID) {
            if (!equality) {
                throw new SQLFeatureNotSupportedException("Point IDs can only be compared with "
                    + "=, <> or IN: Qdrant doesn't order them");
            }

            return ids(List.of(value), negated);
        }

        payloadField(column, "compared");
        ObjectNode condition = equality ? equals(column, value) : range(column, operator, value);

        return negated ? notMatching(condition, column) : condition;
    }

    // column IN (values), or NOT IN when negated
    private ObjectNode in(InList in, boolean negated) throws SQLException {
        ColumnDefinition column = column(in.column());
        var values = new ArrayList<Object>();

        for (Expression value : in.values()) {
            values.add(value(value, column));
        }

        if (column.kind() == ColumnKind.ID) {
            return ids(values, negated);
        }

        payloadField(column, "compared");
        ObjectNode condition;

        if (matchesAny(values, column)) {
            ArrayNode any = JSON.arrayNode();
            for (Object value : values) any.add(node(value));

            condition = field(column).set("match", JSON.objectNode().set("any", any));
        } else {
            var each = new ArrayList<ObjectNode>();
            for (Object value : values) each.add(equals(column, value));

            condition = combine(SHOULD, each.toArray(ObjectNode[]::new));
        }

        return negated ? notMatching(condition, column) : condition;
    }

    /**
     * column IS NULL, or IS NOT NULL. A payload field is null when it's missing, null, or an
     * empty array, as Qdrant's is_empty says; a named vector is null when the point has none.
     *
     * @param wantsNull whether the condition, with any NOT above it, asks for null
     */
    private ObjectNode isNull(IsNull isNull, boolean wantsNull) throws SQLException {
        ColumnDefinition column = column(isNull.column());

        // An unnamed vector is asked about by its Qdrant name, which is "". A point normally has
        // it, but UPDATE ... SET _vector = NULL can take it away, so the question is a fair one
        if (column.kind() == ColumnKind.VECTOR) {
            ObjectNode hasVector = JSON.objectNode().put("has_vector", column.source());

            return wantsNull ? clause(MUST_NOT, List.of(hasVector)) : hasVector;
        }
        if (column.kind() == ColumnKind.ID) {
            throw new SQLFeatureNotSupportedException("Every point has an ID, so '"
                + column.name() + "' is never null");
        }

        payloadField(column, "tested for null");
        ObjectNode empty = isEmpty(column);

        return wantsNull ? empty : clause(MUST_NOT, List.of(empty));
    }

    // The points with, or without, any of these IDs
    private ObjectNode ids(List<Object> values, boolean negated) throws SQLException {
        ArrayNode ids = JSON.arrayNode();

        for (Object value : values) {
            if (value instanceof Long id && id >= 0) {
                ids.add(id);
            } else if (value instanceof String text && isUuid(text)) {
                ids.add(text);
            } else if (value instanceof String text && isUnsigned(text)) {
                // Tools send the ID column's text, and Qdrant takes no number written as text
                ids.add(JSON.numberNode(new BigInteger(text)));
            } else {
                throw new SQLSyntaxErrorException("A point ID is a whole number of 0 or more, "
                    + "or a UUID in quotes, not " + written(value), "42000");
            }
        }

        ObjectNode hasId = JSON.objectNode().set("has_id", ids);

        return negated ? clause(MUST_NOT, List.of(hasId)) : hasId;
    }

    /**
     * The field equals the value. Qdrant matches keywords, whole numbers and booleans exactly;
     * a decimal number, a number in a decimal field, and a date-time are a range from the
     * value to itself, which is how Qdrant compares those.
     */
    private ObjectNode equals(ColumnDefinition column, Object value) throws SQLException {
        boolean exact = value instanceof Boolean
            || value instanceof String && column.type() != ColumnType.DATETIME
            || value instanceof Long && column.type() != ColumnType.FLOAT;

        if (exact) {
            return field(column).set("match", JSON.objectNode().set("value", node(value)));
        }

        ObjectNode range = JSON.objectNode();
        range.set("gte", rangeValue(column, "=", value));
        range.set("lte", rangeValue(column, "=", value));

        return field(column).set("range", range);
    }

    // The field is less or greater than the value
    private ObjectNode range(ColumnDefinition column, String operator, Object value)
        throws SQLException {
        String bound = switch (operator) {
            case "<" -> "lt";
            case "<=" -> "lte";
            case ">" -> "gt";
            default -> "gte";
        };

        return field(column).set("range", JSON.objectNode().set(bound,
            rangeValue(column, operator, value)));
    }

    /**
     * A bound of a range: a number, or a date-time for a date-time field, which Qdrant compares
     * by time rather than as text.
     */
    private JsonNode rangeValue(ColumnDefinition column, String operator, Object value)
        throws SQLException {
        if (value instanceof Long || value instanceof Double) {
            return node(value);
        }
        if (value instanceof String && column.type() == ColumnType.DATETIME) {
            return node(value);
        }

        throw new SQLFeatureNotSupportedException("'" + column.name() + "' " + operator + " "
            + written(value) + " isn't supported: Qdrant compares numbers and date-times with "
            + "<, <=, > and >=, and other values only with = and IN");
    }

    // Whether Qdrant can match the values as one list: keywords, or whole numbers
    private static boolean matchesAny(List<Object> values, ColumnDefinition column) {
        boolean keywords = column.type() != ColumnType.DATETIME
            && values.stream().allMatch(x -> x instanceof String);
        boolean integers = column.type() != ColumnType.FLOAT
            && values.stream().allMatch(x -> x instanceof Long);

        return keywords || integers;
    }

    /**
     * The negation of a condition on a field, which SQL makes true only for points that have
     * the field: not matching, and not empty.
     */
    private static ObjectNode notMatching(ObjectNode condition, ColumnDefinition column) {
        return clause(MUST_NOT, List.of(condition, isEmpty(column)));
    }

    // The column a condition names; a condition compares one column with constants
    private ColumnDefinition column(Expression expression) throws SQLException {
        if (expression instanceof Column column) {
            return columns.find(column);
        }
        if (expression instanceof FunctionCall call) {
            throw new SQLFeatureNotSupportedException("'" + call.name() + "' isn't supported in "
                + "WHERE yet: a condition compares a column with constants");
        }

        throw new SQLFeatureNotSupportedException("A condition compares a column with "
            + "constants, as price > 10");
    }

    // The constant a column is compared with
    private Object value(Expression expression, ColumnDefinition column) throws SQLException {
        if (expression instanceof Literal literal && literal.value() == null) {
            throw new SQLSyntaxErrorException("Comparing '" + column.name() + "' with NULL is "
                + "never true: use IS NULL or IS NOT NULL", "42000");
        }
        if (expression instanceof Literal literal) {
            return literal.value();
        }
        if (expression instanceof Parameter) {
            throw new SQLFeatureNotSupportedException("Parameters are given through a "
                + "PreparedStatement: Connection.prepareStatement(sql), then setString and the "
                + "other setters");
        }
        throw new SQLFeatureNotSupportedException("'" + column.name() + "' can only be compared "
            + "with a text, number or boolean constant");
    }

    // Fails unless the column is one payload field, which is what Qdrant's conditions test
    private void payloadField(ColumnDefinition column, String what) throws SQLException {
        String reason = switch (column.kind()) {
            case PAYLOAD_FIELD -> null;
            case PAYLOAD -> "the whole payload can't be " + what + ": name one of its fields";
            case VECTOR -> "a vector can only be tested with IS NULL or IS NOT NULL";
            case SCORE -> "a search's score can't be filtered yet";
            case ID -> "point IDs are matched with =, <> and IN";
        };

        if (reason != null) {
            throw new SQLFeatureNotSupportedException("Column '" + column.name() + "' of "
                + table + " isn't supported in this condition: " + reason);
        }
    }

    private static ObjectNode field(ColumnDefinition column) {
        return JSON.objectNode().put("key", column.source());
    }

    private static ObjectNode isEmpty(ColumnDefinition column) {
        return JSON.objectNode().set("is_empty", field(column));
    }

    /**
     * A filter of one clause over the conditions; a condition that is a filter of the same
     * clause alone gives its own conditions instead, so {@code a AND b AND c} is one list.
     */
    private static ObjectNode combine(String clause, ObjectNode... conditions) {
        var flat = new ArrayList<ObjectNode>();

        for (ObjectNode condition : conditions) {
            if (condition.size() == 1 && condition.has(clause)) {
                condition.get(clause).forEach(x -> flat.add((ObjectNode) x));
            } else {
                flat.add(condition);
            }
        }

        return clause(clause, flat);
    }

    private static ObjectNode clause(String clause, List<ObjectNode> conditions) {
        ArrayNode list = JSON.arrayNode();
        conditions.forEach(list::add);

        return JSON.objectNode().set(clause, list);
    }

    // Whether a condition is a filter, made only of must, should and must_not
    private static boolean isFilter(ObjectNode condition) {
        var names = condition.fieldNames();

        while (names.hasNext()) {
            if (!CLAUSES.contains(names.next())) return false;
        }

        return true;
    }

    private static JsonNode node(Object value) {
        if (value instanceof Long number) return JSON.numberNode(number);
        if (value instanceof Double number) return JSON.numberNode(number);
        if (value instanceof Boolean bool) return JSON.booleanNode(bool);

        return JSON.textNode((String) value);
    }

    // A whole number Qdrant can hold as an ID: 0 up to the largest unsigned 64-bit one
    private static boolean isUnsigned(String text) {
        return text.matches("\\d{1,20}") && new BigInteger(text).bitLength() <= 64;
    }

    private static boolean isUuid(String text) {
        try {
            return UUID.fromString(text).toString().equalsIgnoreCase(text);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String written(Object value) {
        return value instanceof String text ? "'" + text + "'" : String.valueOf(value);
    }
}
