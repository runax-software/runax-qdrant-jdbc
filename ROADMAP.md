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
  and `NOT`; `BETWEEN` and the geo and nested-array functions in the dialect spec come later.
- [x] **`LIKE` and full-text matching** — `name LIKE 'Desk%'` as Qdrant's `prefix` match and
  `name LIKE '%desk%'` as its `substring` match, both case-sensitive, with `NOT LIKE`
  following SQL's rule that a missing field matches neither; and
  `text_match(title, 'refund policy')`, `text_any(…)` and `phrase_match(…)` as `text`,
  `text_any` and `phrase`, which reach a text index and run without one too. A pattern Qdrant
  can't match — a suffix, a `%` in the middle, `_`, `ILIKE` — is an error naming what to write
  instead. A phrase needs an index built with `phrase_matching`, which the driver checks first
  because Qdrant answers one without it with no points rather than an error. `at_least`, the
  geo functions, and the array functions come later.
- [x] **COUNT(\*)** through the count API — `SELECT COUNT(*) FROM docs` is one exact count call
  and one `BIGINT` row, without reading a single point or column. Counting the points a `WHERE`
  matches sends that filter to the count, and a count per group goes through the facet API, as
  the `GROUP BY` item below describes. `COUNT(*)` has to be the only select item unless the
  statement groups.
- [x] **Similarity search with vector literals** —
  `ORDER BY similarity(vector, [0.1, ...]) DESC` through the Query API, with a `_score`
  column. Dense vectors only: searching a sparse vector or a multivector, and the other forms
  a search can take, come with the items below.
- [x] **Batches of searches** — several similarity searches of one collection in one
  `execute`, separated by semicolons, sent as one Query API batch and answered with a result
  set each, through `getMoreResults`.
- [x] **The best points of each group** — `SELECT DISTINCT ON (doc_id) … ORDER BY
  similarity(…) DESC LIMIT 10` for the best point of each value of a payload field, and `LIMIT 3
  BY doc_id` for the best three, through Qdrant's grouped Query API. The statement's own `LIMIT`
  and `OFFSET` still count rows.
- [x] **DataGrip compatibility** — harmless defaults for the `Connection` and
  `Statement` methods DataGrip calls on every connection, and support for the SQL it
  generates when opening, filtering, and sorting a table. The defaults and the statement
  that opens a table (`SELECT t.* FROM collections.docs t`, through table aliases and
  qualified names), filtering through `WHERE`, and sorting through `ORDER BY` are all done, and
  so is paging past the first screen with `OFFSET`, its own item below.
- [x] **Integration tests** against Qdrant in Docker, run by CI on every pull request.
- [ ] **Packaging** — one self-contained jar, a DataGrip setup guide, and publishing to
  Maven Central as `com.runaxsoftware:qdrant-jdbc`. The jar (`./gradlew shadowJar`) and
  the guide (in the README) are done; Maven Central isn't.
- [x] **`embed('text')`** in a search: `similarity(dense, embed('refund policy',
  'openai:text-embedding-3-small'))`. The model is `provider:model`: `openai:` and `voyage:`
  are called by the driver, with their keys from the connection's `openaiApiKey` and
  `voyageApiKey`, and in `INSERT`, where Qdrant or the driver embeds the text as the point is
  stored. Every text a statement needs goes to the provider in one request, split only to the
  provider's own limits, so an `INSERT` of many rows is one call. A model may be asked for fewer
  dimensions than it was trained for (`?dimensions=1024`), and whether a text is a query or a
  document travels with it. Other providers come later.
- [x] **Qdrant Cloud inference** — let Qdrant embed the text server-side where available,
  through the same function: `embed('refund policy', 'qdrant:qdrant/bm25')`. Every Qdrant runs
  BM25 into sparse vectors; hosted dense models need Qdrant Cloud's inference.
- [x] **Embedding model per vector** — kept in the collection's metadata for each vector
  (`runax.embed.dense = "openai:text-embedding-3-small"`), not per connection, so
  `embed('text')` needs no model and a query can't silently use the wrong one. It's set from
  SQL with `ALTER TABLE docs SET (metadata.runax.embed.dense = 'openai:…')`.
