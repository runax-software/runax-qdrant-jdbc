package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Ordering;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.embedding.EmbeddingModel;
import com.runaxsoftware.qdrant.jdbc.metadata.QdrantDatabaseMetaData;
import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.DriverColumns;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.FunctionCall;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.OrderItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Parameter;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.SelectItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Star;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.VectorLiteral;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.PerGroup;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SelectStatement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ConstantPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.EmbedText;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CountPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.FacetColumn;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.FacetPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.GroupedQueryPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.QueryPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ScrollPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.SystemTablePlan;
import com.runaxsoftware.qdrant.jdbc.system.SystemColumn;
import com.runaxsoftware.qdrant.jdbc.system.SystemTable;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class SelectPlanner {

    // The name PostgreSQL gives a SELECT item that isn't a column and has no alias
    private static final String UNNAMED = "?column?";

    // The function a search orders by, lower case: the driver's own names ignore case
    // The distances whose scores rank the lowest first
    private static final Set<String> LOWER_IS_BEST = Set.of("Euclid", "Manhattan");

    private static final String GROUPS_HAVE_NO_OFFSET =
        "Qdrant's grouped search has no offset";

    private static final String SIMILARITY = "similarity";

    // The one aggregate Qdrant answers itself, lower case, and the name PostgreSQL gives its
    // column when the statement gives it none
    private static final String COUNT = "count";

    // The payload indexes Qdrant can count the values of, for a GROUP BY
    private static final Set<PayloadIndexType> FACET_INDEXES = Set.of(PayloadIndexType.KEYWORD,
        PayloadIndexType.INTEGER, PayloadIndexType.BOOL, PayloadIndexType.UUID);

    // The payload indexes Qdrant can order by: the ones that compare values as a range
    private static final Set<PayloadIndexType> RANGE_INDEXES = Set.of(
        PayloadIndexType.INTEGER, PayloadIndexType.FLOAT, PayloadIndexType.DATETIME);

    // The driver's score column, which a search has and a scroll doesn't
    private static final String SCORE = DriverColumns.SCORE.columnName();

    // The catalog and the collections' schema, the names DatabaseMetaData reports
    private static final String CATALOG = QdrantDatabaseMetaData.CATALOG;
    private static final String COLLECTIONS_SCHEMA = QdrantDatabaseMetaData.SCHEMA;

    // The function that turns text into the vector a search searches with
    private static final String EMBED = "embed";

    // Why a plain or ordered read has to skip in the driver, for the error past the cap
    private static final String SCROLL_PAGES_BY_ID =
        "Qdrant pages a scroll by point ID rather than by a row count";

    // Why a GROUP BY has to skip in the driver, for the error past the cap
    private static final String FACET_HAS_NO_OFFSET = "Qdrant's facet API takes no offset at all";

    /**
     * What a statement's ORDER BY asks Qdrant to rank by.
     *
     * @param vector   the Qdrant name of the vector to search, empty for an unnamed one
     * @param query    the vector to search with, empty when the search embeds text
     * @param embed    the text to embed, or {@code null}
     */
    private record Search(String vector, List<Double> query, EmbedText embed) {
    }

    public Plan plan(SelectStatement statement, Catalog catalog) throws SQLException {
        if (statement.table() == null) {
            return constants(statement);
        }

        TableName table = statement.table();
        checkCatalog(table);

        return SystemTable.isSystemSchema(table.schema())
            ? systemTable(statement, table)
            : collection(statement, table, catalog);
    }

    // A collection, named on its own or in the schema the collections sit in
    private static Plan collection(SelectStatement statement, TableName table, Catalog catalog)
        throws SQLException {
        checkCollectionSchema(table);

        SelectItem counted = counted(statement.items());

        if (statement.perGroup() != null && (counted != null || !statement.groupBy().isEmpty())) {
            throw new SQLFeatureNotSupportedException("DISTINCT ON and LIMIT ... BY keep the best "
                + "points of each group, so they go with a search, not with COUNT(*) or GROUP BY, "
                + "which count the groups instead");
        }

        if (counted != null && statement.groupBy().isEmpty()) {
            if (statement.items().size() > 1) {
                throw new SQLFeatureNotSupportedException("COUNT(*) is a row of its own: a "
                    + "statement that counts a collection's points selects nothing besides it, "
                    + "unless it groups them with GROUP BY");
            }

            return count(statement, table, catalog, counted.alias());
        }

        TableSchema schema = catalog.table(table.name());

        if (!statement.groupBy().isEmpty()) {
            return facet(statement, schema, counted);
        }

        OrderItem order = order(statement);
        Search search = order == null ? null : search(order, schema, statement.alias());
        List<ColumnDefinition> columns =
            resolve(statement.items(), schema, statement.alias(), search != null);

        boolean withPayload = columns.stream().anyMatch(
            x -> x.kind() == ColumnKind.PAYLOAD || x.kind() == ColumnKind.PAYLOAD_FIELD);
        boolean withVector = columns.stream().anyMatch(x -> x.kind() == ColumnKind.VECTOR);
        JsonNode filter = FilterPlanner.plan(statement.where(), schema,
            column -> find(schema, column, statement.alias(), search != null));

        if (statement.perGroup() != null) {
            return grouped(statement, schema, search, columns, withPayload, withVector, filter);
        }

        if (search != null) {
            // Qdrant's Query API skips by row count itself, so this offset needs no cap
            return new QueryPlan(schema.name(), columns, search.vector(), search.query(),
                withPayload, withVector, statement.limit(), offset(statement), filter,
                search.embed());
        }

        Ordering ordering = order == null
            ? null : ordering(order, schema, statement.alias());

        return new ScrollPlan(schema.name(), columns, withPayload, withVector, statement.limit(),
            skip(statement, SCROLL_PAGES_BY_ID), filter, ordering);
    }

    /**
     * A search that keeps the best points of each group. The groups are the values of a payload
     * field holding text or whole numbers, the two kinds Qdrant groups by; it needs no index.
     */
    private static Plan grouped(SelectStatement statement, TableSchema schema, Search search,
                                List<ColumnDefinition> columns, boolean withPayload,
                                boolean withVector, JsonNode filter) throws SQLException {
        PerGroup perGroup = statement.perGroup();

        if (search == null) {
            throw new SQLFeatureNotSupportedException("DISTINCT ON and LIMIT ... BY keep the best "
                + "points of each group, and only a search says which are best: add ORDER BY "
                + "similarity(vector, [0.1, ...]) DESC. To count a field's values instead, use "
                + "GROUP BY with COUNT(*)");
        }
        if (perGroup.size() < 1) {
            throw new SQLSyntaxErrorException("LIMIT ... BY keeps at least one row of each "
                + "group, not " + perGroup.size(), "42000");
        }

        ColumnDefinition column = find(schema, perGroup.column(), statement.alias(), false);

        if (column.kind() != ColumnKind.PAYLOAD_FIELD
            || (column.type() != ColumnType.TEXT && column.type() != ColumnType.INTEGER)) {
            throw new SQLFeatureNotSupportedException("Grouping a search by '" + column.name()
                + "' of " + schema.name() + " isn't supported: Qdrant groups by a payload field "
                + "that holds text or whole numbers, and this one is "
                + (column.kind() == ColumnKind.PAYLOAD_FIELD ? column.type().typeName()
                : "not a payload field"));
        }

        // Euclid and Manhattan are distances, so the closest point has the lowest score
        boolean lowerIsBest = schema.collection().vectors().stream()
            .filter(vector -> vector.name().equals(search.vector()))
            .anyMatch(vector -> LOWER_IS_BEST.contains(String.valueOf(vector.distance())));

        return new GroupedQueryPlan(
            new QueryPlan(schema.name(), columns, search.vector(), search.query(), withPayload,
                withVector, statement.limit(), skip(statement, GROUPS_HAVE_NO_OFFSET), filter,
                search.embed()),
            column.source(), perGroup.size(), lowerIsBest);
    }

    /**
     * The rows an OFFSET passes over, 0 when the statement gives none: {@code OFFSET 0} is no
     * offset at all, as it is in SQL.
     */
    private static long offset(SelectStatement statement) {
        return statement.offset() == null ? 0 : statement.offset();
    }

    /**
     * The rows an OFFSET makes the driver read and throw away, checked against
     * {@link Planner#MAX_OFFSET}. Qdrant can't skip by a row count here, so the driver reads
     * those rows to discard them; past the cap the statement is an error that says so rather than
     * a read of far more rows than it returns.
     *
     * @param reason what Qdrant can't do, for the error
     */
    private static long skip(SelectStatement statement, String reason) throws SQLException {
        long offset = offset(statement);

        if (offset > Planner.MAX_OFFSET) {
            throw new SQLFeatureNotSupportedException("OFFSET " + offset + " isn't supported: "
                + reason + ", so the driver reads the rows it skips and discards them, and "
                + "skipping that many would read far more rows than the statement returns. "
                + "OFFSET up to " + Planner.MAX_OFFSET + " runs; past that, narrow the read with "
                + "a WHERE");
        }

        return offset;
    }

    /**
     * The one thing a statement orders by, or {@code null} when it orders by nothing. Qdrant
     * orders by one field or ranks by one score, so ordering by several is an error rather than
     * sorting the driver would have to do itself.
     */
    private static OrderItem order(SelectStatement statement) throws SQLException {
        if (statement.orderBy().isEmpty()) {
            return null;
        }
        if (statement.orderBy().size() > 1) {
            throw new SQLFeatureNotSupportedException(
                "Ordering by more than one thing isn't supported yet");
        }

        return statement.orderBy().get(0);
    }

    /**
     * The order a scroll reads points in: a payload field with a range index, which is what
     * Qdrant can order by, or {@code null} for its own order, by point ID.
     */
    private static Ordering ordering(OrderItem order, TableSchema schema, String alias)
        throws SQLException {
        if (!(order.expression() instanceof Column named)) {
            throw new SQLFeatureNotSupportedException("ORDER BY takes a column or "
                + SIMILARITY + "(vector, [0.1, ...]) DESC");
        }

        ColumnDefinition column = find(schema, named, alias, false);

        if (column.kind() == ColumnKind.ID) {
            if (order.descending()) {
                throw new SQLFeatureNotSupportedException("Qdrant reads points in order of their "
                    + "ID upwards, so ORDER BY " + column.name() + " DESC isn't supported");
            }

            return null;   // which is the order a scroll reads in anyway
        }

        if (column.kind() != ColumnKind.PAYLOAD_FIELD) {
            throw new SQLFeatureNotSupportedException("Ordering by '" + column.name() + "' of "
                + schema.name() + " isn't supported: Qdrant orders by a payload field");
        }

        PayloadIndexType index = schema.collection().payloadIndexes().get(column.source());

        // Set.of takes no null, and a field with no index at all is the common case
        if (index == null || !RANGE_INDEXES.contains(index)) {
            throw new SQLFeatureNotSupportedException("Ordering by '" + column.name() + "' of "
                + schema.name() + " needs a range index on it: Qdrant orders by a field indexed "
                + "as integer, float, or datetime, and this one is "
                + (index == null ? "not indexed" : "indexed as " + index.qdrantName()));
        }

        return new Ordering(column.source(), order.descending());
    }

    /**
     * The item a select list counts points with, {@code COUNT(*)} in any case and under any
     * name, or {@code null} when the list counts nothing. Qdrant's count API is the only
     * aggregate it runs, so every other call in a select list is an error here rather than work
     * the driver would have to do over a whole collection.
     */
    private static SelectItem counted(List<SelectItem> items) throws SQLException {
        for (SelectItem item : items) {
            if (!(item.expression() instanceof FunctionCall call)) {
                continue;
            }

            if (!call.is(COUNT)) {
                throw new SQLFeatureNotSupportedException("'" + call.name() + "' isn't an "
                    + "aggregate this driver has: Qdrant counts points, through COUNT(*), and "
                    + "computes no other aggregate over a collection");
            }
            if (call.arguments().size() != 1 || !(call.arguments().get(0) instanceof Star)) {
                throw new SQLFeatureNotSupportedException("COUNT takes a star, as COUNT(*): "
                    + "Qdrant counts the points of a collection, not the values of a field");
            }
            return item;
        }

        return null;
    }

    /**
     * A count of a collection's points. Qdrant's count API answers one number for the whole
     * collection, so the plan needs nothing but the collection's name and what to call the
     * column: no column of the collection is read, and none has to be looked up.
     *
     * @param alias the name the statement gives the count, or {@code null} for the driver's own
     */
    private static Plan count(SelectStatement statement, TableName table, Catalog catalog,
                              String alias) throws SQLException {
        if (!statement.orderBy().isEmpty()) {
            throw new SQLFeatureNotSupportedException(
                "ORDER BY isn't supported in a count: COUNT(*) is one row, in no order");
        }

        JsonNode filter = null;

        // A count of the whole collection needs none of its columns; a filter names some
        if (statement.where() != null) {
            TableSchema schema = catalog.table(table.name());
            filter = FilterPlanner.plan(statement.where(), schema,
                column -> find(schema, column, statement.alias(), false));
        }

        // A count is one row, so an OFFSET needs no cap: it either keeps that row or skips it
        return new CountPlan(table.name(), alias == null ? COUNT : alias, filter,
            statement.limit(), offset(statement));
    }

    /**
     * Counting the points per value of one payload field, which is what a GROUP BY with
     * COUNT(*) asks Qdrant's facet API for. Qdrant facets one field at a time, and only one
     * with an index it can facet on, so grouping by anything else is an error rather than
     * counting the driver would have to do over a whole collection.
     */
    private static Plan facet(SelectStatement statement, TableSchema schema, SelectItem counted)
        throws SQLException {
        if (statement.groupBy().size() > 1) {
            throw new SQLFeatureNotSupportedException("Grouping by more than one field isn't "
                + "supported: Qdrant counts the values of one field at a time");
        }
        if (!statement.orderBy().isEmpty()) {
            throw new SQLFeatureNotSupportedException("ORDER BY isn't supported on a GROUP BY "
                + "yet: Qdrant answers with the most common values first");
        }

        Column grouped = statement.groupBy().get(0);
        ColumnDefinition column = find(schema, grouped, statement.alias(), false);

        if (column.kind() != ColumnKind.PAYLOAD_FIELD) {
            throw new SQLFeatureNotSupportedException("Grouping by '" + column.name() + "' of "
                + schema.name() + " isn't supported: Qdrant counts the values of a payload "
                + "field");
        }

        PayloadIndexType index = schema.collection().payloadIndexes().get(column.source());

        if (index == null || !FACET_INDEXES.contains(index)) {
            throw new SQLFeatureNotSupportedException("Grouping by '" + column.name() + "' of "
                + schema.name() + " needs an index Qdrant can count values of: it counts a "
                + "field indexed as keyword, integer, bool, or uuid, and this one is "
                + (index == null ? "not indexed" : "indexed as " + index.qdrantName()));
        }

        JsonNode filter = FilterPlanner.plan(statement.where(), schema,
            named -> find(schema, named, statement.alias(), false));

        // Qdrant counts the values its index holds, so a group's type follows the index: an
        // array of keywords is a JSON column, but each group of it is one keyword
        ColumnType type = ColumnType.forIndex(index);

        return new FacetPlan(schema.name(), column.source(),
            facetColumns(statement, schema, column, type, counted), filter, statement.limit(),
            skip(statement, FACET_HAS_NO_OFFSET));
    }

    /**
     * The columns a grouped select list reads: the grouped field and the count, in the
     * statement's order. Qdrant answers a facet with those two and nothing else, so any other
     * column is an error, as it is in SQL for a column that isn't grouped.
     */
    private static List<FacetColumn> facetColumns(SelectStatement statement, TableSchema schema,
                                                  ColumnDefinition grouped, ColumnType type,
                                                  SelectItem counted) throws SQLException {
        var columns = new ArrayList<FacetColumn>();

        for (SelectItem item : statement.items()) {
            String name = item.alias();

            if (item == counted) {
                columns.add(new FacetColumn(name == null ? COUNT : name, ColumnType.INTEGER,
                    true));
                continue;
            }

            if (!(item.expression() instanceof Column named)) {
                throw new SQLFeatureNotSupportedException("A grouped statement selects the field "
                    + "it groups by and COUNT(*), and Qdrant answers a group with nothing else");
            }

            // Resolved the same way as the grouped field, so a name under the table matches too
            ColumnDefinition selected = find(schema, named, statement.alias(), false);

            if (!selected.source().equals(grouped.source())) {
                throw new SQLSyntaxErrorException("Column '" + selected.name() + "' of "
                    + schema.name() + " isn't grouped: a grouped statement selects the field it "
                    + "groups by, '" + grouped.name() + "', and COUNT(*)", "42S22");
            }

            columns.add(new FacetColumn(name == null ? selected.name() : name, type, false));
        }

        return columns;
    }

    /**
     * The search an ORDER BY asks for, or {@code null} when it orders by a column, which orders
     * a scroll instead.
     */
    private static Search search(OrderItem item, TableSchema schema, String alias)
        throws SQLException {
        if (!(item.expression() instanceof FunctionCall call)) {
            return null;   // a column, which orders a scroll instead
        }
        if (!call.is(SIMILARITY)) {
            throw new SQLFeatureNotSupportedException("'" + call.name() + "' isn't a function "
                + "this driver has; a search orders by " + SIMILARITY + "(vector, [0.1, ...]) "
                + "DESC");
        }
        if (!item.descending()) {
            throw new SQLFeatureNotSupportedException("A similarity search returns the closest "
                + "points first, so it needs ORDER BY " + SIMILARITY + "(...) DESC");
        }
        if (call.arguments().size() != 2) {
            throw new SQLSyntaxErrorException(SIMILARITY + " takes a vector column and a vector, "
                + "as " + SIMILARITY + "(dense, [0.1, 0.2])", "42000");
        }

        ColumnDefinition vector = searched(call.arguments().get(0), schema, alias);
        Expression with = call.arguments().get(1);

        if (with instanceof FunctionCall function && function.is(EMBED)) {
            return new Search(vector.source(), List.of(), embed(function, vector, schema));
        }

        if (vector.type() != ColumnType.VECTOR) {
            throw new SQLFeatureNotSupportedException("Searching '" + vector.name() + "', a "
                + vector.type().typeName() + " of " + schema.name() + ", with a vector written "
                + "out isn't supported yet: search it with " + EMBED + "('text', "
                + "'qdrant:qdrant/bm25'), which Qdrant embeds");
        }

        VectorLiteral query = vectorLiteral(with, vector);

        if (vector.size() != query.size()) {
            throw new SQLSyntaxErrorException("Vector '" + vector.name() + "' of " + schema.name()
                + " has " + vector.size() + " dimensions, the vector searching it has "
                + query.size(), "42000");
        }

        return new Search(vector.source(), query.values(), null);
    }

    /**
     * The text of {@code embed('refund policy')} and the model that embeds it. A vector is
     * searched well only with the model its points were embedded with, so that model can be kept
     * in the collection's metadata ({@code runax.embed.<vector>}): {@code embed} then needs no
     * model, and one that names another is an error rather than a search that silently finds
     * nonsense.
     */
    static EmbedText embed(FunctionCall call, ColumnDefinition vector, TableSchema schema)
        throws SQLException {
        String usage = EMBED + "('refund policy', 'provider:model'), as " + SIMILARITY + "("
            + vector.name() + ", " + EMBED + "('refund policy', 'openai:text-embedding-3-small'))";

        if (call.arguments().isEmpty() || call.arguments().size() > 2) {
            throw new SQLSyntaxErrorException(EMBED + " takes the text and, unless the collection "
                + "keeps one, the model: " + usage, "42000");
        }

        String text = text(call.arguments().get(0), usage);
        EmbeddingModel named = call.arguments().size() == 2
            ? EmbeddingModel.parse(text(call.arguments().get(1), usage))
            : null;
        EmbeddingModel kept = keptModel(vector, schema);

        if (named != null && kept != null && !named.equals(kept)) {
            throw new SQLSyntaxErrorException("Vector '" + vector.name() + "' of " + schema.name()
                + " is embedded with " + kept + ", as its " + EmbeddingModel.metadataKey(vector.name())
                + " metadata says, so searching it with " + named + " would find the wrong points: "
                + "leave the model out, or name " + kept, "42000");
        }

        EmbeddingModel model = named != null ? named : kept;
        if (model == null) {
            throw new SQLSyntaxErrorException(EMBED + " needs the model that embeds vector '"
                + vector.name() + "' of " + schema.name() + ": name it, as " + EMBED + "('"
                + text + "', 'openai:text-embedding-3-small'), or keep it in the collection's "
                + EmbeddingModel.metadataKey(vector.name()) + " metadata", "42000");
        }

        // The providers the driver calls make dense vectors; Qdrant's models make any kind
        if (!model.provider().embedsOnServer() && vector.type() != ColumnType.VECTOR) {
            throw new SQLFeatureNotSupportedException(model + " makes dense vectors, and '"
                + vector.name() + "' of " + schema.name() + " is a " + vector.type().typeName()
                + ": embed it with a model Qdrant runs, as qdrant:qdrant/bm25 for a sparse one");
        }

        // A size the ID asks for is known before the provider is called, so a mismatch fails here
        // rather than after paying for a vector that can't be stored
        if (model.dimensions() > 0 && vector.type() == ColumnType.VECTOR
            && model.dimensions() != vector.size()) {
            throw new SQLSyntaxErrorException(model + " is asked for " + model.dimensions()
                + " dimensions, and '" + vector.name() + "' of " + schema.name() + " holds "
                + vector.size() + ": ask for " + vector.size() + " dimensions, or leave the size "
                + "out for the model's own", "42000");
        }

        return new EmbedText(model, text, vector.type() == ColumnType.VECTOR ? vector.size() : 0);
    }

    // The model the collection's metadata keeps for the vector, or null when it keeps none
    private static EmbeddingModel keptModel(ColumnDefinition vector, TableSchema schema)
        throws SQLException {
        String key = EmbeddingModel.metadataKey(vector.name());
        String id = schema.collection().metadata().get(key);
        if (id == null) return null;

        try {
            return EmbeddingModel.parse(id);
        } catch (SQLException e) {
            throw new SQLException("The " + key + " metadata of " + schema.name() + " holds '"
                + id + "', which isn't a model: " + e.getMessage(), e.getSQLState(), e);
        }
    }

    private static String text(Expression argument, String usage) throws SQLException {
        if (argument instanceof Literal literal && literal.value() instanceof String text) {
            return text;
        }
        if (argument instanceof Parameter) {
            throw new SQLFeatureNotSupportedException("Parameters are given through a "
                + "PreparedStatement");
        }

        throw new SQLSyntaxErrorException(EMBED + " takes text in quotes: " + usage, "42000");
    }

    /**
     * The collection's vector a search reads, which is a vector column and not a payload field
     * or the point ID.
     */
    private static ColumnDefinition searched(Expression argument, TableSchema schema, String alias)
        throws SQLException {
        if (!(argument instanceof Column column)) {
            throw new SQLSyntaxErrorException("A similarity search names the vector to search "
                + "first, as " + SIMILARITY + "(dense, [0.1, 0.2])", "42000");
        }

        ColumnDefinition definition = find(schema, column, alias, false);

        if (definition.kind() != ColumnKind.VECTOR) {
            throw new SQLSyntaxErrorException("Column '" + definition.name() + "' of "
                + schema.name() + " isn't a vector: a similarity search searches a vector column",
                "42000");
        }
        return definition;
    }

    /**
     * The vector a search searches with, written out. Text to embed is an {@code embed(...)},
     * read above; the other forms a search could take come later.
     */
    private static VectorLiteral vectorLiteral(Expression argument, ColumnDefinition vector)
        throws SQLException {
        if (argument instanceof VectorLiteral literal) {
            return literal;
        }
        if (argument instanceof Parameter) {
            throw new SQLFeatureNotSupportedException("Parameters aren't supported yet");
        }
        if (argument instanceof FunctionCall call) {
            throw new SQLFeatureNotSupportedException("'" + call.name() + "' isn't supported yet: "
                + "a similarity search takes a vector written out, as [0.1, 0.2], or "
                + EMBED + "('text') to embed text");
        }

        throw new SQLSyntaxErrorException("A similarity search takes a vector written out in "
            + "brackets, as " + SIMILARITY + "(" + vector.name() + ", [0.1, 0.2])", "42000");
    }

    /**
     * One of the {@code system} schema's tables. Their columns are fixed, so this needs no
     * catalog lookup and never calls Qdrant.
     */
    private static Plan systemTable(SelectStatement statement, TableName name) throws SQLException {
        if (statement.where() != null) {
            throw new SQLFeatureNotSupportedException(
                "WHERE isn't supported yet on the " + SystemTable.SCHEMA + " schema's tables");
        }
        if (!statement.orderBy().isEmpty()) {
            throw new SQLFeatureNotSupportedException("ORDER BY isn't supported yet");
        }

        SystemTable table = SystemTable.named(name.name()).orElseThrow(
            () -> new SQLSyntaxErrorException("Unknown table '" + name.qualified() + "': the "
                + SystemTable.SCHEMA + " schema has " + systemTableNames(), "42S02"));

        if (counted(statement.items()) != null) {
            throw new SQLFeatureNotSupportedException("Counting " + name.qualified() + " isn't "
                + "supported: Qdrant counts the points of a collection, and a table of the "
                + SystemTable.SCHEMA + " schema describes the server instead");
        }

        List<SystemColumn> columns = resolve(statement.items(), table, statement.alias());

        // The table is read whole either way, so skipping its first rows needs no cap
        return new SystemTablePlan(table, columns, statement.limit(), offset(statement));
    }

    // A collection is named on its own or in the schema the collections sit in
    static void checkCollectionSchema(TableName table) throws SQLException {
        if (table.schema() != null && !table.schema().equalsIgnoreCase(COLLECTIONS_SCHEMA)) {
            throw new SQLSyntaxErrorException("Unknown schema '" + table.schema() + "': this "
                + "driver has " + COLLECTIONS_SCHEMA + " for the collections and "
                + SystemTable.SCHEMA + " for the server's own tables", "42S02");
        }
    }

    // Qdrant has no catalogs, so the driver's one catalog is the only name a statement can give
    static void checkCatalog(TableName table) throws SQLException {
        if (table.catalog() != null && !table.catalog().equalsIgnoreCase(CATALOG)) {
            throw new SQLSyntaxErrorException("Unknown catalog '" + table.catalog()
                + "': this driver has only " + CATALOG, "42S02");
        }
    }

    private static String systemTableNames() {
        return Arrays.stream(SystemTable.values())
            .map(SystemTable::tableName)
            .sorted()
            .collect(Collectors.joining(", "));
    }

    // A SELECT without FROM: every item is a constant, so the row needs no call to Qdrant
    private static ConstantPlan constants(SelectStatement statement) throws SQLException {
        if (statement.where() != null || !statement.orderBy().isEmpty()) {
            throw new SQLFeatureNotSupportedException(
                "WHERE and ORDER BY aren't supported in a SELECT without FROM");
        }

        var columns = new ArrayList<ResultColumn>();
        var row = new ArrayList<Object>();

        for (SelectItem item : statement.items()) {
            Object value = constant(item.expression());

            columns.add(ResultColumn.of(item.alias() != null ? item.alias() : UNNAMED, sqlType(value)));
            row.add(value);
        }

        boolean hasRow = (statement.limit() == null || statement.limit() > 0)
            && (statement.offset() == null || statement.offset() == 0);

        return new ConstantPlan(columns, hasRow ? List.of(Collections.unmodifiableList(row)) : List.of());
    }

    private static Object constant(Expression expression) throws SQLException {
        if (expression instanceof Literal literal) {
            return literal.value();
        }
        if (expression instanceof Star star) {
            throw new SQLSyntaxErrorException("SELECT " + star.qualified() + " needs a FROM",
                "42000");
        }
        if (expression instanceof Column column) {
            throw new SQLSyntaxErrorException(
                "Unknown column '" + column.qualified() + "': the SELECT has no FROM", "42S22");
        }
        if (expression instanceof Parameter) {
            throw new SQLFeatureNotSupportedException("Parameters aren't supported yet");
        }

        throw new SQLFeatureNotSupportedException(
            "Only constants are supported in a SELECT without FROM");
    }

    private static int sqlType(Object value) {
        if (value instanceof Long) {
            return Types.BIGINT;
        }
        if (value instanceof Double) {
            return Types.DOUBLE;
        }
        if (value instanceof Boolean) {
            return Types.BOOLEAN;
        }

        return Types.VARCHAR;
    }

    /**
     * The system table columns a select list reads, in its order: a star stands for all of
     * them. A column or star may be named under the table or the statement's alias for it.
     *
     * @param alias the name the statement gives the table, or {@code null}
     */
    private static List<SystemColumn> resolve(
        List<SelectItem> items, SystemTable table, String alias) throws SQLException {
        String name = SystemTable.SCHEMA + "." + table.tableName();
        var columns = new ArrayList<SystemColumn>();

        for (SelectItem item : items) {
            if (item.expression() instanceof Star star) {
                checkQualifier(star.qualifier(), star.qualified(), alias, table.tableName(), name,
                    true);
                columns.addAll(table.columns());
                continue;
            }

            if (!(item.expression() instanceof Column column)) {
                throw new SQLFeatureNotSupportedException(
                    "Constants in a SELECT with FROM aren't supported yet");
            }

            checkQualifier(column.qualifier(), column.qualified(), alias, table.tableName(), name,
                true);

            SystemColumn definition = table.column(column.name()).orElseThrow(
                () -> unknownColumn(column.qualified(), name));

            columns.add(item.alias() == null ? definition : definition.as(item.alias()));
        }

        return columns;
    }

    /**
     * The collection's columns a select list reads, in its order: a star stands for every
     * column but the vectors, which are fetched only when a statement names them, and the
     * score, which a collection has no column for. A column or star may be named under the
     * collection or the statement's alias for it.
     *
     * @param alias     the name the statement gives the collection, or {@code null}
     * @param searching whether the statement is a search, which is what has a score
     */
    private static List<ColumnDefinition> resolve(List<SelectItem> items, TableSchema schema,
                                                  String alias, boolean searching)
        throws SQLException {
        var columns = new ArrayList<ColumnDefinition>();

        for (SelectItem item : items) {
            if (item.expression() instanceof Star star) {
                checkQualifier(star.qualifier(), star.qualified(), alias, schema.name(),
                    schema.name(), false);
                schema.columns().stream()
                    .filter(x -> x.kind() != ColumnKind.VECTOR)
                    .forEach(columns::add);
                continue;
            }

            if (!(item.expression() instanceof Column column)) {
                throw new SQLFeatureNotSupportedException(
                    "Constants in a SELECT with FROM aren't supported yet");
            }

            ColumnDefinition definition = find(schema, column, alias, searching);

            columns.add(item.alias() == null ? definition : new ColumnDefinition(item.alias(),
                definition.kind(), definition.type(), definition.size(), definition.source(),
                definition.remarks()));
        }

        return columns;
    }

    /**
     * The collection column a select item names. A qualifier that doesn't name the collection
     * may still be the first part of a nested payload field written without quotes, as
     * {@code specs.color} is for the column {@code "specs.color"}, so the whole name is looked
     * up before the qualifier is reported as unknown.
     */
    static ColumnDefinition find(TableSchema schema, Column column, String alias,
                                         boolean searching) throws SQLException {
        if (column.qualifier() == null || namesTable(column.qualifier(), alias, schema.name(), false)) {
            if (column.name().equals(SCORE)) {
                return score(searching);
            }

            return schema.column(column.name()).orElseThrow(
                () -> unknownColumn(column.qualified(), schema.name()));
        }

        return schema.column(column.qualified()).orElseThrow(() -> payloadPath(column, schema)
            ? unknownColumn(column.qualified(), schema.name())
            : unknownTable(column.qualifier(), column.qualified(), alias, schema.name()));
    }

    /**
     * The score column, which only a search has: the points a scroll reads are in the
     * collection's own order and no closer to anything.
     */
    private static ColumnDefinition score(boolean searching) throws SQLException {
        if (!searching) {
            throw new SQLSyntaxErrorException("Column '" + SCORE + "' is only in a similarity "
                + "search: add ORDER BY " + SIMILARITY + "(vector, [0.1, ...]) DESC", "42S22");
        }

        return ColumnDefinition.score();
    }

    /**
     * Whether the name reads as a nested payload field rather than a column of another table:
     * the collection has fields under that path, so the field itself is what's missing.
     */
    private static boolean payloadPath(Column column, TableSchema schema) {
        String path = column.qualifier() + ".";

        return schema.columns().stream().anyMatch(x -> x.name().startsWith(path));
    }

    /**
     * Fails unless the qualifier names the table the statement reads.
     *
     * @param written    the name as the statement wrote it, for the error
     * @param alias      the name the statement gives the table, or {@code null}
     * @param table      the table's own name
     * @param reported   how to name the table in the error
     * @param ignoreCase whether the table's own name matches in any case, as the driver's own
     *                   names do and a collection name doesn't
     */
    private static void checkQualifier(String qualifier, String written, String alias,
                                       String table, String reported, boolean ignoreCase)
        throws SQLException {
        if (qualifier != null && !namesTable(qualifier, alias, table, ignoreCase)) {
            throw unknownTable(qualifier, written, alias, reported);
        }
    }

    /**
     * Whether a qualifier names the table a statement reads: the name the statement gives it
     * when it gives one, and the table's own name otherwise, as SQL does.
     */
    private static boolean namesTable(String qualifier, String alias, String table,
                                      boolean ignoreCase) {
        if (alias != null) {
            return qualifier.equals(alias);
        }

        return ignoreCase ? qualifier.equalsIgnoreCase(table) : qualifier.equals(table);
    }

    private static SQLSyntaxErrorException unknownColumn(String column, String table) {
        return new SQLSyntaxErrorException("Unknown column '" + column + "' in " + table, "42S22");
    }

    private static SQLSyntaxErrorException unknownTable(String qualifier, String written,
                                                       String alias, String table) {
        return new SQLSyntaxErrorException("Unknown table or alias '" + qualifier + "' in '"
            + written + "': this statement reads " + table
            + (alias == null ? "" : " as " + alias), "42S02");
    }
}
