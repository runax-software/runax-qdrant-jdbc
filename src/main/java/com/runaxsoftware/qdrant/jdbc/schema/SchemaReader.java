package com.runaxsoftware.qdrant.jdbc.schema;

import com.runaxsoftware.qdrant.jdbc.client.CollectionInfo;
import com.runaxsoftware.qdrant.jdbc.client.Point;
import com.runaxsoftware.qdrant.jdbc.client.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.client.ScrollRequest;

import java.sql.SQLException;
import java.util.List;

/**
 * Reads a collection's {@link TableSchema} from Qdrant: its configuration plus a sample of
 * points. Every call asks Qdrant again, so the columns follow the collection as it changes.
 */
public final class SchemaReader {

    private final QdrantGateway gateway;
    private final int sampleSize;

    /**
     * @param gateway    the connection to Qdrant
     * @param sampleSize how many points to sample for payload fields; 0 uses payload indexes only
     */
    public SchemaReader(QdrantGateway gateway, int sampleSize) {
        this.gateway = gateway;
        this.sampleSize = sampleSize;
    }

    /**
     * Reads the schema of one collection.
     */
    public TableSchema read(String collection) throws SQLException {
        CollectionInfo info = gateway.collectionInfo(collection);
        List<Point> sample = sampleSize == 0
            ? List.of()
            : gateway.scroll(new ScrollRequest(collection, sampleSize, null, true, false))
            .points();

        return SchemaInference.infer(info, sample);
    }
}
