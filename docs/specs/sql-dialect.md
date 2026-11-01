# SQL for Qdrant — dialect spec

**Status:** draft. Today the driver only describes collections through `DatabaseMetaData`;
this spec is the target for everything else. It maps each part of Qdrant 1.20's API to the
SQL the driver will accept, so the [roadmap](../../ROADMAP.md) can be built against one plan.

## Principles

1. **Standard SQL where it means the same thing.** `WHERE`, `ORDER BY`, `LIMIT`, `COUNT`,
   `INSERT`, `UPDATE`, `DELETE`, `CREATE TABLE`, `CREATE INDEX`, and views keep their usual
   meaning.
2. **Functions for what SQL has no word for**: vector similarity, full-text matching, geo
   conditions, server-side embedding. They go where SQL allows functions, so any SQL parser
   and any JDBC tool can handle them.
3. **`CALL qdrant.<procedure>(...)` for operations that aren't about rows**: snapshots, shard
   keys, cluster changes.
4. **Request options** (consistency, shard key, search precision) come from
   `SET qdrant.<option> = <value>` for the connection, or a `/*+ option=value */` hint for
   one statement.
5. **No hidden full scans.** SQL that Qdrant can't run fails with an
   `SQLFeatureNotSupportedException` that says why. Small, bounded work on results Qdrant
   already returned (sorting facet counts, `HAVING`) is fine.

## Data model

