package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.AlterSettingsStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.AlterSystemStatement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.UpdateCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.UpdateQuotasPlan;

import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Plans ALTER TABLE ... SET (...) and ALTER TABLE ... ALTER COLUMN ... SET (...): the body
 * Qdrant's update-collection API takes, holding only what the statement names.
 *
 * <p>A setting can be written several ways, all meaning the same body: by its group and name
 * ({@code hnsw.m = 16}, or {@code hnsw_config.m} as Qdrant names the group), by its name alone
 * where one group only has it ({@code m = 16}), or with its whole group as JSON
 * ({@code hnsw = '{"m": 16}'}). Qdrant drops a name it doesn't know and answers that all went
 * well, so every name is checked here first.
 */
final class SettingsPlanner {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String INVALID_VALUE = "22023";
    private static final String SYNTAX = "42000";
    private static final String UNKNOWN_COLUMN = "42S22";

    Plan plan(AlterSettingsStatement statement, Catalog catalog) throws SQLException {
        String collection = WritePlanner.collection(statement.table(), "ALTER");

        if (statement.column() == null) {
            return new UpdateCollectionPlan(collection,
                body(CollectionSettings.COLLECTION, statement.options(), "A collection"));
        }

        TableSchema schema = catalog.table(collection);
        ColumnDefinition column = schema.column(statement.column())
            .filter(found -> found.kind() == ColumnKind.VECTOR)
            .orElseThrow(() -> new SQLSyntaxErrorException(schema.name() + " has no vector '"
                + statement.column() + "': ALTER COLUMN changes a vector's settings, and a "
                + "payload field has none", UNKNOWN_COLUMN));

        boolean sparse = column.type() == ColumnType.SPARSE_VECTOR;
        ObjectNode settings = body(
            sparse ? CollectionSettings.SPARSE_VECTOR : CollectionSettings.DENSE_VECTOR,
            statement.options(), sparse ? "A sparse vector" : "A vector");

        // Qdrant keeps the unnamed vector under the empty name, which is the column's source
        ObjectNode changes = JSON.objectNode();
        changes.putObject(sparse ? "sparse_vectors" : "vectors").set(column.source(), settings);

        return new UpdateCollectionPlan(schema.name(), changes);
    }

    /**
     * The server's settings, which so far are its quotas.
     */
    Plan plan(AlterSystemStatement statement) throws SQLException {
        ObjectNode body = body(CollectionSettings.SYSTEM, statement.options(), "The server");

        return new UpdateQuotasPlan(body.path(CollectionSettings.QUOTAS));
    }

    /**
     * The settings as Qdrant takes them.
     *
     * @param owner what the settings are of, to start a message with: "A collection"
     */
    private static ObjectNode body(SettingScope scope, Map<String, Expression> settings,
                                   String owner) throws SQLException {
        ObjectNode body = JSON.objectNode();

        for (Map.Entry<String, Expression> setting : settings.entrySet()) {
            String written = setting.getKey();
            int dot = written.indexOf('.');
            String head = (dot < 0 ? written : written.substring(0, dot)).toLowerCase(Locale.ROOT);
            String rest = dot < 0 ? null : written.substring(dot + 1);
            Object value = setting.getValue() instanceof Literal literal ? literal.value() : null;

            if (scope.metadata() && head.equals(CollectionSettings.METADATA)) {
                metadata(body, written, rest, value);
            } else if (scope.quantization()
                && CollectionSettings.QUANTIZATION_NAMES.contains(head)) {
                quantization(body, written, rest, value);
            } else if (group(scope, head) != null) {
                group(body, group(scope, head), written, rest, value);
            } else if (rest == null) {
                single(body, scope, owner, written, head, value);
            } else {
                throw new SQLSyntaxErrorException(owner + " has no settings called '" + head
                    + "', as in '" + written + "': it has " + String.join(", ", groups(scope)),
                    SYNTAX);
            }
        }

        return body;
    }

    // metadata = '{"k": "v"}' or metadata.k = 'v'; Qdrant merges either into what's there
    private static void metadata(ObjectNode body, String written, String key, Object value)
        throws SQLException {
        ObjectNode metadata = body.withObject(CollectionSettings.METADATA);

        if (key == null) {
            object(written, value).properties()
                .forEach(entry -> metadata.set(entry.getKey(), entry.getValue()));
        } else {
            // The key is everything after "metadata.", dots and case kept, as in runax.embed.dense
            metadata.set(key, MAPPER.valueToTree(value));
        }
    }

    // Quantization is one of several shapes, so it is JSON, or the word that turns it off
    private static void quantization(ObjectNode body, String written, String rest, Object value)
        throws SQLException {
        if (rest != null) {
            throw new SQLSyntaxErrorException("Setting '" + written + "' isn't one this driver "
                + "has: quantization is given whole, as quantization = '{\"scalar\": "
                + "{\"type\": \"int8\"}}', or quantization = disabled", SYNTAX);
        }

        if (value instanceof String text
            && text.equalsIgnoreCase(CollectionSettings.QUANTIZATION_OFF)) {
            put(body, CollectionSettings.QUANTIZATION_KEY, written,
                JSON.textNode(CollectionSettings.QUANTIZATION_DISABLED));
        } else {
            put(body, CollectionSettings.QUANTIZATION_KEY, written, object(written, value));
        }
    }

