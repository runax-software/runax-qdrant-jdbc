# Changelog

All notable changes to the Runax JDBC Driver for Qdrant are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `embed('text', 'provider:model')` in a search: `ORDER BY similarity(dense, embed('refund
  policy', 'openai:text-embedding-3-small')) DESC LIMIT 10`. A `qdrant:` model is run by Qdrant
  itself, on any kind of vector: every Qdrant runs `qdrant:qdrant/bm25` into sparse vectors,
  and Qdrant Cloud runs hosted dense models. `openai:` and `voyage:` models are called by the
  driver, with the new `openaiApiKey`, `openaiUrl`, `voyageApiKey` and `voyageUrl` connection
  properties; the keys are never shown or logged. The model a vector was embedded with can be
  kept in the collection's metadata as `runax.embed.<vector>`: `embed('text')` then needs no
  model, and naming another is an error rather than a search against the wrong vectors.
  ([#24])
- Prepared statements: `connection.prepareStatement("SELECT _id FROM docs WHERE lang = ? AND
  year >= ?")`, with `setString`, `setInt`, `setLong`, `setDouble`, `setBoolean`,
  `setBigDecimal`, `setTimestamp`, `setDate`, `setNull`, and `setObject` (a `UUID`, `Instant`,
  `OffsetDateTime`, or a vector as `float[]` or `double[]`). A `?` stands for a constant in
  `WHERE`, the select list, or a search's vector. Values replace placeholders in the parsed
  statement, never in the SQL text. Batches run a `DELETE` once per set of values, and
  `getParameterMetaData()` reports how many placeholders there are. ([#23])
- `GROUP BY` with `COUNT(*)`, through Qdrant's facet API: `SELECT lang, COUNT(*) FROM docs
  GROUP BY lang LIMIT 20` counts the points per value of one payload field, the most common
  first. The field needs an index Qdrant can count the values of (`keyword`, `integer`,
  `bool`, or `uuid`), which the driver checks against the collection's own indexes before
  counting, and a `LIMIT` or the statement's maximum rows, because Qdrant counts the ten most
  common values and returns no cursor when a request gives none. A grouped statement selects
  the grouped field and `COUNT(*)`, in either order and under any names; one without the count
  reads the field's values themselves. An array field counts once per element, so
  `GROUP BY tags` counts the points under each tag, and a group's type follows the index rather
  than the column, so the groups of a JSON column of keywords are text. A `WHERE` condition
  filters the counted points. ([#22])
- `COUNT(*)` with a `WHERE`, which Qdrant's count API takes the same filter for as a read:
  `SELECT COUNT(*) FROM docs WHERE lang = 'en'`. A count of a whole collection still reads
  none of its columns. ([#22])

- `ORDER BY` a payload field, mapped to Qdrant's scroll `order_by`: it needs a range index on
  the field (`integer`, `float`, or `datetime`), which the driver checks against the
  collection's own indexes before reading, and a `LIMIT` or the statement's maximum rows,
  because Qdrant answers an ordered read in one page and returns no cursor to ask for another.
  The fetch size doesn't cut an ordered read down, since there would be no second page to read
  the rest from. `ORDER BY _id` is Qdrant's own order, which a scroll reads in anyway, so it
  pages as usual and needs no limit; `ORDER BY _id DESC` is an error, as Qdrant reads IDs
  upwards. An ordered read takes the same `WHERE` filter as any other. ([#18])

- Catalog `qdrant` and schema `collections`. Every collection is listed under them, and
  the connection reports them as its current catalog and schema, so DataGrip shows the
  collections straight away instead of under `database #1 › schema #1` placeholders with
  the schema unticked. ([#6])
- `schemaCache` setting: seconds each connection keeps a collection's columns, `3600` by
  default, `0` to read them every time. Listing tables, as DataGrip's Refresh does, reads
  the listed collections again. ([#8])
- CI on GitHub Actions for every pull request and push to `main`: `./gradlew build` on
  JDK 17, 21 and 25, then the integration tests against Qdrant in Docker. ([#7])
- System tables: `system.collections` and `system.aliases`, read-only tables that describe
  the server rather than any collection's points, in a second schema, `system`, so their
  names can't clash with a collection's. `SELECT name, status, points_count FROM
  system.collections` reads Qdrant's collection API; `DatabaseMetaData` lists both tables as
  `SYSTEM TABLE`s with their columns, no primary key and no indexes, and without calling
  Qdrant at all. A `getTables` or `getColumns` call that doesn't filter by schema now returns
  them along with the collections. ([#13])
- Eight more system tables, all read-only, in the same `system` schema: `snapshots` (a
  collection's and the whole storage's together, with the whole storage's rows naming no
  collection), `shard_keys`, `shards` (every copy of every shard, with the peer holding it
  and the shard key it holds), `cluster` (always one row, `disabled` and otherwise null on a
  standalone server), `peers`, `telemetry` (one row per value in the telemetry tree, named by
  its path), `metrics` (one row per Prometheus sample, with its labels as JSON), and `issues`.
  `DatabaseMetaData` lists all ten system tables with their columns, still without calling
  Qdrant. `GET /metrics` answers in Prometheus' text format rather than JSON, so the gateway
  reads a text body as well as a JSON one. ([#20])
- Similarity search with a vector written out: `SELECT _id, _score, title FROM docs ORDER BY
  similarity(dense, [0.1, 0.2, 0.3, 0.4]) DESC LIMIT 10` runs one call to Qdrant's Query API
  and returns the points it ranked, closest first, with `_score` as a `DOUBLE` column.
  `_score` is only in a search, and a star doesn't include it. The vector to search is a
  vector column of the collection — its name for a named vector, `_vector` for a collection's
  single unnamed one — and the driver checks its dimensions against the vector in the
  statement before anything runs. A search says how many points to rank, through `LIMIT` or
  the statement's maximum rows, rather than the driver picking a number. ([#15])
- `WHERE` mapped to a Qdrant filter, for reading a collection and for a similarity search:
  `AND`, `OR` and `NOT` become `must`, `should` and `must_not`; `=`, `<>`, `<`, `<=`, `>`,
  `>=`, `IN` and `NOT IN` become `match` and `range` conditions (a date-time field compares by
  time); `IS NULL` becomes `is_empty`; `_id = 7` and `_id IN (...)` become `has_id`; and a
  named vector `IS NOT NULL` becomes `has_vector`. As in SQL, a negated condition is never
  true for a point whose field is missing or null, so `lang <> 'en'` matches only points with
  a `lang`. Conditions Qdrant can't run, such as `price = ?`, `lower(name) = 'x'` or
  `name > 'm'`, fail with an `SQLFeatureNotSupportedException` instead of being checked by the
  driver; a filtered read still pages through the scroll cursor. ([#16])
- `CREATE TABLE [IF NOT EXISTS] docs (...)` creates a collection: `VECTOR(n)`,
  `MULTIVECTOR(n)` and `SPARSE_VECTOR [MODIFIER IDF]` columns become its vectors, with
  `DISTANCE COSINE` (the default), `DOT`, `EUCLID` or `MANHATTAN`; a single `_vector` column
  makes the unnamed vector; and `lang KEYWORD INDEX` (or `INTEGER`, `FLOAT`, `BOOL`,
  `DATETIME`, `TEXT`, `UUID`, `GEO`, and the SQL names `VARCHAR`, `BIGINT`, `DOUBLE`,
  `BOOLEAN`, `TIMESTAMP`) becomes a payload index. If an index fails, the new collection is
  deleted again. ([#21])
- Deleting points and collections. `DELETE FROM docs WHERE …` deletes the points the
  condition matches, with the same filters as `SELECT`, and returns how many it deleted (the
  driver counts them first, as Qdrant doesn't report it). A `DELETE` without `WHERE` is an
  error that points to `TRUNCATE TABLE docs`, which deletes every point and keeps the
  collection and its indexes. `DROP TABLE docs` deletes the collection; `DROP TABLE IF EXISTS`
  doesn't mind a missing one. Writes wait for Qdrant to apply them. ([#19])
- Table aliases and qualified names: `FROM docs t` (with or without `AS`), and `t.*`,
  `t.name`, `t.price AS cost`, `WHERE t.lang = 'en'` and `ORDER BY t.price` under that name
  or under the table's own name, as SQL does — a name given to the table replaces its own.
  A nested payload field also reads without quotes, as `specs.color`, when nothing of that
  name is in FROM; `"specs.color"` keeps working. An alias or table name that the statement
  doesn't read reports SQLState `42S02`. ([#14])
- Schema-qualified table names: `FROM system.collections`, `FROM collections.docs`, and
  `FROM qdrant.system.collections`, the forms tools generate. The driver's own names — the
  catalog, the two schemas, and the system tables and their columns — match whatever the
  case; a collection name is still matched exactly, as Qdrant matches it. An unknown catalog
  or schema reports SQLState `42S02`. ([#13])
- `COUNT(*)`: `SELECT COUNT(*) FROM docs` asks Qdrant's count API how many points the collection
  holds and returns one `BIGINT` row. The count is exact, and counting reads neither the points
  nor the collection's columns, so the statement is one call to Qdrant. The column is called
  `count`, as PostgreSQL names it, or whatever the statement names it (`COUNT(*) AS total`), and
  `LIMIT 0` returns no row and counts nothing. `COUNT` of a column, `COUNT(DISTINCT ...)`, other
  aggregates, `COUNT(*)` beside other select items outside a `GROUP BY`, and counting a
  `system` table say
  what they are instead of scanning the collection in the driver. Counting the points a `WHERE`
  matches isn't supported yet: Qdrant's count API takes the same filter a read does, and the
  count doesn't send one. ([#17])

### Fixed

- A statement ending with `;`, as DataGrip and SQL scripts send it, failed with `Unexpected
  character ';'`. One semicolon at the end is now accepted; a second statement after it is
  still an error that says to run one statement at a time. ([#SEMI])
- Opening a collection in DataGrip's data editor failed with `Expected the end of the
  statement, found '.'`: it sends `SELECT t.* FROM collections.<name> t`, which the driver
  couldn't parse. Before the schema-qualified names landed the same statement failed in the
  lexer, as `Unexpected character '.'`. ([#14])

### Changed

- `Connection.isReadOnly()` and `DatabaseMetaData.isReadOnly()` now return `false`, as the
  driver can write. `setReadOnly(true)` is kept, and a read-only connection refuses
  `DELETE`, `TRUNCATE` and `DROP TABLE` with SQLState `25006`, so a tool's read-only
  connection setting protects the data. ([#19])

- **Breaking:** the driver talks to Qdrant's REST API with the JDK's HTTP client instead
  of the Qdrant Java SDK over gRPC, so the default port is now `6333` instead of `6334`.
  URLs that name `:6334` must change to the REST port; connecting to the gRPC port fails
  with an error that says so. ([#5])
- The shaded driver jar is 2.6 MB instead of 29.1 MB: it holds only the driver and a
  relocated Jackson. ([#5])
- The code is split into packages named after the JDBC objects: `connection`, `gateway`,
  `metadata`, `statement`, `resultset`, and `schema`. The driver class is still
  `com.runaxsoftware.qdrant.jdbc.QdrantDriver`. ([#5])
- Statements plan against the collection's real columns: a missing collection reports
  SQLState `42S02` and an unknown column `42S22`. Before reporting an unknown column, the
  statement reads the collection's columns again once, so a field added since they were
  cached is found. Reading collections still isn't supported. ([#8])

### Removed

- The Qdrant Java SDK (`io.qdrant:client`) and the gRPC, Guava, protobuf, SLF4J, and
  perfmark libraries it brought into the driver jar. ([#5])

[#5]: https://github.com/runax-software/runax-qdrant-jdbc/pull/5
[#6]: https://github.com/runax-software/runax-qdrant-jdbc/pull/6
[#7]: https://github.com/runax-software/runax-qdrant-jdbc/pull/7
[#8]: https://github.com/runax-software/runax-qdrant-jdbc/pull/8
[#13]: https://github.com/runax-software/runax-qdrant-jdbc/pull/13
[#14]: https://github.com/runax-software/runax-qdrant-jdbc/pull/14
[#15]: https://github.com/runax-software/runax-qdrant-jdbc/pull/15
[#18]: https://github.com/runax-software/runax-qdrant-jdbc/pull/18
[#16]: https://github.com/runax-software/runax-qdrant-jdbc/pull/16
[#17]: https://github.com/runax-software/runax-qdrant-jdbc/pull/17
[#19]: https://github.com/runax-software/runax-qdrant-jdbc/pull/19
[#20]: https://github.com/runax-software/runax-qdrant-jdbc/pull/20
[#21]: https://github.com/runax-software/runax-qdrant-jdbc/pull/21
[#22]: https://github.com/runax-software/runax-qdrant-jdbc/pull/22
[#23]: https://github.com/runax-software/runax-qdrant-jdbc/pull/23
[#24]: https://github.com/runax-software/runax-qdrant-jdbc/pull/24
