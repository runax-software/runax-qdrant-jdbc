package com.runaxsoftware.qdrant.jdbc.schema;

import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Point;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollRequest;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

/**
 * Reads a collection's {@link TableSchema} from Qdrant: its configuration plus a sample of
 * points. A connection has one reader, so what it keeps serves everything that uses the
 * connection, from any thread.
 *
 * <p>Reading a schema takes two calls to Qdrant, and tools ask for the same collection several
 * times in a row (DataGrip reads its columns, then its indexes), so the reader keeps each
 * schema for the connection's {@code schemaCache} setting, like other drivers that work out
 * columns from a sample. A schema is kept from when its read started; a read that fails, such
 * as for a collection that no longer exists, isn't kept. {@link #forget} drops schemas sooner:
 * {@link com.runaxsoftware.qdrant.jdbc.metadata.QdrantDatabaseMetaData#getTables} forgets the
 * collections it lists, because tools list the tables first when they refresh, so a refresh
 * reads each collection once and always shows its current columns.
 */
public final class SchemaReader {

    private final QdrantGateway gateway;
    private final int sampleSize;
    private final SchemaCache cache;

    /**
     * @param gateway    the connection to Qdrant
     * @param sampleSize how many points to sample for payload fields; 0 uses payload indexes only
     * @param expiry     how long to keep a schema after reading it; zero reads it on every call
     */
    public SchemaReader(QdrantGateway gateway, int sampleSize, Duration expiry) {
        this.gateway = gateway;
        this.sampleSize = sampleSize;
        this.cache = new SchemaCache(expiry);
    }

    /**
     * Reads the schema of one collection, or returns the one kept from an earlier read. Fails
     * with SQL state {@code 42S02} if there's no such collection.
     */
    public TableSchema read(String collection) throws SQLException {
        return cache.get(collection, this::readFromQdrant);
    }

    /**
     * Drops the kept schemas of the collections that match, so the next {@link #read} of each
     * asks Qdrant again.
     *
     * @param collections which collection names to forget
     */
    public void forget(Predicate<String> collections) {
        cache.forget(collections);
    }

    private TableSchema readFromQdrant(String collection) throws SQLException {
        QdrantCollection qdrantCollection = gateway.collection(collection);
        List<Point> sample = sampleSize == 0 ? List.of()
            : gateway.scroll(new ScrollRequest(collection, sampleSize, null, true, false))
            .points();

        return SchemaInference.infer(qdrantCollection, sample);
    }
}
