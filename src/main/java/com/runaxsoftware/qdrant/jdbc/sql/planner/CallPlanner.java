package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.metadata.QdrantDatabaseMetaData;
import com.runaxsoftware.qdrant.jdbc.procedure.ArgumentKind;
import com.runaxsoftware.qdrant.jdbc.procedure.ArgumentTarget;
import com.runaxsoftware.qdrant.jdbc.procedure.Procedure;
import com.runaxsoftware.qdrant.jdbc.procedure.ProcedureArgument;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CallArgument;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CallStatement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ProcedurePlan;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plans a CALL: finds the procedure, matches the arguments to the ones it declares, by position
 * and then by name, and builds the one request it makes. Nothing is asked of Qdrant here: whether
 * the collection, the shard, or the peer is there is Qdrant's to say when the request runs.
 */
final class CallPlanner {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYNTAX = "42000";
    private static final String INVALID_VALUE = "22023";
    private static final String UNKNOWN_PROCEDURE = "42883";

    // What may stand before a procedure's name: the catalog, the schema, or both
    private static final List<List<String>> QUALIFIERS = List.of(
        List.of(), List.of(QdrantDatabaseMetaData.CATALOG), List.of(Procedure.SCHEMA),
        List.of(QdrantDatabaseMetaData.CATALOG, Procedure.SCHEMA));

    Plan plan(CallStatement statement) throws SQLException {
        Procedure procedure = procedure(statement);
        Map<ProcedureArgument, JsonNode> values = values(procedure, statement.arguments());

        String path = procedure.path();
        var query = new StringBuilder();
        ObjectNode fields = JSON.objectNode();
        Path file = null;
        String restores = null;

        for (Map.Entry<ProcedureArgument, JsonNode> entry : values.entrySet()) {
            ProcedureArgument argument = entry.getKey();
            JsonNode value = entry.getValue();

            if (procedure.restores() && argument.name().equals(Procedure.COLLECTION)) {
                restores = value.asText();
            }

            switch (argument.target()) {
                case PATH -> path = path.replace("{" + argument.name() + "}", encode(value.asText()));
                case QUERY -> query.append(query.length() == 0 ? '?' : '&').append(argument.name())
                    .append('=').append(encode(value.asText()));
                case BODY -> fields.set(argument.name(), value);
                case FILE -> file = file(procedure, argument, value.asText());
            }
        }

        return new ProcedurePlan(procedure, procedure.method(), path + query,
            body(procedure, fields), file, restores);
    }

    // The file a call names, as an absolute path, so what it means doesn't depend on the folder
    // the tool happened to start in
    private static Path file(Procedure procedure, ProcedureArgument argument, String text)
        throws SQLException {
        Path path;
        try {
            path = Path.of(text);
        } catch (InvalidPathException e) {
            throw new SQLSyntaxErrorException("Argument '" + argument.name() + "' of "
                + procedure.procedureName() + " isn't a file path: '" + text + "'",
                INVALID_VALUE, e);
        }

        if (!path.isAbsolute()) {
            throw new SQLSyntaxErrorException("Argument '" + argument.name() + "' of "
                + procedure.procedureName() + " takes the file's full path, not '" + text + "'",
                INVALID_VALUE);
        }

        return path.normalize();
    }

    private static Procedure procedure(CallStatement statement) throws SQLException {
        List<String> qualifier = statement.qualifier().stream()
            .map(part -> part.toLowerCase(Locale.ROOT)).toList();

        if (!QUALIFIERS.contains(qualifier)) {
            throw new SQLSyntaxErrorException("Unknown procedure '" + written(statement)
                + "': the driver's procedures are " + QdrantDatabaseMetaData.CATALOG + "."
                + Procedure.SCHEMA + "'s, called by name or as " + QdrantDatabaseMetaData.CATALOG
                + ".name", UNKNOWN_PROCEDURE);
        }

        return Procedure.named(statement.procedure()).orElseThrow(
            () -> new SQLSyntaxErrorException("Unknown procedure '" + written(statement)
                + "': there are " + String.join(", ", Arrays.stream(Procedure.values())
                .map(Procedure::procedureName).sorted().toList()), UNKNOWN_PROCEDURE));
    }

