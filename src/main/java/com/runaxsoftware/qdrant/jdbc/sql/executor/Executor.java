package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.embedding.Embedders;
import com.runaxsoftware.qdrant.jdbc.embedding.EmbeddingModel;
import com.runaxsoftware.qdrant.jdbc.embedding.TextRole;
import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.gateway.models.FacetHit;
import com.runaxsoftware.qdrant.jdbc.gateway.models.FacetRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Document;
import com.runaxsoftware.qdrant.jdbc.gateway.models.MatrixPair;
import com.runaxsoftware.qdrant.jdbc.gateway.models.MatrixRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PointOperation;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Point;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollPage;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantAlias;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QueryRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScoredPoint;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorField;
import com.runaxsoftware.qdrant.jdbc.resultset.ListResultSet;
import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.sql.planner.Planner;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.AddVectorPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ClearIssuesPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ConstantPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CountPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateIndexPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateViewPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DeletePointsPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropIndexPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropVectorPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropViewPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.EmbedText;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.FacetColumn;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.FacetPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.GroupedQueryPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.InsertPointsPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.MatrixPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.NewPoint;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.PointPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ProcedurePlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.QueryPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.RenameViewPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ScrollPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.SystemTablePlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.UpdateCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.UpdatePointsPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.UpdateQuotasPlan;
import com.runaxsoftware.qdrant.jdbc.system.QdrantSystemTables;
import com.runaxsoftware.qdrant.jdbc.system.SystemTableReader;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
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
    private static final String DUPLICATE_COLUMN = "42S21";
    private static final String UNKNOWN_COLUMN = "42S22";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    // The form field Qdrant reads an uploaded snapshot from
    private static final String SNAPSHOT_FIELD = "snapshot";
    private static final String FILE_ERROR = "58030";

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
        this(scroller(gateway), searcher(gateway), gateway::count, gateway::facet,
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

        if (plan instanceof PointPlan point) {
            return ExecutionResult.rows(new ListResultSet(
                PointValues.resultColumns(point.columns(), point.collection()),
                pointRows(point, limit(point.limit(), maxRows)), statement));
        }

        if (plan instanceof QueryPlan query) {
            Bounded bounded = searchLimit(query, maxRows);
            long limit = bounded.limit();

            return ExecutionResult.rows(new ListResultSet(
                PointValues.resultColumns(query.columns(), query.collection()),
                limit == 0 ? List.of() : searchRows(query, limit), statement),
                warning(bounded, "A similarity search"));
        }

        if (plan instanceof GroupedQueryPlan grouped) {
            Bounded bounded = searchLimit(grouped.search(), maxRows);
            long limit = bounded.limit();

            return ExecutionResult.rows(new ListResultSet(
                PointValues.resultColumns(grouped.search().columns(),
                    grouped.search().collection()),
                limit == 0 ? List.of() : groupedRows(grouped, limit), statement),
                warning(bounded, "A similarity search"));
        }

        if (plan instanceof MatrixPlan matrix) {
            return ExecutionResult.rows(new ListResultSet(matrixColumns(matrix),
                matrixRows(matrix, limit(matrix.limit(), maxRows)), statement));
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

        if (plan instanceof UpdateCollectionPlan update) {
            writer.updateCollection(update.collection(), update.changes());
            return ExecutionResult.updated(0);
        }

        if (plan instanceof ProcedurePlan call) {
            call(call);
            return ExecutionResult.updated(0);
        }

        if (plan instanceof UpdateQuotasPlan quotas) {
            updateQuotas(quotas);
            return ExecutionResult.updated(0);
        }

        if (plan instanceof AddVectorPlan add) {
            addVector(add);
            return ExecutionResult.updated(0);
        }

        if (plan instanceof DropVectorPlan drop) {
            dropVector(drop);
            return ExecutionResult.updated(0);
        }

        // Qdrant answers only that it cleared them, not how many there were
        if (plan instanceof ClearIssuesPlan) {
            writer.clearIssues();
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
     * Runs several searches of one collection as one batch, and returns each one's rows in the
     * order given, as running them one by one would. Each keeps its own limit, offset, filter
     * and columns; a search bounded to no rows isn't sent.
     *
     * @param plans the searches, all of the same collection
     */
    public List<ExecutionResult> executeSearches(List<QueryPlan> plans, Statement statement)
        throws SQLException {
        long maxRows = statement.getLargeMaxRows();
        var bounds = new ArrayList<Bounded>(plans.size());
        var requests = new ArrayList<QueryRequest>(plans.size());

        for (QueryPlan plan : plans) {
            Bounded bounded = searchLimit(plan, maxRows);
            bounds.add(bounded);

            if (bounded.limit() > 0) requests.add(request(plan, bounded.limit()));
        }

        Iterator<List<ScoredPoint>> answers = searchBatch(plans.get(0).collection(), requests)
            .iterator();
        var results = new ArrayList<ExecutionResult>(plans.size());

        for (int i = 0; i < plans.size(); i++) {
            QueryPlan plan = plans.get(i);
            List<Object[]> rows = bounds.get(i).limit() == 0 ? List.of()
                : rows(plan, answers.next());

            results.add(ExecutionResult.rows(new ListResultSet(
                PointValues.resultColumns(plan.columns(), plan.collection()), rows, statement),
                warning(bounds.get(i), "A similarity search")));
        }

        return results;
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

        Map<EmbedText, List<Double>> embedded = embedAll(plan.points(), plan.collection());
        var points = new ArrayList<JsonNode>(plan.points().size());
        for (NewPoint point : plan.points()) points.add(point(point, embedded));

        writer.insertPoints(plan.collection(), points, plan.onConflict().replaces(),
            plan.updateFilter());

        return plan.points().stream().map(x -> x.id().asText()).toList();
    }

    /**
     * Every text the statement's points need embedded, embedded in one call per model: the
     * providers take a batch, so a thousand rows that embed a column are one request rather than
     * a thousand. Rows that give the same text for the same model share one vector, since the
     * answer is the same and a provider charges for each.
     *
     * @return the vector for each distinct text-and-model the points name
     */
    private Map<EmbedText, List<Double>> embedAll(List<NewPoint> points, String collection)
        throws SQLException {
        // Grouped by model, since one call carries one model; LinkedHashMap keeps the texts in
        // the order the statement gave them, which is the order the answers come back in
        Map<EmbeddingModel, List<EmbedText>> byModel = new LinkedHashMap<>();

        for (NewPoint point : points) {
            for (EmbedText text : point.embeds().values()) {
                List<EmbedText> texts = byModel.computeIfAbsent(text.model(),
                    model -> new ArrayList<>());
                if (!texts.contains(text)) texts.add(text);
            }
        }

        Map<EmbedText, List<Double>> embedded = new LinkedHashMap<>();

        for (Map.Entry<EmbeddingModel, List<EmbedText>> entry : byModel.entrySet()) {
            List<EmbedText> texts = entry.getValue();
            List<List<Double>> vectors = embedders.embedder(entry.getKey().provider())
                .embed(entry.getKey(), TextRole.DOCUMENT,
                    texts.stream().map(EmbedText::text).toList());

            if (vectors.size() != texts.size()) {
                throw new SQLException(entry.getKey() + " made " + vectors.size()
                    + " vectors for " + texts.size() + " texts", "HY000");
            }

            for (int i = 0; i < texts.size(); i++) {
                EmbedText text = texts.get(i);
                List<Double> vector = vectors.get(i);

                if (vector.size() != text.dimensions()) {
                    throw new SQLException(text.model() + " made a vector of " + vector.size()
                        + " dimensions, and the vector it goes into in " + collection + " has "
                        + text.dimensions(), "42000");
                }

                embedded.put(text, vector);
            }
        }

        return embedded;
    }

    /**
     * A point as Qdrant takes it, with the vectors the driver embedded already made: the unnamed
     * vector on its own, named ones under their names.
     */
    private JsonNode point(NewPoint point, Map<EmbedText, List<Double>> embedded) {
        Map<String, JsonNode> vectors = new LinkedHashMap<>(point.vectors());

        for (Map.Entry<String, EmbedText> embed : point.embeds().entrySet()) {
            ArrayNode values = JSON.arrayNode();
            embedded.get(embed.getValue()).forEach(values::add);
            vectors.put(embed.getKey(), values);
        }

        ObjectNode json = JSON.objectNode();
        json.set("id", point.id());
        json.set("payload", point.payload());

        json.set("vector", vectorValue(vectors));

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

            point.set("vector", vectorValue(vectors));

            points.add(point);
        }

        ObjectNode change = JSON.objectNode();
        change.set("points", points);
        change.set("update_filter", plan.filter());

        return JSON.objectNode().set(PointOperation.UPDATE_VECTORS.operationName(), change);
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

        return JSON.objectNode().set(PointOperation.DELETE_VECTORS.operationName(), change);
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
            throw noInference(e, List.of(request));
        }
    }

    private List<List<ScoredPoint>> searchBatch(String collection, List<QueryRequest> requests)
        throws SQLException {
        try {
            return searcher.searchBatch(collection, requests);
        } catch (SQLException e) {
            throw noInference(e, requests);
        }
    }

    // The error itself, unless it is Qdrant saying it can't embed a search's text: then one
    // that says what to do about it
    private static SQLException noInference(SQLException e, List<QueryRequest> requests) {
        Document document = requests.stream().map(QueryRequest::document)
            .filter(Objects::nonNull).findFirst().orElse(null);

        if (document == null || !String.valueOf(e.getMessage()).contains(NO_INFERENCE)) {
            return e;
        }

        return new SQLException("This Qdrant can't embed text with 'qdrant:" + document.model()
            + "': it runs no inference service, which hosted models need (Qdrant Cloud has "
            + "one). Embed with a model the driver calls, as openai:text-embedding-3-small, or "
            + "with qdrant:qdrant/bm25 for a sparse vector", e.getSQLState(), e);
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

    /**
     * Runs a procedure's one request. A file it names is looked at first, so a path that can't
     * work fails before Qdrant is asked to do anything: taking a snapshot to stream is work,
     * and so is receiving one.
     */
    private void call(ProcedurePlan call) throws SQLException {
        Path file = call.file();

        switch (call.procedure().transfer()) {
            case NONE -> writer.call(call.method(), call.path(), call.body());
            case DOWNLOAD -> {
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new SQLException("Can't save to " + file + ": the file is already "
                        + "there, and the driver doesn't overwrite one", FILE_ERROR);
                }
                if (file.getParent() == null || !Files.isDirectory(file.getParent())) {
                    throw new SQLException("Can't save to " + file + ": its folder doesn't exist",
                        FILE_ERROR);
                }

                writer.download(call.path(), file);
            }
            case UPLOAD -> {
                if (!Files.isRegularFile(file)) {
                    throw new SQLException("Can't read " + file + ": there is no such file",
                        FILE_ERROR);
                }

                writer.upload(call.path(), file);
            }
        }
    }

    /**
     * Changes the quotas the plan names and keeps the rest. Qdrant takes a whole config and
     * replaces the one it has, so the driver reads it first, exact unless something else sets
     * quotas between the two calls. A null takes a limit away, which is leaving it out.
     */
    private void updateQuotas(UpdateQuotasPlan plan) throws SQLException {
        ObjectNode config = JSON.objectNode();
        writer.quotas().properties().forEach(entry -> config.set(entry.getKey(), entry.getValue()));

        for (Map.Entry<String, JsonNode> change : plan.changes().properties()) {
            if (change.getValue().isNull()) config.remove(change.getKey());
            else config.set(change.getKey(), change.getValue());
        }

        writer.setQuotas(config);
    }

    /**
     * A point's vectors as Qdrant takes them: the unnamed one alone is the vector itself, and
     * any others go under their names. A collection that was given a named vector after it was
     * created has both kinds, and then the unnamed one goes under the name Qdrant keeps it by,
     * the empty one.
     */
    private static JsonNode vectorValue(Map<String, JsonNode> vectors) {
        if (vectors.size() == 1 && vectors.containsKey("")) return vectors.get("");

        ObjectNode named = JSON.objectNode();
        vectors.forEach(named::set);

        return named;
    }

    /**
     * Adds the vector unless the collection has one under the name. Qdrant answers a vector
     * that's there with the same settings as a success and one with others as a bad request, so
     * the driver looks first and says the same thing about both: the name is taken.
     */
    private void addVector(AddVectorPlan plan) throws SQLException {
        if (hasVector(plan.collection(), plan.vector())) {
            if (plan.ifNotExists()) return;

            throw new SQLException(plan.collection() + " already has a vector '" + plan.vector()
                + "': a vector's dimensions and distance are fixed, so to change them drop the "
                + "column and add it again, which deletes its values", DUPLICATE_COLUMN);
        }

        writer.createVector(plan.collection(), plan.vector(), plan.config());
    }

    /**
     * Deletes the vector if the collection has it. Qdrant answers a vector that isn't there as
     * a success, so the driver looks first, and reports a missing one.
     */
    private void dropVector(DropVectorPlan plan) throws SQLException {
        List<VectorField> vectors = writer.vectors(plan.collection());

        if (vectors.stream().noneMatch(vector -> vector.name().equals(plan.vector()))) {
            if (plan.ifExists()) return;

            List<String> names = vectors.stream().filter(vector -> !vector.isUnnamed())
                .map(VectorField::name).sorted().toList();
            throw new SQLException(plan.collection() + " has no vector '" + plan.vector()
                + "': its named vectors are " + (names.isEmpty() ? "none" : String.join(", ", names))
                + ". ALTER TABLE drops vectors only: a payload field has no column to drop, so "
                + "DROP INDEX removes its index and UPDATE ... SET " + plan.vector()
                + " = NULL the field itself", UNKNOWN_COLUMN);
        }

        writer.deleteVector(plan.collection(), plan.vector());
    }

    private boolean hasVector(String collection, String name) throws SQLException {
        return writer.vectors(collection).stream().anyMatch(vector -> vector.name().equals(name));
    }

    private static QdrantAlias alias(List<QdrantAlias> aliases, String name) {
        return aliases.stream().filter(x -> x.name().equals(name)).findFirst().orElse(null);
    }

    // Qdrant reads one point by its ID in a call of its own, which a scroller of pages can't say
    private static Scroller scroller(QdrantGateway gateway) {
        return new Scroller() {
            @Override
            public ScrollPage scroll(ScrollRequest request) throws SQLException {
                return gateway.scroll(request);
            }

            @Override
            public Point point(String collection, JsonNode id) throws SQLException {
                return gateway.point(collection, id);
            }
        };
    }

    // Qdrant runs a batch of searches in one call, which a searcher made of one method can't say
    private static Searcher searcher(QdrantGateway gateway) {
        return new Searcher() {
            @Override
            public List<ScoredPoint> search(QueryRequest query) throws SQLException {
                return gateway.query(query);
            }

            @Override
            public List<MatrixPair> matrix(MatrixRequest matrix) throws SQLException {
                return gateway.matrix(matrix);
            }

            @Override
            public List<ScoredPoint> searchGroups(QueryRequest query, String groupBy,
                                                  long groupSize) throws SQLException {
                return gateway.queryGroups(query, groupBy, groupSize);
            }

            @Override
            public List<List<ScoredPoint>> searchBatch(String collection,
                                                       List<QueryRequest> queries)
                throws SQLException {
                return gateway.queryBatch(collection, queries);
            }
        };
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

            @Override
            public void clearIssues() throws SQLException {
                gateway.clearIssues();
            }

            @Override
            public void call(HttpMethod method, String path, JsonNode body) throws SQLException {
                gateway.run(method, path, body);
            }

            @Override
            public long download(String path, Path file) throws SQLException {
                return gateway.download(path, file);
            }

            @Override
            public void upload(String path, Path file) throws SQLException {
                gateway.upload(path, SNAPSHOT_FIELD, file);
            }

            @Override
            public JsonNode quotas() throws SQLException {
                return gateway.quotas().path("config");
            }

            @Override
            public void setQuotas(JsonNode config) throws SQLException {
                gateway.setQuotas(config);
            }

            @Override
            public void updateCollection(String collection, JsonNode changes)
                throws SQLException {
                gateway.updateCollection(collection, changes);
            }

            @Override
            public List<VectorField> vectors(String collection) throws SQLException {
                return gateway.collection(collection).vectors();
            }

            @Override
            public void createVector(String collection, String vector, JsonNode config)
                throws SQLException {
                gateway.createVector(collection, vector, config);
            }

            @Override
            public void deleteVector(String collection, String vector) throws SQLException {
                gateway.deleteVector(collection, vector);
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
        return rows(plan, search(request(plan, limit)));
    }

    /**
     * The rows of a grouped search: the best points overall, with at most the plan's number from
     * any one group. Qdrant counts groups where SQL counts rows, and has no offset for them, so
     * the driver asks for as many groups as there are rows to read, which is always enough since
     * the best rows come from the best groups, then orders the points of those groups by score,
     * passes over the offset, and keeps the limit.
     */
    private List<Object[]> groupedRows(GroupedQueryPlan plan, long limit) throws SQLException {
        QueryPlan search = plan.search();
        long groups = Math.min(limit, Long.MAX_VALUE - search.offset()) + search.offset();
        QueryRequest ranked = request(search, groups);

        // The offset is the driver's to pass over, so the request goes without one
        QueryRequest request = new QueryRequest(ranked.collection(), ranked.vector(),
            ranked.query(), ranked.limit(), 0, ranked.withPayload(), ranked.withVector(),
            ranked.filter(), ranked.document());
        List<ScoredPoint> found;

        try {
            found = new ArrayList<>(searcher.searchGroups(request, plan.groupBy(),
                plan.groupSize()));
        } catch (SQLException e) {
            throw noInference(e, List.of(request));
        }

        // Stable, so points with the same score stay in Qdrant's order
        Comparator<ScoredPoint> byScore = Comparator.comparingDouble(ScoredPoint::score);
        found.sort(plan.lowerIsBest() ? byScore : byScore.reversed());

        return rows(search, found.stream().skip(search.offset()).limit(limit).toList());
    }

    /**
     * The one point's row, or none: when there's no such point, when the statement's limit is
     * no rows, or when an OFFSET passes over the only row there could be. The last two ask
     * Qdrant for nothing.
     */
    private List<Object[]> pointRows(PointPlan plan, Long limit) throws SQLException {
        if ((limit != null && limit <= 0) || plan.offset() > 0) return List.of();

        Point point = scroller.point(plan.collection(), plan.id());
        if (point == null) return List.of();

        Object[] row = new Object[plan.columns().size()];
        for (int i = 0; i < row.length; i++) {
            row[i] = PointValues.of(point, null, plan.columns().get(i));
        }

        return List.<Object[]>of(row);
    }

    private static List<ResultColumn> matrixColumns(MatrixPlan plan) {
        var columns = new ArrayList<ResultColumn>(plan.columns().size());

        for (int i = 0; i < plan.columns().size(); i++) {
            columns.add(ResultColumn.of(plan.names().get(i), plan.columns().get(i).sqlType()));
        }

        return columns;
    }

    /**
     * The matrix's rows, in Qdrant's order: each sampled point's neighbours together, the
     * closest first. Qdrant answers the whole matrix at once, so the OFFSET and the limit are
     * taken here, and a limit of no rows asks for nothing.
     */
    private List<Object[]> matrixRows(MatrixPlan plan, Long limit) throws SQLException {
        if (limit != null && limit <= 0) return List.of();

        var rows = new ArrayList<Object[]>();

        for (MatrixPair pair : searcher.matrix(plan.request()).stream().skip(plan.offset())
            .limit(limit == null ? Long.MAX_VALUE : limit).toList()) {
            Object[] row = new Object[plan.columns().size()];

            for (int i = 0; i < row.length; i++) {
                row[i] = switch (plan.columns().get(i)) {
                    case A -> pair.a().asText();
                    case B -> pair.b().asText();
                    case SCORE -> pair.score();
                    case ROW -> pair.row();
                    case COL -> pair.col();
                };
            }

            rows.add(row);
        }

        return rows;
    }

    // The points a search found as rows of the columns it selects
    private static List<Object[]> rows(QueryPlan plan, List<ScoredPoint> found) {
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
