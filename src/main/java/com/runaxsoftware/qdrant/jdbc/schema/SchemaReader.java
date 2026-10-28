package com.runaxsoftware.qdrant.jdbc.schema;

import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantAlias;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Point;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollRequest;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

/**
 * Reads a collection's {@link TableSchema} from Qdrant, its configuration plus a sample of
 * points, and the lists of collections and aliases. A connection has one reader, so what it
 * keeps serves everything that uses the connection, from any thread.
 *
 * <p>Reading a schema takes two calls to Qdrant, and tools ask for the same collection several
 * times in a row (DataGrip reads its columns, then its indexes), so the reader keeps each
 * schema for the connection's {@code schemaCache} setting, like other drivers that work out
 * columns from a sample. A schema is kept from when its read started; a read that fails, such
 * as for a collection that no longer exists, isn't kept. {@link #forget} drops schemas sooner:
 * {@link com.runaxsoftware.qdrant.jdbc.metadata.QdrantDatabaseMetaData#getTables} forgets the
 * names it matches, because tools list the tables first when they refresh, so a refresh reads
 * each collection once and always shows its current columns.
 *
 * <p>The collection and alias lists are kept the same way, so asking for each table's columns,
 * key, and indexes doesn't list them again every time. {@link #listCollections} and
 * {@link #listAliases} always ask Qdrant and keep the answer; {@code getTables} uses them, so a
 * refresh lists them once. {@link #collections} and {@link #aliases} return the kept lists, and
 * ask Qdrant only when none is kept or the kept one has expired. {@link #collectionOf} looks up
 * one name in the kept lists and, like a statement that reads columns again for a column it
 * doesn't know, lists both again once when they have no such name, so a collection or alias
 * created since the last listing is found; only such a miss costs a listing. A
 * {@code schemaCache} of zero keeps neither schemas nor lists.
 *
 * <p>Schemas are kept by the name they were read under. Qdrant answers an alias's name with the
 * collection it points at, so {@link #read} takes an alias too, as SQL that names one needs; that
 * schema is kept under, and named after, the alias, and is read again like any other, such as
 * after a refresh that lists the alias. Database metadata reads a view under its collection's
 * name instead, from the kept alias list, so a collection and its aliases share one read.
 * Keeping a view's columns under the alias would read each collection again for every alias.
 * {@code getTables} therefore forgets the collection behind each view it lists too: refreshing
 * only a view reads its collection again, and after a refresh a view whose alias moved shows
 * its new collection.
 */
public final class SchemaReader {

    private final QdrantGateway gateway;
    private final int sampleSize;
    private final SchemaCache cache;
    private final Snapshot<List<String>> collections;
    private final Snapshot<List<QdrantAlias>> aliases;
    private final boolean keeping;

    /**
     * @param gateway    the connection to Qdrant
     * @param sampleSize how many points to sample for payload fields; 0 uses payload indexes only
     * @param expiry     how long to keep a schema or list after reading it; zero reads it on
     *                   every call
     */
    public SchemaReader(QdrantGateway gateway, int sampleSize, Duration expiry) {
        this.gateway = gateway;
        this.sampleSize = sampleSize;
        this.cache = new SchemaCache(expiry);
        this.collections = new Snapshot<>("the collection list",
            () -> List.copyOf(gateway.listCollections()), expiry);
        this.aliases = new Snapshot<>("the alias list", gateway::listAliases, expiry);
        this.keeping = !expiry.isZero();
    }

    /**
     * Reads the schema of one collection, or of the collection an alias points at, or returns
     * the one kept from an earlier read under that name. Fails with SQL state {@code 42S02} if
     * there's no such collection or alias.
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

    /**
     * The collection names, as kept from the last listing, or listed now if none is kept.
     */
    public List<String> collections() throws SQLException {
        return collections.get();
    }

    /**
     * The aliases, as kept from the last listing, or listed now if none is kept.
     */
    public List<QdrantAlias> aliases() throws SQLException {
        return aliases.get();
    }

    /**
     * Asks Qdrant for the collection names and keeps them for {@link #collections}.
     */
    public List<String> listCollections() throws SQLException {
        return collections.refresh();
    }

    /**
     * Asks Qdrant for the aliases and keeps them for {@link #aliases}.
     */
    public List<QdrantAlias> listAliases() throws SQLException {
        return aliases.refresh();
    }

    /**
     * The collection a table or view name reads: the name itself for a collection, the
     * collection an alias points at for an alias, or {@code null} for neither. Looks in the kept
     * lists, the alias list only when the name isn't a collection. When neither list has the
     * name, as for one created since the last listing, lists both again, once, and keeps them.
     *
     * @param name an exact collection or alias name, or {@code null}, which names nothing
     */
    public String collectionOf(String name) throws SQLException {
        if (name == null) return null;
        if (collections().contains(name)) return name;

        String target = target(name, aliases());
        if (target != null || !keeping) return target;

        // Not kept, but maybe created since the last listing: list both again, once
        List<String> listed = listCollections();
        target = target(name, listAliases());

        return listed.contains(name) ? name : target;
    }

    private static String target(String alias, List<QdrantAlias> aliases) {
        for (QdrantAlias listed : aliases) {
            if (listed.name().equals(alias)) return listed.collection();
        }

        return null;
    }

    private TableSchema readFromQdrant(String collection) throws SQLException {
        QdrantCollection qdrantCollection = gateway.collection(collection);
        List<Point> sample = sampleSize == 0 ? List.of()
            : gateway.scroll(new ScrollRequest(collection, sampleSize, null, true, false))
            .points();

        return SchemaInference.infer(qdrantCollection, sample);
    }
}
