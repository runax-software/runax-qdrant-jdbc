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
- Schema-qualified table names: `FROM system.collections`, `FROM collections.docs`, and
  `FROM qdrant.system.collections`, the forms tools generate. The driver's own names — the
  catalog, the two schemas, and the system tables and their columns — match whatever the
  case; a collection name is still matched exactly, as Qdrant matches it. An unknown catalog
  or schema reports SQLState `42S02`. ([#13])

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