    // hnsw.m = 16, or the whole group as hnsw = '{"m": 16}'
    private static void group(ObjectNode body, SettingGroup group, String written, String rest,
                              Object value) throws SQLException {
        ObjectNode target = body.withObject(group.key());

        if (rest != null) {
            String field = rest.toLowerCase(Locale.ROOT);
            SettingKind kind = group.fields().get(field);

            if (kind == null) throw unknownField(group, written, field);

            put(target, field, written, value(written, kind, value));
            return;
        }

        for (Map.Entry<String, JsonNode> entry : object(written, value).properties()) {
            if (!group.fields().containsKey(entry.getKey())
                && !group.objects().contains(entry.getKey())) {
                throw unknownField(group, written, entry.getKey());
            }

            put(target, entry.getKey(), written, entry.getValue());
        }
    }

    // A name without a group: a setting outside any, or one that only one group has
    private static void single(ObjectNode body, SettingScope scope, String owner, String written,
                               String name, Object value) throws SQLException {
        SettingKind own = scope.own().get(name);
        if (own != null) {
            put(body, name, written, value(written, own, value));
            return;
        }

        List<SettingGroup> holders = scope.groups().stream()
            .filter(group -> group.fields().containsKey(name)).toList();

        if (holders.size() == 1) {
            SettingGroup group = holders.get(0);
            put(body.withObject(group.key()), name, written,
                value(written, group.fields().get(name), value));
            return;
        }

        if (holders.isEmpty()) {
            throw new SQLSyntaxErrorException(owner + " has no setting '" + written + "': its "
                + "settings are in " + String.join(", ", groups(scope)) + ", written as "
                + "group.name, such as " + example(scope), SYNTAX);
        }

        throw new SQLSyntaxErrorException("Setting '" + written + "' is in more than one group: "
            + "write " + String.join(" or ", holders.stream()
                .map(group -> group.name() + "." + name).toList()), SYNTAX);
    }

    private static SettingGroup group(SettingScope scope, String name) {
        return scope.groups().stream().filter(group -> group.named(name)).findFirst().orElse(null);
    }

    // Every name a statement can start a setting with, for a message
    private static List<String> groups(SettingScope scope) {
        var names = new TreeSet<String>(scope.own().keySet());
        scope.groups().forEach(group -> names.add(group.name()));
        if (scope.quantization()) names.add(CollectionSettings.QUANTIZATION_NAMES.get(0));
        if (scope.metadata()) names.add(CollectionSettings.METADATA);

        return List.copyOf(names);
    }

    private static String example(SettingScope scope) {
        SettingGroup group = scope.groups().get(scope.groups().size() > 1 ? 1 : 0);

        return group.name() + "." + new TreeSet<>(group.fields().keySet()).first();
    }

    private static SQLException unknownField(SettingGroup group, String written, String field) {
        if (group.objects().contains(field)) {
            return new SQLSyntaxErrorException("Setting '" + written + "' is an object, so it "
                + "goes in with its group as JSON: " + group.name() + " = '{\"" + field
                + "\": ...}'", SYNTAX);
        }

        return new SQLSyntaxErrorException("'" + field + "', as in '" + written + "', isn't a "
            + group.name() + " setting: they are " + String.join(", ",
            new TreeSet<>(group.fields().keySet())), SYNTAX);
    }

    // Two spellings of one setting in a statement is a mistake, whichever would win
    private static void put(ObjectNode target, String field, String written, JsonNode value)
        throws SQLException {
        if (target.has(field)) {
            throw new SQLSyntaxErrorException("Setting '" + written + "' gives '" + field
                + "' a second time in one statement", SYNTAX);
        }

        target.set(field, value);
    }

    private static JsonNode value(String written, SettingKind kind, Object value)
        throws SQLException {
        return switch (kind) {
            case BOOL -> {
                if (value instanceof Boolean bool) yield JSON.booleanNode(bool);
                if (value instanceof String text && (text.equalsIgnoreCase("true")
                    || text.equalsIgnoreCase("false"))) {
                    yield JSON.booleanNode(Boolean.parseBoolean(text));
                }
                throw invalid(written, "true or false", value);
            }
            case WHOLE -> {
                if (value instanceof Long number && number >= 0) yield JSON.numberNode(number);
                throw invalid(written, "a whole number of 0 or more", value);
            }
            case NUMBER -> {
                if (value instanceof Long number) yield JSON.numberNode(number);
                if (value instanceof Double number) yield JSON.numberNode(number);
                throw invalid(written, "a number", value);
            }
            case THREADS -> {
                if (value instanceof Long number && number >= 0) yield JSON.numberNode(number);
                if (value instanceof String text && text.equalsIgnoreCase("auto")) {
                    yield JSON.textNode("auto");
                }
                throw invalid(written, "a whole number of 0 or more, or auto", value);
            }
            case PERCENT -> {
                if (value == null) yield JSON.nullNode();
                if (value instanceof Long number && number >= 0 && number <= 100) {
                    yield JSON.numberNode(number);
                }
                throw invalid(written, "a whole number from 0 to 100, or NULL for no limit", value);
            }
            case WORD -> {
                if (value instanceof String text) yield JSON.textNode(text.toLowerCase(Locale.ROOT));
                throw invalid(written, "a word", value);
            }
        };
    }

    // A group given whole: JSON text holding an object
    private static ObjectNode object(String written, Object value) throws SQLException {
        if (value instanceof String text) {
            try {
                if (MAPPER.readTree(text) instanceof ObjectNode object) return object;
            } catch (JsonProcessingException e) {
                // Not JSON at all: the message below says what it takes
            }
        }

        throw invalid(written, "a JSON object, as '{\"name\": value}'", value);
    }

    private static SQLException invalid(String written, String expected, Object value) {
        return new SQLSyntaxErrorException("Setting '" + written + "' takes " + expected
            + ", not " + (value instanceof String text ? "'" + text + "'" : value),
            INVALID_VALUE);
    }
}