- [x] **GROUP BY** with `COUNT(*)` through the facet API, on a field indexed as keyword,
  integer, bool, or uuid. A grouped statement needs a limit, since Qdrant counts the most
  common values in one call; `HAVING` and ordering the groups by anything but their count come
  later.
- [x] **ORDER BY** on range-indexed payload fields, through the scroll's `order_by`, and
  `ORDER BY _id` as Qdrant's own order. An ordered read needs a limit, since Qdrant answers
  one in a single page; paging an ordered read by value (`order_by.start_from`) comes later.
- [x] **OFFSET** on every kind of read, which is what lets a data grid page past its first
  screen. Qdrant has no one way to do it: a search's `OFFSET` is the Query API's own `offset`, a
  row count sent as written, while a scroll pages by point ID and the facet API has no `offset`
  at all, so a plain read skips through the cursor, an ordered read and a `GROUP BY` over-fetch
  `limit + offset`, and each discards the first rows. Because the driver pays for those skips
  they're capped at 10,000 rows, past which the statement is an error rather than a quiet read of
  a whole collection. `COUNT(*)` is one row, so any `OFFSET` skips it. Paging an ordered read by
  value instead of by count (`order_by.start_from`, above) would lift its cap.
- [x] **Prepared statements** with parameters: `?` for constants in `WHERE`, the select list,
  and a search's vector (`float[]` or `double[]`), batches of values for `DELETE`, and
  parameter metadata. `LIMIT ?` and `OFFSET ?` come later.
- [x] **Request options** — `SET qdrant.consistency = majority` and the other options
  Qdrant takes with each request (`ordering`, `wait`, `timeout`, `hnsw_ef`, `exact`,
  `indexed_only`), kept for the connection, with `RESET` and `SHOW`. `shard_key`, the
  quantization options, and the `/*+ … */` hint for one statement come later.
- [x] **Grid edits** — `UPDATE ... WHERE _id = ?` from DataGrip's data editor, mapped to
  Qdrant's set-payload call: fields, fields inside objects, `NULL` to delete one, a new field,
  or `_payload` for the whole payload, in one batch call.
- [x] **Changing vectors** — `UPDATE docs SET dense = '[0.1, ...]' WHERE ...` through Qdrant's
  `update_vectors`, and `SET dense = NULL` through `delete_vectors`, both in the same batch call
  as any payload change beside them. A vector is written out, given as JSON text as a tool sends
  a cell, or made with `embed('text')`, as an `INSERT` takes it. The two halves differ because
  Qdrant does: a delete takes the filter, while a write takes point IDs and rejects a filter
  alone, so the driver reads the matching IDs first, sends the filter along as `update_filter`
  so a point that stops matching in between is left alone, and caps the read at 10,000 points.
  Deleting a vector means a point can have none, so `IS NULL` now answers for the unnamed vector
  too.
- [x] **Indexes** — `CREATE INDEX [IF NOT EXISTS] ON docs (lang) [USING text] [WITH (…)]`
  and `DROP INDEX lang_idx ON docs`, with each type's options, the type defaulting to the
  column's, and the columns the driver keeps following at once.
- [x] **Views** — Qdrant aliases: `CREATE [OR REPLACE] VIEW current AS SELECT * FROM docs_v2`,
  `ALTER VIEW current RENAME TO live`, and `DROP VIEW [IF EXISTS] current`, the replace as one
  atomic switch.
- [x] **INSERT** — `INSERT INTO docs (_id, lang, dense) VALUES (…), (…)` as one upsert that
  skips points already there: an ID that's taken is an error, as a primary key's is, and a row
  without one gets a UUID, which `getGeneratedKeys` returns. Vectors are written out, given as
  JSON text, or embedded with `embed()`.
- [x] **`INSERT … ON CONFLICT`** — what to do with a row whose ID is taken, as Qdrant's upsert
  modes: `ON CONFLICT [(_id)] DO NOTHING` is `insert_only`, which skips a point that's there and
  needs no ID lookup, and `DO UPDATE` is `upsert`, which replaces it. `DO UPDATE … WHERE
  price < 100` sends the condition as Qdrant's `update_filter`, so only the points already there
  that match are replaced while new ones are inserted regardless — PostgreSQL's own meaning for
  that clause. `DO UPDATE` takes no `SET` list, since Qdrant replaces a whole point. Batching the
  provider calls a driver-side `embed()` makes comes later.
