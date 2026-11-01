# Runax JDBC Driver for Qdrant — Roadmap

Planned work, roughly in the order it will land. The SQL each item accepts is specified in
[docs/specs/sql-dialect.md](docs/specs/sql-dialect.md); what the ticks below amount to in
measured REST and SQL coverage is in [docs/coverage.md](docs/coverage.md).

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
- [x] **COUNT(\*)** through the count API — `SELECT COUNT(*) FROM docs` is one exact count call
  and one `BIGINT` row, without reading a single point or column. Still to come: counting the
  points a `WHERE` matches, by sending the filter above to the count, and a count per group
  through the facet API below.
- [x] **Similarity search with vector literals** —
  `ORDER BY similarity(vector, [0.1, ...]) DESC` through the Query API, with a `_score`
  column. Dense vectors only: searching a sparse vector or a multivector, and the other forms
  a search can take, come with the items below.
- [x] **DataGrip compatibility** — harmless defaults for the `Connection` and
  `Statement` methods DataGrip calls on every connection, and support for the SQL it
  generates when opening, filtering, and sorting a table. The defaults and the statement
  that opens a table (`SELECT t.* FROM collections.docs t`, through table aliases and
  qualified names), filtering through `WHERE`, and sorting through `ORDER BY` are all done.
  What a grid still can't do is page past the first screen (`OFFSET`), its own item below.
- [x] **Integration tests** against Qdrant in Docker, run by CI on every pull request.
- [ ] **Packaging** — one self-contained jar, a DataGrip setup guide, and publishing to
  Maven Central as `com.runaxsoftware:qdrant-jdbc`. The jar (`./gradlew shadowJar`) and
  the guide (in the README) are done; Maven Central isn't.
- [x] **`embed('text')`** in a search: `similarity(dense, embed('refund policy',
  'openai:text-embedding-3-small'))`. The model is `provider:model`: `openai:` and `voyage:`
  are called by the driver, with their keys from the connection's `openaiApiKey` and
  `voyageApiKey`. Other providers, batching, and `embed()` in `INSERT` come later.
- [x] **Qdrant Cloud inference** — let Qdrant embed the text server-side where available,
  through the same function: `embed('refund policy', 'qdrant:qdrant/bm25')`. Every Qdrant runs
  BM25 into sparse vectors; hosted dense models need Qdrant Cloud's inference.
- [x] **Embedding model per vector** — kept in the collection's metadata for each vector
  (`runax.embed.dense = "openai:text-embedding-3-small"`), not per connection, so
  `embed('text')` needs no model and a query can't silently use the wrong one. Setting it from
  SQL (`ALTER TABLE … SET (metadata = …)`) comes later; Qdrant's update-collection API sets it
  today.
- [x] **GROUP BY** with `COUNT(*)` through the facet API, on a field indexed as keyword,
  integer, bool, or uuid. A grouped statement needs a limit, since Qdrant counts the most
  common values in one call; `HAVING` and ordering the groups by anything but their count come
  later.
- [x] **ORDER BY** on range-indexed payload fields, through the scroll's `order_by`, and
  `ORDER BY _id` as Qdrant's own order. An ordered read needs a limit, since Qdrant answers
  one in a single page; paging an ordered read by value (`order_by.start_from`) comes later.
- [x] **Prepared statements** with parameters: `?` for constants in `WHERE`, the select list,
  and a search's vector (`float[]` or `double[]`), batches of values for `DELETE`, and
  parameter metadata. `LIMIT ?` and `OFFSET ?` come later.
- [x] **Request options** — `SET qdrant.consistency = majority` and the other options
  Qdrant takes with each request (`ordering`, `wait`, `timeout`, `hnsw_ef`, `exact`,
  `indexed_only`), kept for the connection, with `RESET` and `SHOW`. `shard_key`, the
  quantization options, and the `/*+ … */` hint for one statement come later.
- [ ] **Grid edits** — `UPDATE ... WHERE _id = ?` from DataGrip's data editor, mapped to
  Qdrant's set-payload call.
- [x] **DELETE** mapped to deleting points: `DELETE FROM docs WHERE …` through the same
  filters as `SELECT`, `TRUNCATE TABLE docs` for every point, and `DROP TABLE [IF EXISTS] docs`
  for the collection itself. A `DELETE` without `WHERE` is an error that points to `TRUNCATE`.
- [ ] **DataGrip plugin** (`runax-qdrant-datagrip`, a separate repository) — a search
  panel that writes SQL into a console, collection tools, and a bundled driver
  definition.
- [x] **Creating collections** — `CREATE TABLE [IF NOT EXISTS] docs (dense VECTOR(384)
  DISTANCE COSINE, keywords SPARSE_VECTOR MODIFIER IDF, lang KEYWORD INDEX)`: dense,
  multi-, and sparse vectors, a single `_vector` column for the unnamed vector, and payload
  indexes. Collection settings in `WITH (…)` and `ALTER TABLE` come later.
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
