package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.embedding.Embedders;
import com.runaxsoftware.qdrant.jdbc.embedding.TextRole;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.gateway.models.FacetHit;
import com.runaxsoftware.qdrant.jdbc.gateway.models.FacetRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Document;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantAlias;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QueryRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScoredPoint;
import com.runaxsoftware.qdrant.jdbc.resultset.ListResultSet;
import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.sql.planner.Planner;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ConstantPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CountPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateIndexPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateViewPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DeletePointsPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropIndexPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropViewPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.EmbedText;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.FacetColumn;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.FacetPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.InsertPointsPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.NewPoint;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.QueryPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.RenameViewPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ScrollPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.SystemTablePlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.UpdatePointsPlan;
import com.runaxsoftware.qdrant.jdbc.system.QdrantSystemTables;
import com.runaxsoftware.qdrant.jdbc.system.SystemTableReader;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a plan and returns what it produced.
 */
public final class Executor {
    /**
     * Points per scroll request when the statement has no fetch size.
     */
    static final int DEFAULT_PAGE_SIZE = 100;

    /**
     * The most points one scroll request asks for, whatever the fetch size: a page is one HTTP
     * response held in memory at once.
     */
    static final int MAX_PAGE_SIZE = 1_000;

    private static final String UNKNOWN_TABLE = "42S02";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    // What Qdrant answers when it's asked to embed with a model and has no inference service
    private static final String NO_INFERENCE = "InferenceService URL not configured";

    /**
     * How many rows a one-call read returns, and whether the connection's default limit is what
     * said so, which the statement warns about: a read bounded by a number the SQL never
     * mentioned is worth saying out loud.
     *
     * @param limit     the rows to read
     * @param defaulted whether the connection's default limit set it
     */
    private record Bounded(long limit, boolean defaulted) {
    }

    private final Scroller scroller;
    private final Searcher searcher;
    private final Counter counter;
    private final Faceter faceter;
    private final SystemTableReader systemTables;
    private final int defaultLimit;
    private final Writer writer;
    private final Embedders embedders;

    /**
     * @param gateway where scroll plans read points, query plans rank them, count and facet
     *                plans count them, and system table plans read the server's own state from
     */
    public Executor(QdrantGateway gateway) {
        this(gateway, Embedders.none(), 0);
    }

    /**
     * @param gateway      as above
     * @param embedders    the providers a search's {@code embed()} calls, unless Qdrant embeds
     * @param defaultLimit how many rows a one-call read returns when the statement says
     *                     nothing, or 0 to make such a statement an error
     */
    public Executor(QdrantGateway gateway, Embedders embedders, int defaultLimit) {
        this(gateway::scroll, gateway::query, gateway::count, gateway::facet,
            new QdrantSystemTables(gateway), writer(gateway), embedders, defaultLimit);
    }

    /**
     * An executor that finds no search results, counts no points, and finds no system table
     * rows, for plans that read none of the three.
     */
    Executor(Scroller scroller) {
        this(scroller, query -> List.of(), (collection, filter) -> 0, facet -> List.of(),
            table -> List.of());
    }

    /**
     * An executor that runs no write plans.
     */
    Executor(Scroller scroller, Searcher searcher, Counter counter, Faceter faceter,
             SystemTableReader systemTables) {
        this(scroller, searcher, counter, faceter, systemTables, null);
    }

    Executor(Scroller scroller, Searcher searcher, Counter counter, Faceter faceter,
             SystemTableReader systemTables, Writer writer) {
        this(scroller, searcher, counter, faceter, systemTables, writer, Embedders.none(), 0);
    }

    Executor(Scroller scroller, Searcher searcher, Counter counter, Faceter faceter,
             SystemTableReader systemTables, Writer writer, Embedders embedders) {
        this(scroller, searcher, counter, faceter, systemTables, writer, embedders, 0);
    }

    Executor(Scroller scroller, Searcher searcher, Counter counter, Faceter faceter,
             SystemTableReader systemTables, Writer writer, Embedders embedders,
             int defaultLimit) {
        this.defaultLimit = defaultLimit;
        this.embedders = embedders;
        this.writer = writer;
        this.scroller = scroller;
        this.searcher = searcher;
        this.faceter = faceter;
        this.counter = counter;
        this.systemTables = systemTables;
    }