- [x] **DELETE** mapped to deleting points: `DELETE FROM docs WHERE …` through the same
  filters as `SELECT`, `TRUNCATE TABLE docs` for every point, and `DROP TABLE [IF EXISTS] docs`
  for the collection itself. A `DELETE` without `WHERE` is an error that points to `TRUNCATE`.
- [ ] **DataGrip plugin** (`runax-qdrant-datagrip`, a separate repository) — a search
  panel that writes SQL into a console, collection tools, and a bundled driver
  definition.
- [x] **Creating collections** — `CREATE TABLE [IF NOT EXISTS] docs (dense VECTOR(384)
  DISTANCE COSINE, keywords SPARSE_VECTOR MODIFIER IDF, lang KEYWORD INDEX)`: dense,
  multi-, and sparse vectors, a single `_vector` column for the unnamed vector, and payload
  indexes. Collection settings in `WITH (…)` come later.
- [x] **Adding and dropping vectors** — `ALTER TABLE docs ADD COLUMN image VECTOR(512) DISTANCE
  COSINE` and `ALTER TABLE docs DROP COLUMN image`, through Qdrant's named-vector API: a dense
  vector, a multivector, or a sparse one, on a collection that already holds points. A payload
  column is an error naming `CREATE INDEX` or `DROP INDEX`, since the payload has no schema to
  alter.
- [x] **Collection settings** — `ALTER TABLE docs SET (hnsw.m = 16, indexing_threshold = 20000)`
  through Qdrant's update-collection API: replication and payload storage, HNSW, the
  optimizers, strict mode, quantization, and metadata, each setting written as `group.name`, by
  its name alone where one group has it, or with its group as JSON. `ALTER TABLE docs ALTER
  COLUMN dense SET (on_disk = true)` changes one vector's. Every name is checked first, since
  Qdrant drops one it doesn't know without a word. The same settings at creation, `CREATE TABLE
  … WITH (…)`, come later.
- [ ] **Post-filtering in the driver (to decide)** — conditions Qdrant can't run, such as
  `price > cost`, `LOWER(name) = 'lamp'`, or joins, checked by the driver on the rows Qdrant
  returns. Probably opt-in and capped, since it can read far more points than it returns.
- [x] **System tables** — read-only tables for server and collection state, browsable in
  DataGrip, in a `system` schema next to `collections`, the schema collections sit in, so
  they can't clash with collection names. `system.collections` (status, optimizer, point and
  segment counts, shards, vectors, payload indexes) and `system.aliases` are done, along
  with the schema-qualified names (`system.collections`, `qdrant.system.collections`) that
  reach them, and so are `snapshots`, `shard_keys`, `shards`, `cluster`, `peers`,
  `telemetry`, `metrics`, `issues`, `health` (Qdrant's three health probes, a row each), and
  `optimizations` (each collection's running, queued, and latest completed optimizations), and
  `quotas` (the server's limits on memory and disk, which `ALTER SYSTEM SET (quotas.… = …)`
  changes), and `cluster_telemetry` (the telemetry of every peer, on a server that is a cluster).
  Read-only lists then move from `CALL` procedures to these tables. `TRUNCATE TABLE system.issues` clears the issues Qdrant reports, the one write a
  system table takes. A system table takes `SELECT`, a column list, `LIMIT`, and `OFFSET`; `WHERE`,
  `ORDER BY`, and `COUNT(*)` over one come with the items above. Only the telemetry Qdrant
  answers with by default is exposed, not its deeper `?details_level=` levels.
- [x] **`CALL` and the cluster procedures** — `CALL qdrant.create_shard_key('docs', 'eu')` and
  eleven more, for what Qdrant does that isn't about rows: shard keys, moving, replicating and
  dropping shards, resharding, removing a peer, recovering Raft. Arguments go by position or as
  `name => value`, constants or `?`, and `DatabaseMetaData` lists the procedures. Qdrant takes
  them only when it runs as a cluster. Procedures for snapshots come later.
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
