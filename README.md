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
- **Similarity search** — rank points by similarity to a vector or to text embedded on
  the fly, with the score as a `_score` column.
- **Honest errors** — SQL that Qdrant can't run (joins, arbitrary aggregates) fails with
  a clear error instead of scanning a whole collection on the client.
- **Official Qdrant SDK** — talks to Qdrant over gRPC through Qdrant's own Java client; a
  single jar to drop into DataGrip.

## Connection

```
jdbc:qdrant://localhost:6334
```

The driver talks to Qdrant's gRPC API (port `6334` by default) through the official
Qdrant Java SDK. Put the Qdrant API key in the password field, so DataGrip keeps it in its
credential store rather than in the project's data source files.

Other settings go in the URL (`?tls=true&timeout=60`) or in the connection properties
(DataGrip's Advanced tab):

| Setting | Default | Meaning |
| --- | --- | --- |
| `tls` | `false` | Connect over TLS, as Qdrant Cloud needs |
| `timeout` | `30` | Seconds to wait for each call to Qdrant |
| `sampleSize` | `100` | Points read to work out a collection's columns |
| `logFile` | — | File to log every JDBC call to, for troubleshooting; the API key is never logged |

## Try it in DataGrip

1. Build the driver jar: `./gradlew shadowJar` writes
   `build/libs/qdrant-jdbc-<version>-all.jar`.
2. In DataGrip, open **Database Explorer → + → Driver**, name it `Qdrant (Runax)`, and
   under **Driver Files** add the jar as a custom JAR.
3. Set **Class** to `com.runaxsoftware.qdrant.jdbc.QdrantDriver` and add the URL template
   `jdbc:qdrant://{host::localhost}[:{port::6334}]`.
4. Create a data source from that driver. Choose **No auth**, or **User & Password** with
   the API key as the password and the user left empty.
5. **Test Connection**, then open the data source: collections show up as tables, with
   their columns, primary key, and payload indexes.

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

Similarity search, combined with a filter:

```sql
SELECT _id, _score, _payload
FROM docs
WHERE lang = 'en'
ORDER BY similarity(dense, embed('refund policy')) DESC
LIMIT 10
```

`dense` is the name of the vector to search, and `embed(...)` turns text into a vector
with the embedding model configured for that vector. A vector literal works in its place.

### How SQL maps to Qdrant

| SQL | Qdrant | Notes |
| --- | --- | --- |
| `WHERE` on payload fields | Filter (`must` / `should` / `must_not`) | Fast with payload indexes |
| `LIMIT` | `limit` | |
| `OFFSET n` | Scroll | Qdrant pages with a cursor, so the driver skips `n` points |
| `ORDER BY field` | Scroll `order_by` | Needs a range index on the field |
| `COUNT(*)` | Count API | |
| `GROUP BY field` + `COUNT(*)` | Facet API | Needs a keyword index on the field |
| `ORDER BY similarity(...)` | Query API | Score returned as `_score` |
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