    /**
     * @param plan      the plan to run
     * @param statement the statement the result belongs to
     */
    public ExecutionResult execute(Plan plan, Statement statement) throws SQLException {
        long maxRows = statement.getLargeMaxRows();
        int fetchsize = statement.getFetchSize();

        if (plan instanceof ConstantPlan constant) {
            List<Object[]> rows = constant.rows().stream()
                .limit(maxRows > 0 ? maxRows : Long.MAX_VALUE)
                .map(List::toArray)
                .toList();
            return ExecutionResult.rows(new ListResultSet(constant.columns(), rows, statement));
        }

        if (plan instanceof ScrollPlan scroll) {
            Long limit = limit(scroll.limit(), maxRows);
            int pageSize;

            if (scroll.ordering() == null) {
                pageSize = fetchsize > 0 ? Math.min(fetchsize, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;

                return ExecutionResult.rows(
                    new ScrollResultSet(scroller, scroll, limit, pageSize, statement));
            }

            Bounded bounded = orderedLimit(limit);
            limit = bounded.limit();
            pageSize = onePage(limit, scroll.offset());

            return ExecutionResult.rows(
                new ScrollResultSet(scroller, scroll, limit, pageSize, statement),
                warning(bounded, "ORDER BY"));
        }

        if (plan instanceof QueryPlan query) {
            Bounded bounded = searchLimit(query, maxRows);
            long limit = bounded.limit();

            return ExecutionResult.rows(new ListResultSet(
                PointValues.resultColumns(query.columns(), query.collection()),
                limit == 0 ? List.of() : searchRows(query, limit), statement),
                warning(bounded, "A similarity search"));
        }

        if (plan instanceof CountPlan count) {
            // A count is always a number, so its column takes no nulls, and it belongs to no
            // collection: it's one number about one, not a column of it
            var column = new ResultColumn(count.column(), Types.BIGINT, "BIGINT", "", 0,
                ResultSetMetaData.columnNoNulls);

            return ExecutionResult.rows(new ListResultSet(List.of(column),
                countRows(count, limit(count.limit(), maxRows)), statement));
        }

        if (plan instanceof FacetPlan facet) {
            Bounded bounded = groupLimit(facet, maxRows);
            long limit = bounded.limit();

            return ExecutionResult.rows(new ListResultSet(resultColumns(facet),
                limit == 0 ? List.of() : facetRows(facet, limit), statement),
                warning(bounded, "GROUP BY"));
        }

        if (plan instanceof DeletePointsPlan delete) {
            return ExecutionResult.updated(delete(delete));
        }

        if (plan instanceof InsertPointsPlan insert) {
            return ExecutionResult.inserted(insert(insert));
        }

        if (plan instanceof UpdatePointsPlan update) {
            return ExecutionResult.updated(update(update));
        }

        if (plan instanceof DropCollectionPlan drop) {
            drop(drop);
            return ExecutionResult.updated(0);
        }

        if (plan instanceof CreateCollectionPlan create) {
            create(create);
            return ExecutionResult.updated(0);
        }

        if (plan instanceof CreateIndexPlan index) {
            createIndex(index);
            return ExecutionResult.updated(0);
        }

        if (plan instanceof DropIndexPlan index) {
            dropIndex(index);
            return ExecutionResult.updated(0);
        }

        if (plan instanceof CreateViewPlan view) {
            createView(view);
            return ExecutionResult.updated(0);
        }

        if (plan instanceof RenameViewPlan view) {
            renameView(view);
            return ExecutionResult.updated(0);
        }

        if (plan instanceof DropViewPlan view) {
            dropView(view);
            return ExecutionResult.updated(0);
        }

        if (plan instanceof SystemTablePlan system) {
            return ExecutionResult.rows(new ListResultSet(resultColumns(system),
                systemRows(system, limit(system.limit(), maxRows)), statement));
        }

        throw new SQLFeatureNotSupportedException(
            "Running a " + plan.getClass().getSimpleName() + " isn't supported yet");
    }

    /**
     * How many points an ordered scroll reads. Qdrant answers one in a single page, with no
     * next offset to read another, so one says how many: the statement's LIMIT, its maximum
     * rows, or the connection's default limit. A read that gives none of the three, on a
     * connection whose default is 0, is an error rather than the first page the driver happened
     * to ask for.
     */
    private Bounded orderedLimit(Long limit) throws SQLException {
        return bound(limit, "ORDER BY needs a LIMIT: Qdrant answers an ordered read in one page, "
            + "so a statement says how many points to read");
    }

    /**
     * One page holding every row an ordered read returns, and the points an OFFSET skips before
     * them, since there's no second page to ask for and Qdrant rejects an {@code offset} on an
     * ordered scroll outright. The fetch size doesn't cut it down: that would drop rows the
     * statement asked for.
     *
     * @param offset the points to skip, which the driver discards from this page
     */
    private static int onePage(long limit, long offset) {
        return (int) Math.max(1, Math.min(limit, Integer.MAX_VALUE - offset) + offset);
    }

    /**
     * Deletes the plan's points and returns how many there were. Qdrant doesn't say how many a
     * delete removed, so they're counted first: exact unless something writes to the collection
     * between the two calls.
     */
    private long delete(DeletePointsPlan plan) throws SQLException {
        long count = writer.count(plan.collection(), plan.filter());

        if (count > 0) {
            writer.deletePoints(plan.collection(), plan.filter());
        }

        return count;
    }

    /**
     * Adds the plan's points and returns their IDs.
     *
     * <p>Without an ON CONFLICT clause a point ID the statement gives mustn't be taken, as a
     * primary key mustn't: Qdrant's upsert would replace that point, so the IDs are looked up
     * first and the {@code insert_only} mode skips any point that arrives between the lookup and
     * the write. With one, no lookup is needed — {@code DO NOTHING} is what {@code insert_only}
     * already does to a point that's there, and {@code DO UPDATE} is Qdrant's {@code upsert}.
     *
     * <p>The IDs returned are the ones the statement gave or the driver made, whether or not
     * Qdrant went on to write each point: a {@code DO NOTHING} that skipped one doesn't say so.
     */
    private List<String> insert(InsertPointsPlan plan) throws SQLException {
        if (plan.onConflict().checksIds()) {
            List<JsonNode> given = plan.points().stream()
                .filter(NewPoint::idGiven)
                .map(NewPoint::id)
                .toList();

            if (!given.isEmpty()) {
                List<JsonNode> taken = writer.existingIds(plan.collection(), given);

                if (!taken.isEmpty()) {
                    throw new SQLException("Point " + taken.get(0) + " is already in "
                        + plan.collection() + ": INSERT adds new points, and UPDATE changes one. "
                        + "Add ON CONFLICT (_id) DO NOTHING to skip the points that are there, "
                        + "or DO UPDATE to replace them", "23505");
                }
            }
        }

        var points = new ArrayList<JsonNode>(plan.points().size());
        for (NewPoint point : plan.points()) points.add(point(point, plan.collection()));

        writer.insertPoints(plan.collection(), points, plan.onConflict().replaces(),
            plan.updateFilter());

        return plan.points().stream().map(x -> x.id().asText()).toList();
    }

    /**
     * A point as Qdrant takes it, with the vectors the driver embeds made now: the unnamed
     * vector on its own, named ones under their names.
     */
    private JsonNode point(NewPoint point, String collection) throws SQLException {
        Map<String, JsonNode> vectors = new LinkedHashMap<>(point.vectors());

        for (Map.Entry<String, EmbedText> embed : point.embeds().entrySet()) {
            EmbedText text = embed.getValue();
            List<Double> vector = embedders.embedder(text.model().provider())
                .embed(text.model(), TextRole.DOCUMENT, text.text());

            if (vector.size() != text.dimensions()) {
                throw new SQLException(text.model() + " made a vector of " + vector.size()
                    + " dimensions, and '" + embed.getKey() + "' of " + collection + " has "
                    + text.dimensions(), "42000");
            }

            ArrayNode values = JSON.arrayNode();
            vector.forEach(values::add);
            vectors.put(embed.getKey(), values);
        }

        ObjectNode json = JSON.objectNode();
        json.set("id", point.id());
        json.set("payload", point.payload());

        if (vectors.containsKey("")) {
            json.set("vector", vectors.get(""));
        } else {
            ObjectNode named = json.putObject("vector");
            vectors.forEach(named::set);
        }

        return json;
    }

    /**
     * Changes the plan's points and returns how many there were, counted first, as a delete's
     * are: Qdrant doesn't say how many points a payload change reached.
     */
    private long update(UpdatePointsPlan plan) throws SQLException {
        long count = writer.count(plan.collection(), plan.filter());

        if (count == 0) {
            return 0;
        }

        var operations = new ArrayList<>(plan.operations());

        if (plan.writesVectors()) {
            operations.add(vectorWrite(plan));
        }
        if (!plan.cleared().isEmpty()) {
            operations.add(vectorDelete(plan));
        }

        writer.updatePoints(plan.collection(), operations);

        return count;
    }

    /**
     * Writing a vector, as Qdrant's {@code update_vectors}. That operation takes point IDs and
     * rejects a filter alone, so the matching IDs are read first — the one write the driver
     * pays a read for, which is why it's capped like a deep OFFSET. The filter goes along as
     * the operation's {@code update_filter}, so a point that stops matching between the read
     * and the write is left alone rather than written to on the strength of a stale ID.
     */
    private JsonNode vectorWrite(UpdatePointsPlan plan) throws SQLException {
        List<JsonNode> ids = writer.matchingIds(plan.collection(), plan.filter(),
            (int) Planner.MAX_OFFSET);

        if (ids.size() > Planner.MAX_OFFSET) {
            throw new SQLFeatureNotSupportedException("Writing a vector to more than "
                + Planner.MAX_OFFSET + " points of " + plan.collection() + " isn't supported: "
                + "Qdrant writes vectors to the points an UPDATE names, so the driver reads the "
                + "matching IDs first and that read is capped. Narrow the WHERE, or re-insert "
                + "the points instead");
        }

        Map<String, JsonNode> vectors = embedded(plan, plan.collection());
        ArrayNode points = JSON.arrayNode();

        for (JsonNode id : ids) {
            ObjectNode point = JSON.objectNode();
            point.set("id", id);

            // Named vectors go under their names; the unnamed one is the vector itself
            if (vectors.containsKey("")) {
                point.set("vector", vectors.get(""));
            } else {
                ObjectNode named = point.putObject("vector");
                vectors.forEach(named::set);
            }

            points.add(point);
        }

        ObjectNode change = JSON.objectNode();
        change.set("points", points);
        change.set("update_filter", plan.filter());

        return JSON.objectNode().set("update_vectors", change);
    }

    /**
     * Deleting a vector, as Qdrant's {@code delete_vectors}, which takes the filter as it is:
     * no IDs to read, so no cap.
     */
    private static JsonNode vectorDelete(UpdatePointsPlan plan) {
        ArrayNode names = JSON.arrayNode();
        plan.cleared().forEach(names::add);

        ObjectNode change = JSON.objectNode();
        change.set("filter", plan.filter());
        change.set("vector", names);

        return JSON.objectNode().set("delete_vectors", change);
    }

    /**
     * The vectors to write, with the ones the driver embeds made now. The text goes to the
     * provider once for the whole statement, not once per point: an UPDATE writes the same
     * vector to every point its WHERE matches.
     */
    private Map<String, JsonNode> embedded(UpdatePointsPlan plan, String collection)
        throws SQLException {
        Map<String, JsonNode> vectors = new LinkedHashMap<>(plan.vectors());

        for (Map.Entry<String, EmbedText> embed : plan.embeds().entrySet()) {
            EmbedText text = embed.getValue();
            List<Double> vector = embedders.embedder(text.model().provider())
                .embed(text.model(), TextRole.DOCUMENT, text.text());

            if (vector.size() != text.dimensions()) {
                throw new SQLException(text.model() + " made a vector of " + vector.size()
                    + " dimensions, and '" + embed.getKey() + "' of " + collection + " has "
                    + text.dimensions(), "42000");
            }

            ArrayNode values = JSON.arrayNode();
            vector.forEach(values::add);
            vectors.put(embed.getKey(), values);
        }

        return vectors;
    }

    private void drop(DropCollectionPlan plan) throws SQLException {
        try {
            writer.deleteCollection(plan.collection());
        } catch (SQLException e) {
            if (!plan.ifExists() || !UNKNOWN_TABLE.equals(e.getSQLState())) {
                throw e;
            }
        }
    }

    /**
     * Creates the collection, then its indexes. Qdrant can't do both in one call, so when an
     * index fails the collection is deleted again: a CREATE TABLE either makes the whole table
     * or none of it.
     */
    private void create(CreateCollectionPlan plan) throws SQLException {
        if (plan.ifNotExists() && writer.collectionExists(plan.collection())) {
            return;
        }

        writer.createCollection(plan.collection(), plan.config());

        try {
            for (CreateCollectionPlan.PayloadIndex index : plan.indexes()) {
                writer.createIndex(plan.collection(), index.field(), JSON.textNode(index.type()));
            }
        } catch (SQLException e) {
            try {
                writer.deleteCollection(plan.collection());
            } catch (SQLException undo) {
                e.addSuppressed(undo);
            }

            throw e;
        }
    }

    /**
     * The search request: the vector written out, the vector a provider made of the text, or
     * the text itself for Qdrant to embed.
     */
    private QueryRequest request(QueryPlan plan, long limit) throws SQLException {
        EmbedText embed = plan.embed();
        List<Double> query = plan.query();
        Document document = null;

        if (embed != null && embed.model().provider().embedsOnServer()) {
            document = new Document(embed.text(), embed.model().name());
        } else if (embed != null) {
            query = embedders.embedder(embed.model().provider())
                .embed(embed.model(), TextRole.QUERY, embed.text());

            if (query.size() != embed.dimensions()) {
                throw new SQLException(embed.model() + " made a vector of " + query.size()
                    + " dimensions, and '" + plan.vector() + "' of " + plan.collection() + " has "
                    + embed.dimensions() + ": it was embedded with another model", "42000");
            }
        }

        return new QueryRequest(plan.collection(), plan.vector(), query, limit, plan.offset(),
            plan.withPayload(), plan.withVector(), plan.filter(), document);
    }

    /**
     * The search's points. A Qdrant without an inference service can't embed a document with a
     * hosted model, and says so in terms of its own configuration, so the error says what to do.
     */
    private List<ScoredPoint> search(QueryRequest request) throws SQLException {
        try {
            return searcher.search(request);
        } catch (SQLException e) {
            if (request.document() == null || !String.valueOf(e.getMessage()).contains(NO_INFERENCE)) {
                throw e;
            }

            throw new SQLException("This Qdrant can't embed text with 'qdrant:"
                + request.document().model() + "': it runs no inference service, which hosted "
                + "models need (Qdrant Cloud has one). Embed with a model the driver calls, as "
                + "openai:text-embedding-3-small, or with qdrant:qdrant/bm25 for a sparse vector",
                e.getSQLState(), e);
        }
    }

    /**
     * Creates the index unless the field has one. Qdrant would replace an index already there
     * with the new one without a word, so the driver looks first: changing an index's type is a
     * DROP INDEX and a CREATE INDEX, said on purpose.
     */
    private void createIndex(CreateIndexPlan plan) throws SQLException {
        PayloadIndexType existing = writer.payloadIndexes(plan.collection()).get(plan.field());

        if (existing != null) {
            if (plan.ifNotExists()) return;

            throw new SQLException("'" + plan.field() + "' of " + plan.collection() + " already "
                + "has an index, " + plan.field() + "_idx, of type " + existing.qdrantName()
                + ": drop it first to change it", "42S11");
        }

        writer.createIndex(plan.collection(), plan.field(), plan.schema());
    }

    /**
     * Deletes the index on the first field the plan names that has one. Qdrant answers a field
     * without an index as a success, so the driver looks first, and reports a missing one.
     */
    private void dropIndex(DropIndexPlan plan) throws SQLException {
        Map<String, PayloadIndexType> indexes = writer.payloadIndexes(plan.collection());
        String field = plan.fields().stream().filter(indexes::containsKey).findFirst()
            .orElse(null);

        if (field == null) {
            if (plan.ifExists()) return;

            List<String> names = indexes.keySet().stream().sorted().map(x -> x + "_idx").toList();
            throw new SQLException("No index '" + plan.written() + "' on " + plan.collection()
                + ": its indexes are " + (names.isEmpty() ? "none" : String.join(", ", names)),
                "42S12");
        }

        writer.deleteIndex(plan.collection(), field);
    }

    /**
     * Creates the alias, or with OR REPLACE moves it, in one atomic change. Qdrant would move
     * an existing alias on a plain create too, so the driver looks first and makes that an
     * error unless OR REPLACE asked for it.
     */
    private void createView(CreateViewPlan plan) throws SQLException {
        List<QdrantAlias> aliases = writer.aliases();
        QdrantAlias existing = alias(aliases, plan.view());
        QdrantAlias target = alias(aliases, plan.collection());

        if (target != null) {
            throw new SQLException("'" + plan.collection() + "' is a view of "
                + target.collection() + ": a view shows a collection, so name "
                + target.collection(), "42000");
        }
        if (existing != null && !plan.orReplace()) {
            throw new SQLException("View '" + plan.view() + "' already exists, showing "
                + existing.collection() + ": use CREATE OR REPLACE VIEW to move it", "42S01");
        }

        var actions = new ArrayList<JsonNode>();
        if (existing != null) {
            actions.add(JSON.objectNode().set("delete_alias",
                JSON.objectNode().put("alias_name", plan.view())));
        }
        actions.add(JSON.objectNode().set("create_alias", JSON.objectNode()
            .put("collection_name", plan.collection()).put("alias_name", plan.view())));

        writer.changeAliases(actions);
    }

    // Qdrant would move an alias already called the new name, so that's checked too
    private void renameView(RenameViewPlan plan) throws SQLException {
        List<QdrantAlias> aliases = writer.aliases();

        if (alias(aliases, plan.view()) == null) {
            throw new SQLException("Unknown view '" + plan.view() + "'", "42S02");
        }
        if (alias(aliases, plan.newName()) != null) {
            throw new SQLException("View '" + plan.newName() + "' already exists", "42S01");
        }

        writer.changeAliases(List.of(JSON.objectNode().set("rename_alias", JSON.objectNode()
            .put("old_alias_name", plan.view()).put("new_alias_name", plan.newName()))));
    }

    // Qdrant answers a missing alias as deleted, so the driver looks first
    private void dropView(DropViewPlan plan) throws SQLException {
        if (alias(writer.aliases(), plan.view()) == null) {
            if (plan.ifExists()) return;
            throw new SQLException("Unknown view '" + plan.view() + "'", "42S02");
        }

        writer.changeAliases(List.of(JSON.objectNode().set("delete_alias",
            JSON.objectNode().put("alias_name", plan.view()))));
    }

    private static QdrantAlias alias(List<QdrantAlias> aliases, String name) {
        return aliases.stream().filter(x -> x.name().equals(name)).findFirst().orElse(null);
    }

    private static Writer writer(QdrantGateway gateway) {
        return new Writer() {
            @Override
            public long count(String collection, JsonNode filter) throws SQLException {
                return gateway.count(collection, filter);
            }

            @Override
            public void deletePoints(String collection, JsonNode filter) throws SQLException {
                gateway.deletePoints(collection, filter);
            }

            @Override
            public List<JsonNode> existingIds(String collection, List<JsonNode> ids)
                throws SQLException {
                return gateway.existingIds(collection, ids);
            }

            @Override
            public void insertPoints(String collection, List<JsonNode> points,
                                     boolean replaceExisting, JsonNode updateFilter)
                throws SQLException {
                gateway.insertPoints(collection, points, replaceExisting, updateFilter);
            }

            @Override
            public void updatePoints(String collection, List<JsonNode> operations)
                throws SQLException {
                gateway.updatePoints(collection, operations);
            }

            @Override
            public List<JsonNode> matchingIds(String collection, JsonNode filter, int limit)
                throws SQLException {
                return gateway.matchingIds(collection, filter, limit);
            }

            @Override
            public void deleteCollection(String collection) throws SQLException {
                gateway.deleteCollection(collection);
            }

            @Override
            public boolean collectionExists(String collection) throws SQLException {
                return gateway.collectionExists(collection);
            }

            @Override
            public void createCollection(String collection, JsonNode config) throws SQLException {
                gateway.createCollection(collection, config);
            }

            @Override
            public void createIndex(String collection, String field, JsonNode schema)
                throws SQLException {
                gateway.createIndex(collection, field, schema);
            }

            @Override
            public Map<String, PayloadIndexType> payloadIndexes(String collection)
                throws SQLException {
                return gateway.collection(collection).payloadIndexes();
            }

            @Override
            public void deleteIndex(String collection, String field) throws SQLException {
                gateway.deleteIndex(collection, field);
            }

            @Override
            public List<QdrantAlias> aliases() throws SQLException {
                return gateway.listAliases();
            }

            @Override
            public void changeAliases(List<JsonNode> actions) throws SQLException {
                gateway.changeAliases(actions);
            }
        };
    }

    /**
     * The groups' rows, the most common value first, holding the value and the count in the
     * statement's order. One call answers the whole GROUP BY, so the rows are read at once.
     *
     * <p>An OFFSET is skipped here rather than by Qdrant: its facet API has no offset field, and
     * ignores one instead of reporting it, so sending the statement's offset would quietly
     * answer with the groups the statement asked to pass over. The call counts the groups the
     * statement returns and the ones it skips together, and the first are dropped.
     */
    private List<Object[]> facetRows(FacetPlan plan, long limit) throws SQLException {
        long offset = plan.offset();
        List<FacetHit> counted = faceter.facet(new FacetRequest(plan.collection(), plan.key(),
            total(limit, offset), plan.filter()));
        List<FacetHit> hits = offset >= counted.size()
            ? List.of()
            : counted.subList((int) offset, counted.size());
        List<FacetColumn> columns = plan.columns();
        var rows = new ArrayList<Object[]>(hits.size());

        for (FacetHit hit : hits) {
            Object[] row = new Object[columns.size()];

            for (int i = 0; i < row.length; i++) {
                FacetColumn column = columns.get(i);
                row[i] = column.counting() ? hit.count() : value(hit.value(), column.type());
            }

            rows.add(row);
        }

        return rows;
    }

    /**
     * A grouped value, as the column's type. Qdrant counts the values of a field indexed as a
     * keyword, a whole number, a bool, or a UUID, so each is one of those three kinds of JSON.
     */
    private static Object value(JsonNode value, ColumnType type) {
        if (value == null || value.isNull()) {
            return null;
        }

        return switch (type) {
            case INTEGER -> value.longValue();
            case BOOLEAN -> value.booleanValue();
            default -> value.asText();
        };
    }

    /**
     * How many groups a GROUP BY reads. Qdrant counts the most common values and returns no
     * cursor, so a statement says how many: its LIMIT, its maximum rows, or the connection's
     * default limit. One that gives none of the three, on a connection whose default is 0, is an
     * error rather than the ten Qdrant counts by default.
     */
    private Bounded groupLimit(FacetPlan plan, long maxRows) throws SQLException {
        return bound(limit(plan.limit(), maxRows), "GROUP BY needs a LIMIT: Qdrant counts the "
            + "most common values of a field in one call, so a statement says how many groups "
            + "to read");
    }

    private static List<ResultColumn> resultColumns(FacetPlan plan) {
        return plan.columns().stream()
            .map(x -> new ResultColumn(x.name(), x.type().sqlType(), x.type().typeName(),
                plan.collection(), 0,
                x.nullable() ? ResultSetMetaData.columnNullable : ResultSetMetaData.columnNoNulls))
            .toList();
    }

    /**
     * The search's rows, one per point Qdrant ranked, best first. One call answers the whole
     * search, so the rows are read at once rather than paged. An OFFSET goes to Qdrant, whose
     * Query API counts rows the way SQL does, so the limit stays the rows returned after it and
     * the driver discards nothing.
     */
    private List<Object[]> searchRows(QueryPlan plan, long limit) throws SQLException {
        List<ScoredPoint> found = search(request(plan, limit));
        List<ColumnDefinition> columns = plan.columns();
        var rows = new ArrayList<Object[]>(found.size());

        for (ScoredPoint scored : found) {
            Object[] row = new Object[columns.size()];

            for (int i = 0; i < row.length; i++) {
                row[i] = PointValues.of(scored.point(), scored.score(), columns.get(i));
            }

            rows.add(row);
        }

        return rows;
    }

    /**
     * How many points a search asks Qdrant to rank: its LIMIT, or the statement's maximum rows,
     * whichever is smaller, or the connection's default limit when it gives neither. Qdrant
     * ranks the points nearest the vector rather than reading the whole collection, so
     * something has to say how many, and a search on a connection whose default is 0 is an
     * error instead of a number the driver picked.
     */
    private Bounded searchLimit(QueryPlan plan, long maxRows) throws SQLException {
        return bound(limit(plan.limit(), maxRows), "A similarity search needs a LIMIT: Qdrant "
            + "ranks the points nearest the vector, not the whole collection");
    }

    /**
     * The rows a one-call read returns: the limit the statement worked out, or the connection's
     * default limit when it gave none. A connection whose default is 0 asks for the limit
     * instead, with the message that says why this read needs one.
     */
    private Bounded bound(Long limit, String needsALimit) throws SQLException {
        if (limit != null) {
            return new Bounded(Math.max(limit, 0), false);
        }
        if (defaultLimit == 0) {
            throw new SQLSyntaxErrorException(needsALimit, "42000");
        }

        return new Bounded(defaultLimit, true);
    }

    /**
     * What the statement warns when the connection's default limit bounded a read: the rows are
     * as many as the driver asked for rather than as many as Qdrant holds, and the SQL never
     * said so. It says "at most" because a read may well return fewer, and nothing here knows
     * yet how many a lazy result set will yield.
     */
    private static String warning(Bounded bounded, String read) {
        return bounded.defaulted()
            ? read + " read at most " + bounded.limit() + " rows, the connection's defaultLimit, "
                + "because the statement gave no LIMIT and set no maximum rows"
            : null;
    }

    /**
     * The count's single row, or no row at all when the statement asks for none: a limit of no
     * rows leaves it out, as it does the row of a SELECT without FROM, and so does any OFFSET,
     * since a count is one row and an offset of one is already past it. Either way nothing is
     * counted.
     */
    private List<Object[]> countRows(CountPlan plan, Long limit) throws SQLException {
        if ((limit != null && limit <= 0) || plan.offset() > 0) {
            return List.of();
        }

        return List.<Object[]>of(new Object[]{counter.count(plan.collection(), plan.filter())});
    }

    /**
     * The system table's rows, cut to the limit and holding only the columns the statement
     * asked for, in its order. A system table is small and its rows describe one moment, so
     * they're read at once rather than paged, which is also why an OFFSET costs nothing here:
     * the rows it skips are already in memory.
     */
    private List<Object[]> systemRows(SystemTablePlan plan, Long limit) throws SQLException {
        if (limit != null && limit <= 0) {
            return List.of();
        }

        int[] sources = plan.columns().stream().mapToInt(plan.table()::indexOf).toArray();
        List<Object[]> rows = new ArrayList<>();
        long skipped = 0;

        for (Object[] row : systemTables.read(plan.table())) {
            if (skipped < plan.offset()) {
                skipped++;
                continue;
            }

            if (limit != null && rows.size() >= limit) {
                break;
            }

            Object[] projected = new Object[sources.length];
            for (int i = 0; i < sources.length; i++) projected[i] = row[sources[i]];

            rows.add(projected);
        }

        return rows;
    }

    private static List<ResultColumn> resultColumns(SystemTablePlan plan) {
        return plan.columns().stream()
            .map(x -> new ResultColumn(
                x.name(),
                x.type().sqlType(),
                x.type().typeName(),
                plan.table().tableName(),
                0,
                x.nullable()
                    ? ResultSetMetaData.columnNullable
                    : ResultSetMetaData.columnNoNulls))
            .toList();
    }

    private static Long limit(Long sqlLimit, long maxRows) {
        if (maxRows <= 0) {
            return sqlLimit;
        }

        return sqlLimit == null
            ? maxRows
            : Math.min(sqlLimit, maxRows);
    }

    /**
     * How many rows to ask Qdrant for when the driver skips the offset itself: the rows the
     * statement returns and the ones it passes over. A limit near {@code Long.MAX_VALUE} is a
     * limit of everything, so the two saturate rather than wrapping round to a small number.
     */
    private static long total(long limit, long offset) {
        return limit > Long.MAX_VALUE - offset ? Long.MAX_VALUE : limit + offset;
    }
}
