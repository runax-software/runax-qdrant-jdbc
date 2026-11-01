# Runax JDBC Driver for Qdrant — Roadmap

Planned work, roughly in the order it will land. The SQL each item accepts is specified in
[docs/specs/sql-dialect.md](docs/specs/sql-dialect.md).

- [x] **Connection** — `jdbc:qdrant://host:6333` over Qdrant's REST API, TLS, and the
  Qdrant API key taken from the password field.
- [x] **Collections as tables** — `DatabaseMetaData` lists collections, in catalog `qdrant`
  and schema `collections`, so DataGrip's database explorer fills in on its own.
- [x] **Columns** — one column per payload field under its own name (found from the
  collection's payload indexes and a sample of points), plus the driver's `_`-prefixed
  columns: `_id` (text, since Qdrant IDs are unsigned 64-bit integers or UUIDs), `_payload`
  (the whole payload as JSON), and `_vector` or the vector's name for named, sparse, and
  multi-vectors.
- [x] **SELECT** — `WHERE` mapped to Qdrant filters, `LIMIT`, and result sets that page
  through Qdrant's scroll cursor instead of loading everything. Vectors are fetched only
  when a query names their column. `WHERE` takes comparisons, `IN`, `IS NULL`, `AND`, `OR`
  and `NOT`; `LIKE`, `BETWEEN`, and the filter functions in the dialect spec (full text,
  geo, nested arrays) come later.
- [ ] **COUNT(\*)** through the count API.
- [x] **Similarity search with vector literals** —
  `ORDER BY similarity(vector, [0.1, ...]) DESC` through the Query API, with a `_score`
  column. Dense vectors only: searching a sparse vector or a multivector, and the other forms
  a search can take, come with the items below.
- [ ] **DataGrip compatibility** — harmless defaults for the `Connection` and
  `Statement` methods DataGrip calls on every connection, and support for the SQL it
  generates when opening, filtering, and sorting a table. The defaults and the statement
  that opens a table (`SELECT t.* FROM collections.docs t`, through table aliases and
  qualified names) and filtering through `WHERE` are done; sorting needs `ORDER BY`.
- [x] **Integration tests** against Qdrant in Docker, run by CI on every pull request.
- [ ] **Packaging** — one self-contained jar, a DataGrip setup guide, and publishing to
  Maven Central as `com.runaxsoftware:qdrant-jdbc`. The jar (`./gradlew shadowJar`) and
  the guide (in the README) are done; Maven Central isn't.
- [ ] **`embed('text')`** through the embedder abstraction (OpenAI and other providers),
  which is being built separately.
- [ ] **Qdrant Cloud inference** — let Qdrant embed the text server-side where available.
- [ ] **Embedding model per vector** — kept in the collection's metadata for each named
  vector, not per connection, so a query can't silently use the wrong model.
- [ ] **GROUP BY** with `COUNT(*)` through the facet API.
- [ ] **ORDER BY** on range-indexed payload fields. Only `ORDER BY similarity(...) DESC`
  works so far; every other ordering is an error.
- [ ] **Prepared statements** with parameters.
- [ ] **Grid edits** — `UPDATE ... WHERE _id = ?` from DataGrip's data editor, mapped to
  Qdrant's set-payload call.
- [ ] **DELETE** mapped to deleting points.
- [ ] **DataGrip plugin** (`runax-qdrant-datagrip`, a separate repository) — a search
  panel that writes SQL into a console, collection tools, and a bundled driver
  definition.
- [ ] **Creating collections** — needs SQL syntax for vector size and distance.
- [ ] **Post-filtering in the driver (to decide)** — conditions Qdrant can't run, such as
  `price > cost`, `LOWER(name) = 'lamp'`, or joins, checked by the driver on the rows Qdrant
  returns. Probably opt-in and capped, since it can read far more points than it returns.
- [x] **System tables** — read-only tables for server and collection state, browsable in
  DataGrip, in a `system` schema next to `collections`, the schema collections sit in, so
  they can't clash with collection names. `system.collections` (status, optimizer, point and
  segment counts, shards, vectors, payload indexes) and `system.aliases` are done, along
  with the schema-qualified names (`system.collections`, `qdrant.system.collections`) that
  reach them, and so are `snapshots`, `shard_keys`, `shards`, `cluster`, `peers`,
  `telemetry`, `metrics`, and `issues`. Read-only lists then move from `CALL` procedures to
  these tables. A system table takes `SELECT`, a column list, and `LIMIT`; `WHERE`,
  `ORDER BY`, and `COUNT(*)` over one come with the items above. Only the telemetry Qdrant
  answers with by default is exposed, not its deeper `?details_level=` levels.
- [ ] **Buffered writes (later, opt-in)** — not real transactions, since Qdrant has none.
  With a setting on and auto-commit off, the driver holds `INSERT`, `UPDATE`, and `DELETE`
  until `commit()` and drops them on `rollback()`. Documented as "rollback before commit
  only": commit isn't atomic, so a failure midway leaves part of the changes applied, and
  reads don't see uncommitted writes.

## Not planned

- **JOINs and arbitrary aggregates** (`SUM`, `AVG`, ...) — Qdrant can't run them, and
  emulating them means scanning whole collections on the client. They fail with an
  error instead.
- **Local embedding models** — the driver doesn't run models itself; it uses the embedder
  abstraction or Qdrant's own inference.