    // Each argument the call gives, checked, in the order the procedure declares them
    private static Map<ProcedureArgument, JsonNode> values(
        Procedure procedure, List<CallArgument> given) throws SQLException {
        List<ProcedureArgument> declared = procedure.arguments();
        var found = new LinkedHashMap<String, JsonNode>();
        boolean named = false;

        for (int i = 0; i < given.size(); i++) {
            CallArgument argument = given.get(i);
            ProcedureArgument target;

            if (argument.name() == null) {
                if (named) {
                    throw new SQLSyntaxErrorException("An argument given by position can't "
                        + "follow one given by name: " + signature(procedure), SYNTAX);
                }
                if (i >= declared.size()) {
                    throw new SQLSyntaxErrorException(procedure.procedureName() + " takes "
                        + (declared.isEmpty() ? "no arguments" : "at most " + declared.size()
                        + ": " + signature(procedure)), SYNTAX);
                }

                target = declared.get(i);
            } else {
                named = true;
                String name = argument.name().toLowerCase(Locale.ROOT);
                target = declared.stream().filter(x -> x.name().equals(name)).findFirst()
                    .orElseThrow(() -> new SQLSyntaxErrorException(procedure.procedureName()
                        + " has no argument '" + argument.name() + "': "
                        + signature(procedure), SYNTAX));
            }

            Object value = argument.value() instanceof Literal literal ? literal.value() : null;

            // A NULL is an argument left out, which only an optional one can be
            if (value == null) continue;

            if (found.put(target.name(), value(procedure, target, value)) != null) {
                throw new SQLSyntaxErrorException("Argument '" + target.name() + "' of "
                    + procedure.procedureName() + " is given twice", SYNTAX);
            }
        }

        var values = new LinkedHashMap<ProcedureArgument, JsonNode>();
        for (ProcedureArgument argument : declared) {
            JsonNode value = found.get(argument.name());

            if (value != null) values.put(argument, value);
            else if (argument.required()) {
                throw new SQLSyntaxErrorException(procedure.procedureName() + " needs '"
                    + argument.name() + "': " + signature(procedure), SYNTAX);
            }
        }

        return values;
    }

    // The body Qdrant takes: the arguments themselves, or held under the operation's name
    private static JsonNode body(Procedure procedure, ObjectNode fields) {
        if (procedure.wrapper() != null) {
            return JSON.objectNode().set(procedure.wrapper(), fields);
        }

        boolean takesBody = procedure.arguments().stream()
            .anyMatch(argument -> argument.target() == ArgumentTarget.BODY);

        return takesBody ? fields : null;
    }

    private static JsonNode value(Procedure procedure, ProcedureArgument argument, Object value)
        throws SQLException {
        ArgumentKind kind = argument.kind();

        switch (kind) {
            case TEXT -> {
                if (value instanceof String text && !text.isEmpty()) return JSON.textNode(text);
            }
            case WHOLE -> {
                if (value instanceof Long number && number >= 0) return JSON.numberNode(number);
            }
            case BOOL -> {
                if (value instanceof Boolean bool) return JSON.booleanNode(bool);
            }
            case KEY -> {
                if (value instanceof String text && !text.isEmpty()) return JSON.textNode(text);
                if (value instanceof Long number && number >= 0) return JSON.numberNode(number);
            }
            case JSON -> {
                if (value instanceof String text) {
                    try {
                        return MAPPER.readTree(text);
                    } catch (JsonProcessingException e) {
                        // Not JSON: the message below says what it takes
                    }
                }
            }
        }

        throw new SQLSyntaxErrorException("Argument '" + argument.name() + "' of "
            + procedure.procedureName() + " takes " + kind.expected() + ", not "
            + (value instanceof String text ? "'" + text + "'" : value), INVALID_VALUE);
    }

    // create_shard_key(collection, shard_key [, shards_number => ...]), for a message
    private static String signature(Procedure procedure) {
        var signature = new StringBuilder(procedure.procedureName()).append('(');
        boolean first = true;

        for (ProcedureArgument argument : procedure.arguments()) {
            if (argument.required()) {
                signature.append(first ? "" : ", ").append(argument.name());
            } else {
                signature.append(first ? "[" : " [, ").append(argument.name()).append(" => ...]");
            }
            first = false;
        }

        return signature.append(')').toString();
    }

    private static String written(CallStatement statement) {
        return statement.qualifier().isEmpty() ? statement.procedure()
            : String.join(".", statement.qualifier()) + "." + statement.procedure();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