| Qdrant | SQL |
| --- | --- |
| Collection | Table |
| Alias | View |
| Point | Row |
| Point ID (unsigned 64-bit integer or UUID) | `_id` column, primary key |
| Payload field (`name`, `id`, nested `specs.color`) | Column (`name`, `id`, `"specs.color"`, or `specs.color` where no table is named that) |
| Whole payload | `_payload` column (JSON) |
| Unnamed vector / named vector | `_vector` column / column named after the vector |
| Score of a search | `_score` column |
| Payload index | Index |
| Collection metadata | Table comment and driver settings (see [Embeddings](#embeddings)) |
| Server and collection state | Read-only tables in the `system` schema (see [System tables](#system-tables)) |

Column types follow the payload index or the sampled values, as the driver already reports
them: `VARCHAR`, `BIGINT`, `DOUBLE`, `BOOLEAN`, `TIMESTAMP WITH TIME ZONE`, `JSON`, `VECTOR`,
`MULTIVECTOR`, `SPARSE_VECTOR`.

### Reserved names

Every payload field is a column under its own name, `id` included. The driver's own columns
start with `_`: `_id`, `_payload`, `_vector`, `_score`, `_group`, and `_lookup`, and any it
adds later will too. A payload field whose name collides with one of those (such as
MongoDB's `_id`), or with a vector's name, gets no column of its own; it stays reachable
through the payload, as `_payload->>'_id'`. Other payload fields that start with `_` are
ordinary columns.

### Qualified names

A statement may name the table it reads and name its columns under it, as tools generate:
`SELECT t.* FROM collections.docs t`. A name given to the table in FROM replaces the table's
own, as SQL does, so `docs.name` is an error once the statement says `FROM docs t`.

A nested payload field is one column whose name holds a dot, `"specs.color"`. Written without
quotes, `specs.color` reads as that column too, as long as the statement names no table
`specs`: the name is looked up whole when its first part names nothing in FROM. A qualifier
that names neither the table nor a field of it is an error, `42S02`, naming the table the
statement does read.

### JSON values

Objects and arrays use PostgreSQL's JSON operators and functions:

| SQL | Meaning |
| --- | --- |
| `meta->>'author'`, `meta->'address'->>'city'` | A nested value, as text or JSON (same as the `"meta.author"` column) |
| `_payload->>'_id'` | Any payload key, including one named like a reserved column |
| `(v->>'stock')::int` | A JSON value read as a number, for comparisons |
| `tags @> '["office"]'` | The array contains every listed element |
| `jsonb_array_length(tags)` | The number of elements in the array |
| `jsonb_array_elements(variants)` | The elements of an array of objects, in `EXISTS` subqueries |

## Reading points

| SQL | Qdrant |
| --- | --- |
| `SELECT … FROM docs` | Scroll, paged lazily with the scroll cursor |
| `FROM docs t`, `FROM docs AS t` | A name for the table, which its columns may be named under |
| `SELECT t.*`, `SELECT t.name`, `WHERE t.lang = 'en'` | The same columns, named under the table or that name |
| `SELECT name, price …` | `with_payload` limited to the named fields |
| Vector column in the select list | `with_vectors` for that vector only; `*` returns no vectors |
| `WHERE _id = 7`, `WHERE _id IN (7, '5c56…')` | Retrieve by ID |
| `LIMIT n` | `limit` |
| `OFFSET n` | Query API `offset` for searches; for plain reads, skip `n` points with the cursor |
| `ORDER BY _id` | Scroll order (the default) |
| `ORDER BY price DESC` | `order_by` with direction; needs a range index on `price` |
| `ORDER BY RANDOM()` | Query `sample: random` |
| `SELECT MIN(price)`, `MAX(price)` | `order_by` on `price` with `limit 1`; needs a range index |
| `SELECT DISTINCT lang` | Facet on `lang` |

## Filters (`WHERE`)

Every condition compares a column with constants; comparing two columns, or applying other
functions to a column, is an error.

**What runs today:** `AND`, `OR`, `NOT`; `=`, `<>`, `!=`, `<`, `<=`, `>`, `>=`; `IN` and
`NOT IN`; `IS NULL` and `IS NOT NULL`, on payload fields, `_id`, and named vectors, in a scroll
and in a search. A column comes first and a constant second. Text, whole numbers, and booleans
are matched exactly; a decimal number, a number in a `DOUBLE` field, and a date-time are a
range from the value to itself, which is how Qdrant compares those. A comparison is never true
for a point whose field is missing or null, and neither is its negation, as in SQL:
`lang <> 'en'` and `NOT (price > 5)` match only points that have the field, so the driver
adds `is_empty` to each negated condition. `LIKE`, `BETWEEN`, `at_least`, the text, geo, and
array functions, and `EXISTS` are still the target.

| SQL | Qdrant condition |
| --- | --- |
| `AND`, `OR`, `NOT` | `must`, `should`, `must_not` |
| `at_least(2, cond1, cond2, cond3)` | `min_should` |
| `lang = 'en'`, `year = 2024`, `active = true` | `match` value |
| `lang IN ('en', 'de')` / `NOT IN` | `match` any / except |
| `<`, `<=`, `>`, `>=`, `BETWEEN` on numbers | `range` |
| … on `TIMESTAMP WITH TIME ZONE` columns | `datetime_range` |
| `name LIKE 'Desk%'` | `match` prefix |
| `name LIKE '%lamp%'` | `match` substring |
| `text_match(title, 'refund policy')` | `match` text: every word, through the full-text index |
| `text_any(title, 'refund return')` | `match` text_any: any word |
| `phrase_match(title, 'refund policy')` | `match` phrase; needs `phrase_matching` on the text index |
| `tags @> '["office"]'` | `match` on an array field: any element equals (one condition per listed element) |
| `jsonb_array_length(tags) >= 2` | `values_count` |
| `field IS NULL` / `IS NOT NULL` | `is_empty`: missing, `null`, or `[]` |
| `is_json_null(field)` | `is_null`: present and `null` |
| `dense IS NOT NULL` (vector column) | `has_vector` |
| `_id IN (…)`, `_id NOT IN (…)` | `has_id` |
| `meta->>'author' = 'Ann'` | `match` on the path `meta.author` |
| `EXISTS (SELECT 1 FROM jsonb_array_elements(variants) AS v WHERE v->>'sku' = 'A1' AND (v->>'stock')::int > 0)` | `nested`: every condition on the same array element |
| `geo_radius(location, lat, lon, meters)` | `geo_radius` |
| `geo_box(location, top, left, bottom, right)` | `geo_bounding_box` |
| `geo_polygon(location, 'POLYGON((…))')` | `geo_polygon`, with holes, from WKT |
| `slice(i, n)` | `slice`: part *i* of *n*, for reading a collection in parallel |

## Ranking and vector search (`ORDER BY`)

A search is a `SELECT` ordered by a ranking function. It runs one Query API call, with the
`WHERE` filter, `LIMIT`, and `OFFSET`, and returns the score as `_score`.

**What runs today:** `similarity(vector, [0.1, ...]) DESC` over a dense vector, with `LIMIT`.
The vector to search is a vector column of the collection — its own name for a named vector,
`_vector` for a collection's single unnamed one — and its dimensions are checked against the
vector in the statement before the search runs. `_score` is a `DOUBLE` column that only a
search has, and `*` doesn't include it. A search says how many points to rank, through `LIMIT`
or the statement's maximum rows: Qdrant ranks the points nearest the vector rather than
reading the whole collection, so a search without either is an error rather than a number the
driver picked. `ASC` is an error too, since Qdrant returns the closest points first. The rest
of this section, `OFFSET` included, is still the target; a `WHERE` filters the points
Qdrant ranks, as in a scroll.

```sql
SELECT _id, _score, title
FROM docs
WHERE lang = 'en'
ORDER BY similarity(dense, embed('refund policy')) DESC
LIMIT 10
```

| SQL | Qdrant query |
| --- | --- |
| `similarity(v, query)` | `nearest`, `using` vector *v* |
| `WHERE similarity(v, query) >= 0.8` | `score_threshold` (only in the direction the distance allows) |
| `mmr(similarity(v, query), diversity [, candidates])` | `nearest` with MMR, for diverse results |
| `recommend(v, positive, negative [, strategy])` | `recommend`; strategy `average_vector`, `best_score`, or `sum_scores` |
| `discover(v, target, context)` | `discover` with target and context pairs |
| `context(v, context)` | `context` |
| `feedback(v, target, feedback [, a, b, c])` | `relevance_feedback` with the naive strategy |
| `text_rank(field, 'query')` | `text` query: full-text relevance from a text index with scoring |

The *query* argument can be:

- a vector literal: `'[0.1, 0.2, …]'`, `'{"indices": [1, 7], "values": [0.5, 0.9]}'` for
  sparse, `'[[…], […]]'` for multivectors;
- a point ID: `similarity(dense, 42)` searches with point 42's vector; `ref('other', 42)`
  takes it from another collection (`lookup_from`);
- `embed('text')`: the driver embeds the text with the vector's model, through the
  embedder abstraction (see [Embeddings](#embeddings));
- `document('text', 'model')` or `image('url', 'model')`: Qdrant embeds it (server-side
  inference).

`recommend`, `discover`, `context`, and `feedback` take point IDs and vectors in the same
forms, as `ARRAY[…]` or JSON.

### Combining searches

A subquery in `FROM` with its own `ORDER BY … LIMIT` is a **prefetch**: Qdrant runs it first
and the outer query ranks its results. Fusion and formulas rank several prefetches at once.

```sql
-- Hybrid search: dense and sparse candidates, fused with reciprocal rank fusion
SELECT _id, _score, title
FROM docs
ORDER BY rrf(similarity(dense, embed('refund policy')),
             similarity(keywords, embed('refund policy'))) DESC
LIMIT 10;

-- Two stages: 100 sparse candidates, reranked with the multivector
SELECT _id, _score
FROM (SELECT * FROM docs ORDER BY similarity(keywords, embed('refund')) DESC LIMIT 100)
ORDER BY similarity(colbert, embed('refund')) DESC
LIMIT 10;

-- Score boosting with a formula
SELECT _id, _score
FROM docs
ORDER BY similarity(dense, embed('desk lamp')) * 0.8
         + CASE WHEN in_stock THEN 0.2 ELSE 0 END
         - 0.1 * exp_decay(price, 50, 100, 0.5) DESC
LIMIT 10;
```

| SQL | Qdrant |
| --- | --- |
| Subquery with `ORDER BY … LIMIT` in `FROM` | `prefetch` (nests to any depth) |
| `rrf(s1, s2, …)`, `rrf(k, ARRAY[weights], s1, s2, …)` | `fusion: rrf`, or `rrf` with `k` and weights |
| `dbsf(s1, s2, …)` | `fusion: dbsf` |
| Arithmetic in `ORDER BY`: `+ - * /`, unary `-`, `ABS`, `SQRT`, `POWER`, `EXP`, `LN`, `LOG10`, `GREATEST`, `LEAST` | `formula`; each `similarity(…)` becomes a prefetch, read as `$score[i]` |
| Payload columns in the formula | Formula variables, with `COALESCE(price, 0)` as the default |
| `CASE WHEN <condition> THEN x ELSE y END` | Formula condition |
| `exp_decay`, `gauss_decay`, `lin_decay(x, target, scale, midpoint)` | Decay expressions |
| `geo_distance(location, lat, lon)` | Geo distance expression |
| `TIMESTAMP '2024-01-01T00:00:00Z'` and datetime columns | Datetime expressions |

## Counting and grouping

| SQL | Qdrant |
| --- | --- |
| `SELECT COUNT(*) FROM docs WHERE …` | Count, exact unless `/*+ exact=false */` |
| `SELECT lang, COUNT(*) FROM docs GROUP BY lang` | Facet on `lang` (keyword, integer, UUID, or bool index), sorted by count |
| `… HAVING COUNT(*) > 5`, other `ORDER BY` | Applied to the facet results |
| `SELECT * FROM query_groups(chunks, doc_id, 3, <ranking>)` | Query groups: the best 3 points for each `doc_id`; `LIMIT` counts groups |
| `query_groups(…, lookup => 'docs')` | `with_lookup`: adds each group's record from `docs` as `_lookup` |
| `SELECT * FROM distance_matrix(docs, dense, 100, 5)` | Search matrix pairs: 100 sampled points, their 5 nearest neighbours, as rows `(_a, _b, _score)` |

**What runs today:** `SELECT COUNT(*) FROM docs`, as one count call, exact, answering one
`BIGINT` column called `count` or whatever the statement calls it. Counting reads neither points
nor columns, so a count of a collection that doesn't exist is Qdrant's answer to the count rather
than a check before it. `LIMIT 0` returns no row and counts nothing, as it does for a `SELECT`
without `FROM`; a count is one row, so no other limit changes it. A count with a `WHERE`, the
`exact=false` hint, and the rest of this section are still the target.

Other aggregates (`SUM`, `AVG`, …) are errors: Qdrant has no way to compute them. So are `COUNT`
of a column, `COUNT(DISTINCT …)`, and `COUNT(*)` beside other select items, which Qdrant's count
API has no answer for.

## Writing points

Writes go through the batch update API, wait for Qdrant to apply them (`wait=true`), and
return the number of points written where Qdrant reports it.

| SQL | Qdrant |
| --- | --- |
| `INSERT INTO docs (_id, dense, lang) VALUES (…)` | Upsert in `insert_only` mode |
| `INSERT … ON CONFLICT (_id) DO UPDATE …` | Upsert (`upsert` mode) |
| `… ON CONFLICT (_id) DO UPDATE … WHERE version < 3` | Upsert with `update_filter`: conditional update |
| `INSERT` without `_id` | The driver generates a UUID, returned by `getGeneratedKeys` |
| `INSERT … VALUES (…, embed('text'), …)`, `document(…)` | Vectors embedded by the driver or by Qdrant |
| `UPDATE docs SET lang = 'de' WHERE …` | `set_payload` by filter |
| `UPDATE … SET "meta.author" = 'Ann'` | `set_payload` with `key` (nested path) |
| `UPDATE … SET _payload = '{…}'` | `overwrite_payload` |
| `UPDATE … SET _payload = _payload \|\| '{"lang": "de"}'` | `set_payload`: merge the given fields |
| `UPDATE … SET lang = NULL`, `SET _payload = _payload - 'lang'` | `delete_payload` for `lang` |
| `UPDATE … SET dense = '[…]'` | `update_vectors` |
| `UPDATE … SET dense = NULL` | `delete_vectors` |
| `DELETE FROM docs WHERE …` | Delete points by filter (or by ID) |
| `TRUNCATE TABLE docs` | Delete all points; the collection and its indexes stay |
| JDBC batches (`addBatch` / `executeBatch`) | One batch update call |

## Collections, vectors, and indexes (DDL)

```sql
CREATE TABLE docs (
    dense    VECTOR(384) DISTANCE COSINE,
    colbert  MULTIVECTOR(128) DISTANCE COSINE,
    keywords SPARSE_VECTOR MODIFIER IDF,
    lang     KEYWORD INDEX,
    year     INTEGER INDEX
) WITH (shard_number = 2, replication_factor = 2, on_disk_payload = true);

CREATE INDEX ON docs (title) USING text WITH (tokenizer = 'word', phrase_matching = true);
```

| SQL | Qdrant |
| --- | --- |
| `CREATE TABLE` with vector columns | Create collection; a single column named `_vector` makes the unnamed vector |
| Payload columns with `INDEX` in `CREATE TABLE` | Payload indexes, created after the collection |
| `WITH (…)` | Collection settings: shards, replication, HNSW, optimizers, quantization, strict mode, sharding method, metadata |
| `ALTER TABLE docs SET (…)` | Update collection |
| `ALTER TABLE docs ADD COLUMN image VECTOR(512) DISTANCE COSINE` | Add a named vector |
| `ALTER TABLE docs DROP COLUMN image` | Delete a named vector |
| `CREATE INDEX ON docs (lang) [USING keyword] [WITH (is_tenant = true, …)]` | Create payload index; the type defaults from the column type |
| `DROP INDEX lang_idx ON docs` | Delete payload index |
| `COMMENT ON TABLE docs IS '…'` | Collection metadata `description` |
| `DROP TABLE docs` | Delete collection |

## Aliases

| SQL | Qdrant |
| --- | --- |
| `CREATE VIEW current_docs AS SELECT * FROM docs_v2` | Create alias (only this exact form) |
| `CREATE OR REPLACE VIEW current_docs AS SELECT * FROM docs_v3` | Switch the alias in one atomic change |
| `ALTER VIEW current_docs RENAME TO docs_live` | Rename alias |
| `DROP VIEW current_docs` | Delete alias |

Aliases are listed as views in `DatabaseMetaData`, with the target collection's columns.

## Embeddings

Embedding providers (OpenAI and others) come from a separate embedder abstraction, built
outside this spec. The driver only uses it; this section is the contract the driver needs.

The driver decides **which model** a vector uses. It keeps that in the collection's metadata,
so every client embeds the same vector with the same model:

```sql
ALTER TABLE docs SET (metadata = '{"runax.embed.dense": "<model id>"}');
```

The model ID's format belongs to the embedder abstraction. Provider API keys come from
connection settings and never go into collection metadata.

For each `embed()` the driver asks the embedder for:

- **the vector's model**, by the model ID above;
- **the text's role**: a *query* inside `similarity()`, `recommend()`, and the other ranking
  functions, a *document* inside `INSERT` and `UPDATE` (some models embed the two
  differently);
- **many texts at once**: an `INSERT` of many rows, or a JDBC batch, is one call;
- **the vector's kind**: dense, sparse (`{indices, values}`), or multivector, checked against
  the vector's size from the collection.

A failure in the embedder fails the statement with an `SQLException` that names the model.
`document()` and `image()` don't use the embedder: Qdrant embeds the input itself.

## Request options

| Option | Qdrant parameter |
| --- | --- |
| `consistency` | Read consistency: a number, `majority`, `quorum`, or `all` |
| `ordering` | Write ordering: `weak`, `medium`, or `strong` |
| `wait` | Wait for writes to apply (default `true`) |
| `shard_key` | Shard key selector, for collections with custom sharding |
| `timeout` | Server-side timeout, in seconds |
| `hnsw_ef`, `exact`, `indexed_only` | Search parameters |
| `quantization_rescore`, `quantization_oversampling` | Quantization search parameters |
| `exact` (on counts and facets) | Exact or approximate counting |

```sql
SET qdrant.consistency = 'majority';                    -- for the rest of the connection
SELECT /*+ hnsw_ef=256 shard_key='eu' */ _id, _score    -- for one statement
FROM docs ORDER BY similarity(dense, embed('refund')) DESC LIMIT 10;
```

## System tables

Read-only tables that describe the server rather than any collection's points. They sit in
the `system` schema, next to `collections`, the schema the collections themselves are in, so
their names can't clash with a collection's. `DatabaseMetaData` lists them as
`SYSTEM TABLE`s, with fixed columns, no primary key, and no indexes.

| Table | Qdrant | Columns |
| --- | --- | --- |
| `system.collections` | `GET /collections`, then `GET /collections/{name}` for each | `name`, `status`, `optimizer_status`, `points_count`, `indexed_vectors_count`, `segments_count`, `shard_number`, `replication_factor`, `write_consistency_factor`, `on_disk_payload`, `vectors`, `sparse_vectors`, `payload_indexes` |
| `system.aliases` | `GET /aliases` | `alias_name`, `collection_name` |

```sql
SELECT name, status, points_count FROM system.collections;
SELECT alias_name, collection_name FROM qdrant.system.aliases;
```

Values Qdrant leaves out of its answer, such as the counts of a collection that isn't ready,
are null. Fields whose shape depends on the Qdrant version or on how the collection was
created, such as a collection's vector parameters, are JSON columns holding what Qdrant sent
rather than a column per field. A collection name is matched exactly, as Qdrant matches it,
but the driver's own names — the catalog, the two schemas, and the system tables and their
columns — are matched whatever the case.

`system.collections` reads one collection at a time, because Qdrant describes them only one
at a time: it costs one call per collection. Nothing read here is cached; every statement
sees the server as it is now.

Planned tables, which each describe state Qdrant already exposes: snapshots, shard keys,
cluster info and peers, telemetry, metrics, and issues. The read-only `CALL` procedures
below move to these tables as they land. `GET /metrics` answers in Prometheus' text format
rather than JSON, so it needs more than a new table.

## Operations

| SQL | Qdrant |
| --- | --- |
| `CALL qdrant.create_snapshot('docs')`, `list_snapshots`, `delete_snapshot` | Collection snapshots |
| `CALL qdrant.create_full_snapshot()`, `list_full_snapshots`, `delete_full_snapshot` | Full snapshots |
| `CALL qdrant.create_shard_key('docs', 'eu')`, `delete_shard_key`, `list_shard_keys` | Shard keys |
| `CALL qdrant.cluster_info('docs')` | Collection cluster info, as rows |
| `CALL qdrant.move_shard(…)`, `replicate_shard(…)`, `abort_shard_transfer(…)` | Cluster setup changes |
| `CALL qdrant.collection_info('docs')` | Status, optimizer state, segment and point counts, as rows |

The procedures are listed by `DatabaseMetaData.getProcedures`, so DataGrip shows them.

## Not supported

These are errors, not emulated:

- `JOIN`, except the group lookup above; `UNION`; subqueries other than the prefetch form and
  `EXISTS` over `jsonb_array_elements`.
- Aggregates other than `COUNT`, `MIN`, and `MAX`; `GROUP BY` without a facet-able field.
- Conditions that compare two columns, or apply functions to a column other than those
  listed here: Qdrant's filters only compare one field with constants. Checking them in the
  driver after Qdrant answers is a possible later feature (see the roadmap).
- `LIKE` patterns other than prefix (`'abc%'`) and substring (`'%abc%'`), and `ILIKE`. Qdrant
  has no suffix, wildcard, or case-insensitive substring match, and the driver doesn't filter
  rows itself, so `'%lamp'` is an error rather than a broader `'%lamp%'` search trimmed in
  the driver. The error suggests `'%lamp%'`.
- Transactions and savepoints: every statement applies on its own, because Qdrant has no
  transactions. An opt-in write buffer that holds writes until `commit()` may come later
  (see the roadmap); it still wouldn't make commits atomic.
- Server internals: telemetry (`/telemetry`), Prometheus metrics (`/metrics`), cluster and
  Raft consensus state (`/cluster`), and Qdrant's performance hints (`/issues`). The driver
  doesn't read them yet; use Qdrant's REST API directly or a monitoring tool such as
  Prometheus. They're planned as [system tables](#system-tables), where collections and
  aliases already are.

## Open questions

- How the driver gets the embedder abstraction (a library dependency, or a plug-in found at
  runtime), and whether its interface covers everything in [Embeddings](#embeddings):
  query/document role, batches, and sparse and multivector output.
- Does `insert_only` skip an existing point or reject it, and what does Qdrant report? That
  decides whether `INSERT` of an existing ID is an error.
- Which field does a `text` query search, and what scoring options does it take?
- Do all these forms parse with JSqlParser 5.4: types with arguments and `DISTANCE` in
  `CREATE TABLE`, table functions in `FROM`, `ON CONFLICT … WHERE`, and the PostgreSQL
  operators `->`, `->>`, `::`, `@>`, `||` on JSON? Hints are read from the SQL text before
  parsing.
- Should `IS NULL` match empty arrays (Qdrant's `is_empty`), or should arrays use
  `jsonb_array_length(tags) = 0` and `IS NULL` mean missing or `null` only?
