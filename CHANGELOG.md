# Changelog

All notable changes to the Runax JDBC Driver for Qdrant are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `CALL`, for what Qdrant does that isn't about rows, and twelve procedures for a cluster:
  `create_shard_key` and `delete_shard_key`, `move_shard`, `replicate_shard`,
  `abort_shard_transfer`, `restart_shard_transfer`, `drop_replica`, `start_resharding`,
  `abort_resharding`, `replicate_points`, `remove_peer` and `recover_raft`. A call is `CALL
  qdrant.create_shard_key('docs', 'eu', shards_number => 2)`: arguments by position or as `name
  => value`, each a constant or a `?`, so a `PreparedStatement` runs one. The driver checks the
  arguments and Qdrant the rest. `DatabaseMetaData.getProcedures` and `getProcedureColumns` list
  them, so a tool shows what can be called. Qdrant takes these only when it runs as a cluster.
  `prepareCall` stays unsupported, as no procedure returns anything to read back. ([#49])
- `system.cluster_telemetry`, the telemetry of every peer as the connected one gathers it, one
  row per value like `system.telemetry`; a standalone server has no rows. With it and the
  procedures every route of Qdrant's distributed area is covered. ([#49])
- The server's quotas, the limits on memory and disk past which Qdrant refuses writes:
  `system.quotas` shows them in one row beside how much of each resource is used now, and
  `ALTER SYSTEM SET (quotas.enabled = true, quotas.max_disk_usage_percent = 90)` changes them.
  A setting is written as a collection's is — `quotas.name`, the name alone, or `quotas =
  '{…}'` — and `NULL` takes a limit away. Qdrant replaces the whole quota config with each
  write, so a limit left out would be a limit lost; the driver reads the config first and sends
  it back with only the named settings changed. ([#48])
- `ALTER TABLE … SET (…)`, which changes a collection's settings in place: replication and
  payload storage, HNSW, the optimizers, strict mode, quantization, and metadata. A setting can
  be written by its group and name (`hnsw.m = 16`, or `hnsw_config.m` as Qdrant names the
  group), by its name alone where only one group has it (`m = 16`), or with its whole group as
  JSON (`hnsw = '{"m": 16}'`); all three send the same change. `ALTER TABLE … ALTER COLUMN dense
  SET (on_disk = true, hnsw.m = 8)` changes one vector's settings, dense or sparse. Qdrant
  accepts a setting it doesn't know, drops it, and answers that all went well, so the driver
  checks every name and value first and says which settings there are. Metadata merges, a key
  set to `NULL` is removed, and `metadata.runax.embed.dense = 'openai:…'` sets a vector's
  embedding model from SQL, which until now took a call to Qdrant's API. With this every route
  of Qdrant's collections area is covered. ([#46])
- `ALTER TABLE … ADD COLUMN` and `DROP COLUMN` for named vectors, so a collection that already
  holds points can gain a second embedding — `ALTER TABLE docs ADD COLUMN image VECTOR(512)
  DISTANCE COSINE` — or lose one, without being recreated. The column is written as `CREATE
  TABLE` writes it: `VECTOR(n)`, `MULTIVECTOR(n)` or `SPARSE_VECTOR [MODIFIER IDF]`. Qdrant
  answers an add of a vector that's there, and a drop of one that isn't, as done, so the driver
  looks first: a taken name is `42S21` and a missing vector `42S22`, unless the statement says
  `IF NOT EXISTS` or `IF EXISTS`. Only vectors are columns Qdrant keeps a declaration of, so a
  payload column is an error naming `CREATE INDEX`, `DROP INDEX` or `UPDATE … SET … = NULL`,
  and `_vector` one saying it comes from `CREATE TABLE`. `DatabaseMetaData` now reports
  `supportsAlterTableWithAddColumn` and `supportsAlterTableWithDropColumn`. ([#45])
- `system.optimizations`, what Qdrant's optimizers are doing to each collection: a row per
  optimization that is running, queued, or among the latest completed, with its optimizer,
  status, the segments and points it takes, when it started and finished, and its steps as
  Qdrant's own progress tree. It answers "is my collection still indexing?" from a console,
  which `system.collections` only hinted at with `optimizer_status`. ([#44])
- `system.health`, Qdrant's three health probes as a table: one row each for `healthz`, `livez`
  and `readyz`, with `ok`, the HTTP `status_code` and the `message` Qdrant sent. A probe that
  fails is a row saying so rather than an error, since "not ready" is the answer the table is
  for; `readyz` is the one that looks at the shards and answers 503 while one isn't ready. With
  it every route of Qdrant's service area is covered. ([#43])
- `TRUNCATE TABLE system.issues` clears the issues Qdrant reports about itself, so a list that
  has been read and acted on can be emptied from the same console that showed it. It is the one
  write a `system` table takes: Qdrant forgets its issues all at once and can't forget one, so
  `DELETE FROM system.issues WHERE …` is an error naming the `TRUNCATE`, and every other system
  table stays read-only. The statement reports no count, since Qdrant doesn't say how many
  issues there were, and a read-only connection refuses it. ([#42])
- Every text a statement embeds now goes to the provider in **one request**. An `INSERT` of a
  thousand rows that embeds a column was a thousand round trips, one per row; it is one call,
  split only as far as each provider's own limits on texts and tokens per request require
  (OpenAI 2048 texts / 300k tokens, Voyage 1000 / 120k). Tokens are estimated as UTF-8 bytes,
  which runs high on purpose so a batch stays inside the limit. Rows giving the same text for
  the same model share one vector, since the answer is the same and a provider charges per text.
  A provider may answer in any order and says which text each embedding is for, so vectors go
  back where their texts were rather than in arrival order — pairing a row with another row's
  vector is the kind of mistake nothing downstream could detect. ([#40])
- A size in a model ID: `embed('refund policy', 'openai:text-embedding-3-large?dimensions=1024')`.
  Models that can return fewer dimensions than they were trained for do that better than the
  driver truncating a vector afterwards, so the size becomes OpenAI's `dimensions` or Voyage's
  `output_dimension`. It belongs to the ID rather than to a connection setting, so a collection's
  `runax.embed.<vector>` metadata names one vector exactly and two IDs asking for different sizes
  count as different models — a search can't quietly ask for a size the points weren't stored
  with. A size that doesn't match the vector's own is an error before the provider is called, so
  a vector that couldn't be stored is never paid for. ([#39])

- `INSERT … ON CONFLICT`, so a batch that may repeat point IDs is one statement instead of a
  split into `INSERT`s and `UPDATE`s. `ON CONFLICT [(_id)] DO NOTHING` leaves a point that's
  already there as it is, and `DO UPDATE` replaces it; without the clause a taken ID is still
  `23505`, and that error now names both options. The two map straight onto Qdrant's upsert
  modes: `DO NOTHING` is `insert_only`, which skips an existing point and reports nothing, so
  the statement also drops the ID lookup a plain `INSERT` makes — one call instead of two —
  while `DO UPDATE` is `upsert`.

  `DO UPDATE … WHERE price < 100` sends the condition as Qdrant's `update_filter`, which it
  applies only to the points already there: those that match are replaced, those that don't keep
  their state, and a row whose ID is new is inserted whatever the condition says. That is
  PostgreSQL's meaning for the clause as well, and it makes a conditional refresh one statement.
  `DO UPDATE` takes no `SET` list, because Qdrant replaces a whole point rather than merging
  into it — a column the statement doesn't name is gone, and the error says so. The conflict
  target is optional and may only be `_id`, since a Qdrant point has one key. None of `ON`,
  `CONFLICT`, `DO` and `NOTHING` is a keyword, so all four stay usable as column names. ([#36])
- `LIKE` and full-text matching, so the text a collection holds is reachable from SQL.
  `name LIKE 'Desk%'` is Qdrant's `prefix` match and `name LIKE '%desk%'` its `substring`
  match, both case-sensitive, as Qdrant's exact match is; a pattern with no wildcard matches
  the value exactly, as `=` does, and `NOT LIKE` carries `is_empty` so a point without the
  field matches neither, as SQL says. `text_match(title, 'refund policy')` matches every word
  through a text index, `text_any(title, 'refund shipping')` any of them, and
  `phrase_match(title, 'refund policy')` the words in order. All of them reach each element of
  an array field, and all run on a field with no index at all, as the rest of `WHERE` does —
  Qdrant reads the payload instead, just more slowly. A pattern Qdrant can't match is an error
  naming what to write instead rather than a search the driver would trim itself: `'%lamp'` has
  no suffix match and points at `'%lamp%'`, a `%` in the middle of a pattern has none, `_`
  isn't a single-character wildcard, `ILIKE` has no case-insensitive match, and `LIKE` on a
  number or a boolean compares rather than matches. A phrase needs a text index built with
  `phrase_matching`, which the driver checks first, because Qdrant answers a phrase match
  against an index without it with no points rather than an error — a correct statement would
  otherwise read as a phrase nothing holds; the error names
  `CREATE INDEX … USING text WITH (phrase_matching = true)`, which the driver writes too.
  `LIKE` is read as an operator only after a column, so a payload field may still be called
  `like`. ([#34])
- Changing vectors with `UPDATE`, so a vector is no longer write-once:
  `UPDATE docs SET dense = '[0.1, 0.2]' WHERE _id = 7` writes one and
  `SET dense = NULL` deletes it, in the same batch call as any payload change set beside them.
  The value is a vector written out, JSON text as a tool sends a cell (`'[[…], […]]'` for a
  multivector, `'{"indices": […], "values": […]}'` for a sparse one), or `embed('text')`, with
  the dimensions and shape checked against the collection before anything is sent. Editing a
  vector cell in a grid now saves like every other cell, and re-embedding a collection after a
  model change is one statement instead of deleting and re-inserting its points.

  The two halves reach Qdrant differently because Qdrant does. `delete_vectors` takes the filter
  as it is. `update_vectors` takes a list of point IDs and rejects a filter alone, so a write
  scrolls the IDs the `WHERE` matches first and sends that filter along as the operation's
  `update_filter`, which leaves alone any point that stopped matching in between rather than
  writing to it on the strength of a stale ID. Because that read is one the statement pays for
  and doesn't return, it's capped at 10,000 points — the same cap a driver-side `OFFSET` has —
  and past it the statement is an error suggesting a narrower `WHERE`. Neither of Qdrant's two
  standalone vector routes is called: `points/batch` does both jobs in one request, as it
  already does for the four payload routes. ([#35])
- `_vector IS NULL` and `IS NOT NULL` on a collection's unnamed vector, which used to be an
  error on the grounds that every point has one. Deleting a vector makes that false — Qdrant
  allows a point to lose even its only vector, keeping its payload — so the question is now
  answered with `has_vector` the way it already was for named vectors. ([#35])
- `embed('text')` as an `UPDATE` value, for the vector columns. The text goes to the provider
  once for the whole statement rather than once per point, since an `UPDATE` writes the same
  vector to every point its `WHERE` matches. ([#35])
- Payload indexes from SQL: `CREATE INDEX [IF NOT EXISTS] ON docs (lang) [USING type]
  [WITH (option = value, …)]` and `DROP INDEX [IF EXISTS] lang_idx ON docs`, or `ON docs
  (lang)`. The type defaults to the column's; each type's options are checked, since Qdrant
  drops unknown ones silently, and `stemmer = english` becomes Qdrant's Snowball stemmer. A
  second index on a field is `42S11` and a missing one `42S12`, where Qdrant would replace the
  first and report the second as dropped. ([#33])
- Views as Qdrant aliases: `CREATE [OR REPLACE] VIEW current AS SELECT * FROM docs_v2`,
  `ALTER VIEW current RENAME TO live`, and `DROP VIEW [IF EXISTS] current`. `OR REPLACE` moves
  the alias in one atomic change; a taken name is `42S01` and a missing view `42S02`, where
  Qdrant would move the alias or report it as deleted. ([#33])

- `INSERT INTO docs (_id, lang, dense) VALUES (…), (…)`, as one upsert in Qdrant's
  `insert_only` mode. An ID that's already taken fails with `23505` before anything is
  written, as a primary key would, and a row without `_id` gets a UUID, which
  `getGeneratedKeys` returns. Values are read as their column's type; a field the collection
  doesn't have yet is added, `"specs.color"` builds its object, and `_payload` gives the whole
  payload. A vector is written out, given as JSON text, or made with `embed('text')`, by Qdrant
  as it stores the point or by the driver before. Batches of a prepared `INSERT` work. ([#31])
- `UPDATE docs SET lang = 'de', year = 2024 WHERE _id = 7`, so DataGrip's data editor saves
  edited cells. The changes go in one call to Qdrant's batch update API: fields are set
  together, `"specs.color"` is set inside its object, `NULL` deletes a field, a field the
  collection doesn't have yet is added, and `_payload` replaces or clears the whole payload.
  Values are read as their column's type, so a cell's text such as `'49.5'` is stored as a
  number. The update count is the matching points; `UPDATE` without `WHERE` is an error, as
  are changing `_id` and vectors. ([#28])
- `NULL` as a value, so `SET lang = NULL` deletes a field and `WHERE lang = NULL` says to use
  `IS NULL`. A point ID written as text that holds a whole number, `_id = '7'`, now matches
  that point, as DataGrip sends IDs from a text column. ([#28])
- Request options for the connection: `SET qdrant.consistency = majority`, and `ordering`,
  `wait`, `timeout`, `hnsw_ef`, `exact` and `indexed_only`, each sent with the requests it
  applies to: reads take the consistency and timeout, writes whether to wait, the ordering and
  the timeout, and searches the precision settings. `RESET qdrant.<option>` and `RESET ALL`
  put them back to Qdrant's defaults; `SHOW qdrant.<option>` and `SHOW ALL` list them. They
  belong to the connection, and a read-only connection may change them. ([#27])
- `OFFSET n` on every kind of read, so a data grid can page past its first screen:
  `SELECT * FROM docs LIMIT 50 OFFSET 50`. Qdrant offers no single way to do it, so each kind of
  read reaches it differently. A similarity search sends the Query API's own `offset`, which is a
  row count, so Qdrant does the skipping and `LIMIT` still counts the points returned after it,
  with their own scores. A plain read pages to the row the offset names with the scroll cursor
  and discards the points it read on the way, because Qdrant's scroll `offset` is the point ID to
  start at and not a row count. An ordered read and a `GROUP BY` ask for `limit + offset` and
  drop the first rows: Qdrant rejects an `offset` beside an `order_by` outright, and its facet
  API has no `offset` field and ignores one rather than reporting it, so sending either would
  answer with the wrong rows. Where the driver does the skipping it pays for rows no statement
  returns, so an offset there is capped at 10,000 and a deeper one is an error naming the cap
  rather than a read of a whole collection dressed up as a read of one screen; a search's offset
  and a system table's are free, so neither is capped. `COUNT(*)` is one row, so any `OFFSET`
  skips past it and counts nothing, as `LIMIT 0` already did. `OFFSET` needs no `LIMIT` on a
  plain read or a system table; an ordered read, a search and a `GROUP BY` are bounded by a
  `LIMIT`, the maximum rows, or `defaultLimit`, and the over-fetch is whichever of those applied
  plus the offset. As JDBC specifies, `setMaxRows` bounds the rows the result set yields, so it
  counts the rows after the skip and never against it, and `setFetchSize` still sets the page a
  plain read is skipped through. ([#30])
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
- `defaultLimit` setting, `1000` by default: how many rows a read returns when the statement
  gives no `LIMIT` and sets no maximum rows. It applies only to the three reads Qdrant answers
  in one call and pages no further — an ordered read, a similarity search, and a `GROUP BY` —
  which until now were errors without a limit. The statement reports an `SQLWarning` (SQLState
  `01000`) saying how many rows the driver asked for and why, so a bounded answer never passes
  for the whole one. `defaultLimit=0` keeps asking the statement for its own limit, as before.
  A plain read still pages through the scroll cursor and needs no limit. ([#26])

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

- A collection with both an unnamed vector and named ones now writes and reads all of them.
  The driver took the unnamed vector to be the only one a collection could have: an `INSERT` or
  `UPDATE` that set `_vector` beside a named vector stored `_vector` and dropped the other, and
  `SELECT _vector` returned every vector of the point as one JSON object. Qdrant has always
  allowed the mix, and `ALTER TABLE … ADD COLUMN` on a collection with an unnamed vector is the
  way to it from SQL. ([#45])
- A provider's failure no longer reports a connection SQLState. An unreachable or broken OpenAI
  or Voyage AI answered `08001` or `08S01`, which says *the connection is gone*; connection pools
  close a connection that reports one. The connection to Qdrant is fine when a provider isn't, so
  these are now `HY000`, a timeout `HYT00`, and a rejected key stays `28000`. An API key echoed
  back by a provider or a proxy is also replaced with `***` before it reaches the message. ([#40])
- Text stored by `INSERT` and `UPDATE` was embedded as a *search query* by Voyage AI, not as a
  document. Voyage prepends a different retrieval prompt for each, so stored vectors were built
  with the wrong one and matched queries less well than they should. The `Embedder` interface only
  received a model name and a text, which was true enough when `embed()` existed in searches
  alone; it now carries the text's role, and `embed()` in a ranking function is a query while
  `embed()` in `INSERT` or `UPDATE` is a document. OpenAI's models embed both the same way and
  are unaffected. ([#39])

- The columns the driver keeps followed an `INSERT` or `UPDATE` that added a field only when a
  later `SELECT` named it; now the collection's columns are read again after either, so a
  `CREATE INDEX` on the new field finds its type. After an index change or a `DROP TABLE`, the
  columns kept under the collection's aliases are forgotten too. ([#33])
- A statement ending with `;`, as DataGrip and SQL scripts send it, failed with `Unexpected
  character ';'`. One semicolon at the end is now accepted; a second statement after it is
  still an error that says to run one statement at a time. ([#25])
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
[#25]: https://github.com/runax-software/runax-qdrant-jdbc/pull/25
[#26]: https://github.com/runax-software/runax-qdrant-jdbc/pull/26
[#27]: https://github.com/runax-software/runax-qdrant-jdbc/pull/27
[#28]: https://github.com/runax-software/runax-qdrant-jdbc/pull/28
[#30]: https://github.com/runax-software/runax-qdrant-jdbc/pull/30
[#31]: https://github.com/runax-software/runax-qdrant-jdbc/pull/31
[#33]: https://github.com/runax-software/runax-qdrant-jdbc/pull/33
[#34]: https://github.com/runax-software/runax-qdrant-jdbc/pull/34
[#35]: https://github.com/runax-software/runax-qdrant-jdbc/pull/35
[#36]: https://github.com/runax-software/runax-qdrant-jdbc/pull/36
[#39]: https://github.com/runax-software/runax-qdrant-jdbc/pull/39
[#40]: https://github.com/runax-software/runax-qdrant-jdbc/pull/40
[#42]: https://github.com/runax-software/runax-qdrant-jdbc/pull/42
[#43]: https://github.com/runax-software/runax-qdrant-jdbc/pull/43
[#44]: https://github.com/runax-software/runax-qdrant-jdbc/pull/44
[#45]: https://github.com/runax-software/runax-qdrant-jdbc/pull/45
[#46]: https://github.com/runax-software/runax-qdrant-jdbc/pull/46
[#48]: https://github.com/runax-software/runax-qdrant-jdbc/pull/48
[#49]: https://github.com/runax-software/runax-qdrant-jdbc/pull/49
