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
| `ORDER BY price DESC` | `order_by` with direction; needs a range index on `price`, and a limit, since Qdrant answers an ordered read in one page |
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
driver picked. `ASC` is an error too, since Qdrant returns the closest points first. The
query may also be `embed('text' [, 'provider:model'])`, described under
[Embeddings](#embeddings). The rest of this section, `OFFSET` included, is still the target;
a `WHERE` filters the points Qdrant ranks, as in a scroll.

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
- `embed('text' [, 'provider:model'])`: the text, embedded with the vector's model by Qdrant
  (`qdrant:`) or by a provider the driver calls (see [Embeddings](#embeddings));
- `image('url', 'model')`: Qdrant embeds an image (server-side inference).

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

**What runs today:** `SELECT COUNT(*) FROM docs [WHERE …]`, as one count call, exact,
answering one `BIGINT` column called `count` or whatever the statement calls it. Counting a
whole collection reads neither points nor columns, so a count of a collection that doesn't
exist is Qdrant's answer to the count rather than a check before it; a count with a `WHERE`
reads the columns the condition names. `LIMIT 0` returns no row and counts nothing, as it does
for a `SELECT` without `FROM`; a count is one row, so no other limit changes it.

`SELECT lang, COUNT(*) FROM docs [WHERE …] GROUP BY lang LIMIT 20` also runs, as one facet
call. The field needs an index Qdrant can count the values of — `keyword`, `integer`, `bool`,
or `uuid`, which the driver checks against the collection's indexes first — and the statement
needs a `LIMIT`, or its maximum rows: Qdrant counts the ten most common values when a request
gives none, and returns no cursor to read the rest, so a statement says how many groups it
wants. The groups come back most common first. A grouped statement selects the grouped field
and `COUNT(*)`, in either order and under any names, and one without the count reads the
field's values themselves, which is what `SELECT DISTINCT` asks for. An array field counts once
per element, so `GROUP BY tags` counts the points under each tag, and a group's type follows
the index rather than the column, so the groups of a JSON column of keywords are text.

`HAVING`, ordering the groups by anything but their count, grouping by more than one field, the
`exact=false` hint, and the rest of this section are still the target.

Other aggregates (`SUM`, `AVG`, …) are errors: Qdrant has no way to compute them. So are `COUNT`
of a column, `COUNT(DISTINCT …)`, and, outside a `GROUP BY`, `COUNT(*)` beside other select
items, which Qdrant's count API has no answer for.

## Writing points

Writes go through the batch update API, wait for Qdrant to apply them (`wait=true`), and
return the number of points written where Qdrant reports it.

**What runs today:** `DELETE FROM docs WHERE …`, with the filters `SELECT` takes, and
`TRUNCATE TABLE docs`. Qdrant's delete doesn't report how many points it removed, so the
driver counts the matching points first and returns that count; it's exact unless something
else writes to the collection between the two calls. A `DELETE` without `WHERE` is an error
that points to `TRUNCATE`, so a forgotten condition can't empty a collection. A read-only
connection (`setReadOnly(true)`) refuses every write with SQLState `25006`.

| SQL | Qdrant |
| --- | --- |
| `INSERT INTO docs (_id, dense, lang) VALUES (…)` | Upsert in `insert_only` mode |
| `INSERT … ON CONFLICT (_id) DO UPDATE …` | Upsert (`upsert` mode) |
| `… ON CONFLICT (_id) DO UPDATE … WHERE version < 3` | Upsert with `update_filter`: conditional update |
| `INSERT` without `_id` | The driver generates a UUID, returned by `getGeneratedKeys` |
| `INSERT … VALUES (…, embed('text'), …)` | Vectors embedded by the driver or by Qdrant |
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

**What runs today:** `CREATE TABLE [IF NOT EXISTS]` with `VECTOR(n)`, `MULTIVECTOR(n)`
(`max_sim`), and `SPARSE_VECTOR [MODIFIER IDF]` columns; `DISTANCE` is `COSINE` (the
default), `DOT`, `EUCLID`, or `MANHATTAN`. A column of a payload type followed by `INDEX`
becomes a payload index: `KEYWORD`, `INTEGER`, `FLOAT`, `BOOL`, `DATETIME`, `TEXT`, `UUID`,
`GEO`, or the SQL names `VARCHAR`, `BIGINT`, `DOUBLE`, `BOOLEAN`, `TIMESTAMP`. A payload column
without `INDEX` is an error, since the payload has no schema to declare it in. Qdrant creates
the collection and each index in separate calls, so when an index fails the driver deletes the
new collection again rather than leave half a table. `WITH (…)`, `ALTER TABLE`, and
`CREATE INDEX` are still the target.

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
| `DROP TABLE docs`, `DROP TABLE IF EXISTS docs` | Delete collection (runs today) |

## Aliases

| SQL | Qdrant |
| --- | --- |
| `CREATE VIEW current_docs AS SELECT * FROM docs_v2` | Create alias (only this exact form) |
| `CREATE OR REPLACE VIEW current_docs AS SELECT * FROM docs_v3` | Switch the alias in one atomic change |
| `ALTER VIEW current_docs RENAME TO docs_live` | Rename alias |
| `DROP VIEW current_docs` | Delete alias |

Aliases are listed as views in `DatabaseMetaData`, with the target collection's columns.

## Embeddings

`embed('text', 'provider:model')` turns text into a vector. The model is written as who runs
it, a colon, and the model's own name, unchanged: `openai:text-embedding-3-small`,
`voyage:voyage-3`, `qdrant:qdrant/bm25`, `qdrant:sentence-transformers/all-minilm-l6-v2`.

| Provider | Who embeds | Settings |
| --- | --- | --- |
| `qdrant` | Qdrant, through the Query API's document query: any kind of vector the model makes | None: every Qdrant runs `qdrant/bm25`; hosted models need Qdrant Cloud's inference |
| `openai` | The driver, through `POST {openaiUrl}/embeddings` | `openaiApiKey`; `openaiUrl` (default `https://api.openai.com/v1`) for Azure OpenAI or a proxy |
| `voyage` | The driver, through `POST {voyageUrl}/embeddings`, as a query | `voyageApiKey`; `voyageUrl` (default `https://api.voyageai.com/v1`) |

The driver decides **which model** a vector uses. It keeps that in the collection's metadata,
under `runax.embed.<vector>` (`runax.embed._vector` for the unnamed one), so every client
embeds the same vector with the same model:

```sql
ALTER TABLE docs SET (metadata = '{"runax.embed.dense": "openai:text-embedding-3-small"}');
```

**What runs today:** `embed()` as the query of `similarity(…)`. With a model kept for the
vector, `embed('text')` needs none, and naming a different one is an error: the search would
run, but against vectors made by another model, and find the wrong points. With no model kept,
`embed('text')` without one is an error that says to name it. The metadata is read with the
collection's columns; setting it from SQL (the `ALTER TABLE` above) isn't supported yet, so
it's set with Qdrant's update-collection API. Providers the driver calls make dense vectors,
whose dimensions the driver checks against the vector before it searches; a `qdrant:` model's
are Qdrant's to check. Provider API keys come from connection settings, never from SQL or
collection metadata, and are never logged. A Qdrant without an inference service answers a
hosted `qdrant:` model with an error that says so.

Still the target, for each `embed()`:

- **the text's role**: a *query* inside `similarity()`, `recommend()`, and the other ranking
  functions, a *document* inside `INSERT` and `UPDATE` (some models embed the two
  differently); Voyage AI is already asked for a query;
- **many texts at once**: an `INSERT` of many rows, or a JDBC batch, is one call;
- **more providers**, and sparse and multivector output from the providers the driver calls.

`image()` doesn't use the embedder: Qdrant embeds the input itself.

## Prepared statements

**What runs today:** `Connection.prepareStatement` parses the SQL once and reports syntax errors
then; unknown collections and columns are reported when it runs, since the columns may change in
between. A `?` stands for one constant wherever a constant goes: in `WHERE`, in the select list,
and as the vector of `similarity(…)`. A value takes the placeholder's place in the parsed
statement, never in the SQL text, so it can't change what the statement means.

| Java value | Constant |
| --- | --- |
| `String`, `UUID`, `Character` | Text |
| `Integer`, `Long`, `Short`, `Byte`, `BigInteger`, a whole `BigDecimal` | Whole number |
| `Double`, `Float`, any other `BigDecimal` | Decimal number |
| `Boolean` | Boolean |
| `Timestamp`, `Instant`, `OffsetDateTime`, `ZonedDateTime`, `java.util.Date` | RFC 3339 text in UTC, for date-time fields |
| `java.sql.Date`, `LocalDate` | The start of that day in UTC |
| `float[]`, `double[]`, `Number[]`, a `List` of numbers | A vector |
| `setNull` | `NULL`, which a comparison rejects: write `IS NULL` instead |

`addBatch()` and `executeBatch()` run the statement once per set of values, in order; Qdrant has
no transactions, so when one fails, the sets before it stay applied and the
`BatchUpdateException` reports their counts. `getParameterMetaData()` reports how many
placeholders there are, each of type `OTHER`, since a payload field's type comes from its
points; `getMetaData()` returns `null` until the statement runs. `LIMIT ?` and `OFFSET ?` aren't
supported yet.

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
| `system.snapshots` | `GET /snapshots`, then `GET /collections/{name}/snapshots` for each | `collection_name` (null for a whole-storage snapshot), `name`, `creation_time`, `size`, `checksum` |
| `system.shard_keys` | `GET /collections/{name}/shards` for each | `collection_name`, `shard_key` |
| `system.shards` | `GET /collections/{name}/cluster` for each | `collection_name`, `shard_id`, `shard_key`, `placement` (`local`/`remote`), `peer_id`, `state`, `points_count` |
| `system.cluster` | `GET /cluster` | `status`, `peer_id`, `peer_count`, `raft_term`, `raft_commit`, `raft_pending_operations`, `raft_leader`, `raft_role`, `raft_is_voter`, `consensus_thread_status`, `consensus_thread_updated`, `consensus_thread_error`, `message_send_failures` |
| `system.peers` | `GET /cluster` | `peer_id`, `uri`, `is_this_peer`, `is_leader` |
| `system.telemetry` | `GET /telemetry` | `path`, `value`, `value_type` |
| `system.metrics` | `GET /metrics` | `name`, `labels`, `value`, `type`, `help` |
| `system.issues` | `GET /issues` | `id`, `description`, `related_collection`, `timestamp`, `solution` |

```sql
SELECT name, status, points_count FROM system.collections;
SELECT alias_name, collection_name FROM qdrant.system.aliases;
SELECT collection_name, name, size FROM system.snapshots;
SELECT value FROM system.telemetry;        -- one row per value, by path
SELECT name, labels, value FROM system.metrics;
```

Values Qdrant leaves out of its answer, such as the counts of a collection that isn't ready,
are null. Fields whose shape depends on the Qdrant version or on how the collection was
created, such as a collection's vector parameters, are JSON columns holding what Qdrant sent
rather than a column per field. A collection name is matched exactly, as Qdrant matches it,
but the driver's own names — the catalog, the two schemas, and the system tables and their
columns — are matched whatever the case.

`system.collections`, `system.snapshots`, `system.shard_keys`, and `system.shards` read one
collection at a time, because Qdrant describes a collection only one at a time: each costs
one call per collection, and a collection deleted between the listing and its read is left
out rather than failing the statement. Nothing read here is cached; every statement sees the
server as it is now. Rows are sorted by the driver, by name or id, so what a statement
returns doesn't depend on the order Qdrant answers in.

`system.cluster` is always one row: a standalone server answers `GET /cluster` with nothing
but a status of `disabled`, which leaves the rest of the row null, and then `system.peers`
and `system.shard_keys` have no rows — Qdrant only lets a shard key be created when it runs
as a cluster. A shard key still shows up per shard in `system.shards`, which is where Qdrant
reports it.

Two of these tables aren't tabular at Qdrant's end and are projected rather than mapped:

- `system.telemetry` is a tree whose shape depends on the Qdrant version, so each row is one
  leaf of it: `path` names where the value sits, as dotted keys with `[n]` for a list's
  places, `value` holds it as text, and `value_type` says whether it was a string, a number,
  a boolean, or null. An object or list with nothing in it is a row of its own, so no path
  Qdrant sent is missing. This reads the telemetry Qdrant answers with by default; its
  deeper `?details_level=` levels aren't exposed.
- `system.metrics` comes from `GET /metrics`, which answers in Prometheus' text format
  rather than JSON, so the driver reads the text itself: each row is one sample, with its
  labels as a JSON object and the `type` and `help` Qdrant declared for the metric. A
  histogram's `_bucket`, `_sum`, and `_count` samples take the type and help declared for
  the family they belong to. A line the driver can't read is left out rather than failing
  the statement.

`system.issues` reports what Qdrant collected about itself, such as an `UNINDEXED_FIELD`
suggestion; `solution` is the JSON Qdrant sent, which is a variant whose shape is its own
business. The read-only `CALL` procedures below move to these tables as they land.

## Operations

| SQL | Qdrant |
| --- | --- |
| `CALL qdrant.create_snapshot('docs')`, `delete_snapshot` | Collection snapshots (listing them is `system.snapshots`) |
| `CALL qdrant.create_full_snapshot()`, `delete_full_snapshot` | Full snapshots (listing them is `system.snapshots` too) |
| `CALL qdrant.create_shard_key('docs', 'eu')`, `delete_shard_key` | Shard keys (listing them is `system.shard_keys`) |
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
