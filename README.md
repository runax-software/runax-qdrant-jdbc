# Runax JDBC Driver for Qdrant

[![CI](https://github.com/runax-software/runax-qdrant-jdbc/actions/workflows/ci.yml/badge.svg)](https://github.com/runax-software/runax-qdrant-jdbc/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A JDBC driver that lets SQL tools browse and query [Qdrant](https://qdrant.tech)
collections with SQL, including vector similarity search. JetBrains DataGrip is the
first target; any JDBC client (DBeaver, BI tools) should work the same way.

```sql
SELECT _id, _score, title
FROM docs
WHERE lang = 'en'
ORDER BY similarity(dense, embed('refund policy', 'openai:text-embedding-3-small')) DESC
LIMIT 10
```

> **Status: early development.** Nothing is published to Maven Central yet: download the jar
> from the [releases](https://github.com/runax-software/runax-qdrant-jdbc/releases), or build
> it from source (`./gradlew shadowJar`). It does run SQL: `SELECT` with filters, patterns
> and full-text matching, vector similarity search, `COUNT(*)` and `GROUP BY`, `INSERT`,
> `UPDATE`, `DELETE`, and DDL for collections, indexes and views — fifteen statement kinds.
> Exactly how much of Qdrant and how much SQL that amounts to is measured in
> [docs/coverage.md](docs/coverage.md), the target dialect is in
> [docs/specs/sql-dialect.md](docs/specs/sql-dialect.md), and what's next is in
> [ROADMAP.md](ROADMAP.md).

- **Collections as tables** — collections show up in the database explorer, and points
  as rows: every payload field is a column under its own name, next to the driver's own
  `_id` (the point ID), `_payload` (the whole payload as JSON), and the vectors.
- **SQL over the Qdrant API** — `WHERE` becomes a Qdrant filter, plus `LIMIT`, `OFFSET`,
  `COUNT(*)`, `GROUP BY` through facets, and `ORDER BY` on range-indexed fields.
- **Similarity search** — rank points by similarity to a vector, with the score as a
  `_score` column, and `embed('text')` to search with text, embedded by Qdrant or by a
  provider such as OpenAI.
- **System tables** — twelve tables in a `system` schema describe the server itself
  (`collections`, `aliases`, `snapshots`, `shards`, `shard_keys`, `cluster`, `peers`,
  `telemetry`, `metrics`, `issues`, `health`, `optimizations`), so a collection's status, point counts, shards,
  and aliases are SQL rows rather than REST calls. They are read-only, but for
  `TRUNCATE TABLE system.issues`.
- **Honest errors** — SQL that Qdrant can't run (joins, arbitrary aggregates) fails with
  a clear error instead of scanning a whole collection on the client.
- **One small jar** — talks to Qdrant's REST API with the JDK's own HTTP client, so the
  only library inside is Jackson, relocated so it can't clash with the tool's own copy.

## Requirements

- **Java 17** or newer, in the tool that loads the driver (DataGrip ships its own).
- **Qdrant 1.20** over its REST API, self-hosted or Qdrant Cloud. The integration tests run
  against 1.20.0; older versions may lack routes the driver calls, as
  [docs/coverage.md](docs/coverage.md) notes.
- For `embed()` with a driver-side provider, an OpenAI or Voyage AI API key.

## Install

Download `qdrant-jdbc-<version>-all.jar` from the
[latest release](https://github.com/runax-software/runax-qdrant-jdbc/releases/latest), or build
it yourself:

```bash
git clone https://github.com/runax-software/runax-qdrant-jdbc.git
cd runax-qdrant-jdbc
./gradlew shadowJar    # writes build/libs/qdrant-jdbc-<version>-all.jar
```

It's one self-contained jar: add it to your tool as a custom driver, as below. The driver class
is `com.runaxsoftware.qdrant.jdbc.QdrantDriver`, and it registers itself, so plain JDBC code
only needs the jar on its classpath:

```java
try (Connection connection = DriverManager.getConnection("jdbc:qdrant://localhost:6333");
     ResultSet rows = connection.createStatement().executeQuery(
         "SELECT name, status, points_count FROM system.collections")) {
    while (rows.next()) System.out.println(rows.getString("name"));
}
```

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
| `defaultLimit` | `1000` | Rows a read returns when the statement gives no `LIMIT` and sets no maximum rows, for the reads Qdrant answers in one call and pages no further: an ordered read, a similarity search, and a `GROUP BY`. The statement warns when this bounds it; `0` makes such a statement an error instead |
| `schemaCache` | `3600` | Seconds to keep a collection's columns, and the lists of collections and aliases, before reading them again; listing tables, as DataGrip's Refresh does, reads them again sooner, asking for a table by a name the kept lists don't have lists them again, and `0` reads them every time |
| `logFile` | — | File to log every JDBC call to, for troubleshooting; API keys are never logged |
| `openaiApiKey` | — | OpenAI API key, for `embed('text', 'openai:…')`; never shown or logged |
| `openaiUrl` | `https://api.openai.com/v1` | OpenAI's API URL, or one that answers like it, such as an Azure OpenAI deployment or a proxy |
| `voyageApiKey` | — | Voyage AI API key, for `embed('text', 'voyage:…')`; never shown or logged |
| `voyageUrl` | `https://api.voyageai.com/v1` | Voyage AI's API URL |

## Try it in DataGrip

1. Get the driver jar, as under [Install](#install). Add the `-all` jar only: the plain one
   next to it in `build/libs` has no Jackson inside, and DataGrip fails to load it.
2. In DataGrip, open **Database Explorer → + → Driver**, name it `Qdrant (Runax)`, and
   under **Driver Files** add the jar as a custom JAR.
3. Set **Class** to `com.runaxsoftware.qdrant.jdbc.QdrantDriver` and add the URL template
   `jdbc:qdrant://{host::localhost}[:{port::6333}]`.
4. Create a data source from that driver. Choose **No auth**, or **User & Password** with
   the API key as the password and the user left empty.
5. **Test Connection**, then open the data source: collections show up as tables, with
   their columns, primary key, and payload indexes, and aliases as views with their
   collection's columns.
6. To edit data in the grid, untick **Read-only** in the data source: the driver honours it,
   and refuses every write on a read-only connection.

After rebuilding the jar, remove it from the driver and add it again: DataGrip keeps the jar it
loaded open, and a file rebuilt under it fails with `NoClassDefFoundError`.

To try it on sample data, start Qdrant with `docker compose up -d` and load three small
collections with `./gradlew seedQdrant`.

## Querying

Regular SQL against a collection:

```sql
SELECT _id, _payload
FROM docs
WHERE lang = 'en' AND year >= 2024
LIMIT 50 OFFSET 50
```

`OFFSET` pages past the rows before it, on a plain read, an ordered one, a search, a `GROUP BY`,
or a system table. Qdrant applies it itself only for a search; the other reads skip in the
driver, so they're capped at 10,000 rows and a deeper offset says so instead of reading a whole
collection to return one screen.

Similarity search with a vector written out:

```sql
SELECT _id, _score, title
FROM docs
ORDER BY similarity(dense, [0.1, 0.2, 0.3, 0.4]) DESC
LIMIT 10
```

`dense` is the name of the vector to search (`_vector` for a collection's single unnamed
vector), and `_score` is how close Qdrant scored each point. A search says how many points
to rank, with `LIMIT` or the statement's maximum rows.

A search takes a `WHERE` too, which Qdrant applies before ranking. To search with text,
`embed('text', 'provider:model')` turns it into the vector first:

```sql
SELECT _id, _score, title
FROM docs
WHERE lang = 'en'
ORDER BY similarity(dense, embed('refund policy', 'openai:text-embedding-3-small')) DESC
LIMIT 10
```

| Model | Who embeds | Needs |
| --- | --- | --- |
| `qdrant:qdrant/bm25` | Qdrant, into a sparse vector | Nothing: every Qdrant runs it |
| `qdrant:<hosted model>` | Qdrant | Qdrant Cloud with inference |
| `openai:<model>` | The driver, through OpenAI's API | `openaiApiKey` (and `openaiUrl` for Azure or a proxy) |
| `voyage:<model>` | The driver, through Voyage AI's API | `voyageApiKey` |

A model that can return fewer dimensions than it was trained for takes the size to return, as
`embed('refund policy', 'openai:text-embedding-3-large?dimensions=1024')`: the provider shortens
the vector, which keeps more of its meaning than cutting one short afterwards. The size has to
match the vector it goes into, and a mismatch is an error before the provider is called.

Whether the text is a search query or a document being stored travels with it, because some
models embed the two differently: `embed()` in a ranking function is a query, and in an `INSERT`
or `UPDATE` a document.

A vector is searched well only with the model its points were embedded with. Keep it in the
collection's metadata as `runax.embed.<vector>`, and `embed('refund policy')` needs no model;
naming a different one is then an error rather than a search that quietly finds nonsense:

```bash
curl -X PATCH localhost:6333/collections/docs -H 'Content-Type: application/json' \
  -d '{"metadata": {"runax.embed.dense": "openai:text-embedding-3-small"}}'
```

The server's own state, in the `system` schema next to the `collections` schema the
collections sit in:

```sql
SELECT name, status, points_count, segments_count
FROM system.collections
```

Prepared statements take values with `?`, so values never go into the SQL text:

```java
try (PreparedStatement statement = connection.prepareStatement(
        "SELECT _id, title FROM docs WHERE lang = ? AND year >= ? "
        + "ORDER BY similarity(dense, ?) DESC LIMIT 10")) {
    statement.setString(1, "en");
    statement.setInt(2, 2024);
    statement.setObject(3, new float[] {0.1f, 0.2f, 0.3f, 0.4f});
    ResultSet rows = statement.executeQuery();
}
```

Options for the rest of the connection, sent with each request they apply to:

```sql
SET qdrant.consistency = majority;   -- reads wait for most replicas
SET qdrant.hnsw_ef = 256;            -- searches look at more candidates
SHOW ALL;
RESET ALL;
```

### How SQL maps to Qdrant

| SQL | Qdrant | Notes |
| --- | --- | --- |
| `WHERE` on payload fields | Filter (`must` / `should` / `must_not`) | Fast with payload indexes |
| `LIMIT` | `limit` | |
| `OFFSET n` | Query API `offset` for a search; skipped by the driver otherwise | A search's offset is Qdrant's own; a scroll pages by point ID and a facet has no offset, so those skip `n` rows in the driver, up to 10,000 |
| `ORDER BY field` | Scroll `order_by` | Needs a range index on the field; bounded by `LIMIT`, maximum rows, or `defaultLimit` |
| `COUNT(*)` | Count API | |
| `GROUP BY field` + `COUNT(*)` | Facet API | Needs a keyword, integer, bool, or uuid index; bounded like an ordered read |
| `ORDER BY similarity(v, [0.1, ...]) DESC` | Query API | Score returned as `_score`; bounded like an ordered read |
| `FROM system.collections`, `system.aliases` | Collection and alias APIs | Read-only system tables |
| `TRUNCATE TABLE system.issues` | Clear issues | The one write a system table takes |
| `INSERT INTO docs (_id, lang, dense) VALUES (…)` | Upsert that skips points already there | A taken ID is an error; no `_id` gets a UUID, from `getGeneratedKeys` |
| `UPDATE docs SET lang = 'de' WHERE _id = 7` | Set or delete payload fields, in one batch | What DataGrip's data editor sends; needs a `WHERE` |
| `DELETE FROM docs WHERE …` | Delete points by filter | Returns the number deleted; needs a `WHERE` |
| `TRUNCATE TABLE docs` | Delete every point | The collection and its indexes stay |
| `DROP TABLE [IF EXISTS] docs` | Delete the collection | |
| `CREATE INDEX ON docs (lang)`, `DROP INDEX lang_idx ON docs` | Create or delete a payload index | `USING text WITH (tokenizer = word, …)` for a type and its options |
| `CREATE [OR REPLACE] VIEW current AS SELECT * FROM docs` | Create or switch an alias | Also `ALTER VIEW … RENAME TO`, `DROP VIEW` |
| `CREATE TABLE docs (dense VECTOR(384) DISTANCE COSINE, lang KEYWORD INDEX)` | Create the collection, then its payload indexes | `_vector VECTOR(n)` makes the unnamed vector |
| `JOIN`, `SUM`, `AVG`, subqueries | — | Rejected with an error |

The full mapping, covering writes, DDL, aliases, hybrid search, and the rest of Qdrant's API,
is in the [SQL dialect spec](docs/specs/sql-dialect.md). What of that runs today, measured
against a given driver commit and Qdrant version, is in [docs/coverage.md](docs/coverage.md).

## Limits

The driver runs what Qdrant can run, and says so when it can't, rather than computing it on the
client:

- No `JOIN`, `UNION`, subqueries, or aggregates other than `COUNT(*)`.
- A condition compares one column with constants; `price > cost` or `LOWER(name) = …` is an
  error.
- `ORDER BY` a payload field needs a range index on it (`CREATE INDEX ON docs (year)`), and
  `GROUP BY` a countable one; ordering by text isn't something Qdrant does.
- No transactions: every statement applies on its own, and `setAutoCommit(false)` only adds a
  warning that says so.

[docs/coverage.md](docs/coverage.md) lists every statement and clause, with the error for each
one that doesn't run.

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

Issues and pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). Report security
problems privately, as [SECURITY.md](SECURITY.md) describes, not in a public issue.

## License

[MIT](LICENSE).

This project is not affiliated with or endorsed by Qdrant.
