# Changelog

All notable changes to the Runax JDBC Driver for Qdrant are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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

### Fixed

- Opening a collection in DataGrip's data editor failed with `Expected the end of the
  statement, found '.'`: it sends `SELECT t.* FROM collections.<name> t`, which the driver
  couldn't parse. Before the schema-qualified names landed the same statement failed in the
  lexer, as `Unexpected character '.'`. ([#14])

### Changed

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
[#16]: https://github.com/runax-software/runax-qdrant-jdbc/pull/16
[#20]: https://github.com/runax-software/runax-qdrant-jdbc/pull/20
