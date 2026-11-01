# Runax JDBC Driver for Qdrant

A JDBC driver that lets SQL tools browse and query [Qdrant](https://qdrant.tech)
collections with SQL, including vector similarity search. JetBrains DataGrip is the
first target; any JDBC client (DBeaver, BI tools) should work the same way.

> **Status: early development.** Nothing is published yet. The driver connects and lists
> collections and their columns in DataGrip's database explorer, but doesn't run SQL yet.
> The querying sections below describe the planned design — see [ROADMAP.md](ROADMAP.md).

- **Collections as tables** — collections show up in the database explorer, and points
  as rows: every payload field is a column under its own name, next to the driver's own
  `_id` (the point ID), `_payload` (the whole payload as JSON), and the vectors.
- **SQL over the Qdrant API** — `WHERE` becomes a Qdrant filter, plus `LIMIT`,
  `COUNT(*)`, `GROUP BY` through facets, and `ORDER BY` on range-indexed fields.
- **Similarity search** — rank points by similarity to a vector, with the score as a
  `_score` column; embedding text on the fly comes next.
- **System tables** — `system.collections` and `system.aliases` describe the server
  itself, so a collection's status, point counts, shards, and aliases are SQL rows rather
  than REST calls.
- **Honest errors** — SQL that Qdrant can't run (joins, arbitrary aggregates) fails with
  a clear error instead of scanning a whole collection on the client.
- **One small jar** — talks to Qdrant's REST API with the JDK's own HTTP client, so the
  only library inside is Jackson, relocated so it can't clash with the tool's own copy.

## Connection

```
jdbc:qdrant://localhost:6333
```

The driver talks to Qdrant's REST API, on port `6333` by default, the same port as the web
dashboard. Qdrant's gRPC port, `6334`, doesn't work; if you point the driver at it, the
error says so. Put the Qdrant API key in the password field, so DataGrip keeps it in its
credential store rather than in the project's data source files.

Other settings go in the URL (`?tls=true&timeout=60`) or in the connection properties
(DataGrip's Advanced tab):

| Setting | Default | Meaning |
| --- | --- | --- |
| `tls` | `false` | Connect over TLS, as Qdrant Cloud needs |
| `timeout` | `30` | Seconds to wait for each call to Qdrant |
| `sampleSize` | `100` | Points read to work out a collection's columns |
| `schemaCache` | `3600` | Seconds to keep a collection's columns, and the lists of collections and aliases, before reading them again; listing tables, as DataGrip's Refresh does, reads them again sooner, asking for a table by a name the kept lists don't have lists them again, and `0` reads them every time |
| `logFile` | — | File to log every JDBC call to, for troubleshooting; the API key is never logged |

## Try it in DataGrip

1. Build the driver jar: `./gradlew shadowJar` writes
   `build/libs/qdrant-jdbc-<version>-all.jar`.
2. In DataGrip, open **Database Explorer → + → Driver**, name it `Qdrant (Runax)`, and
   under **Driver Files** add the jar as a custom JAR.
3. Set **Class** to `com.runaxsoftware.qdrant.jdbc.QdrantDriver` and add the URL template
   `jdbc:qdrant://{host::localhost}[:{port::6333}]`.
4. Create a data source from that driver. Choose **No auth**, or **User & Password** with
   the API key as the password and the user left empty.
5. **Test Connection**, then open the data source: collections show up as tables, with
   their columns, primary key, and payload indexes, and aliases as views with their
   collection's columns.

To try it on sample data, start Qdrant with `docker compose up -d` and load three small
collections with `./gradlew seedQdrant`.

## Querying

Regular SQL against a collection:

```sql
SELECT _id, _payload
FROM docs
WHERE lang = 'en' AND year >= 2024
LIMIT 50
```

Similarity search, which runs today with the vector written out:

```sql
SELECT _id, _score, title
FROM docs
ORDER BY similarity(dense, [0.1, 0.2, 0.3, 0.4]) DESC
LIMIT 10
```

`dense` is the name of the vector to search (`_vector` for a collection's single unnamed
vector), and `_score` is how close Qdrant scored each point. A search says how many points
to rank, with `LIMIT` or the statement's maximum rows.

A search takes a `WHERE` too, which Qdrant applies before ranking. Planned, and described in
the dialect spec: `embed('refund policy')` in place of the vector, which turns text into one
with the embedding model configured for that vector.

```sql
SELECT _id, _score, _payload
FROM docs
WHERE lang = 'en'
ORDER BY similarity(dense, embed('refund policy')) DESC
LIMIT 10
```

The server's own state, in the `system` schema next to the `collections` schema the
collections sit in:

```sql
SELECT name, status, points_count, segments_count
FROM system.collections
```

### How SQL maps to Qdrant

| SQL | Qdrant | Notes |
| --- | --- | --- |
| `WHERE` on payload fields | Filter (`must` / `should` / `must_not`) | Fast with payload indexes |
| `LIMIT` | `limit` | |
| `OFFSET n` | Scroll | Qdrant pages with a cursor, so the driver skips `n` points |
| `ORDER BY field` | Scroll `order_by` | Needs a range index on the field, and a `LIMIT` |
| `COUNT(*)` | Count API | |
| `GROUP BY field` + `COUNT(*)` | Facet API | Needs a keyword index on the field |
| `ORDER BY similarity(v, [0.1, ...]) DESC` | Query API | Score returned as `_score`; needs a `LIMIT` |
| `FROM system.collections`, `system.aliases` | Collection and alias APIs | Read-only system tables |
| `DELETE FROM docs WHERE …` | Delete points by filter | Returns the number deleted; needs a `WHERE` |
| `TRUNCATE TABLE docs` | Delete every point | The collection and its indexes stay |
| `DROP TABLE [IF EXISTS] docs` | Delete the collection | |
| `JOIN`, `SUM`, `AVG`, subqueries | — | Rejected with an error |

The full mapping, covering writes, DDL, aliases, hybrid search, and the rest of Qdrant's API,
is in the [SQL dialect spec](docs/specs/sql-dialect.md).

## Building

```bash
./gradlew build        # compile and run the unit tests
./gradlew shadowJar    # the single driver jar, in build/libs
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for prerequisites, running Qdrant locally, and the
integration tests.

## Related

A DataGrip plugin with a search panel and collection tools is planned as a separate
project, `runax-qdrant-datagrip`, built on this driver.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE).

This project is not affiliated with or endorsed by Qdrant.
