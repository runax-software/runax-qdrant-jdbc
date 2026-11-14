# SQL for Qdrant — dialect spec

**Status:** part built, part target. This spec maps each part of Qdrant 1.20's API to the SQL
the driver accepts, so the [roadmap](../../ROADMAP.md) can be built against one plan. A good
deal of it runs today — reads with filters, patterns and full-text matching, similarity search,
`COUNT(*)` and `GROUP BY`, the write statements, and DDL for collections, indexes and views —
and the rest is still the target. Each section says which of its parts run, under
**What runs today**; for the measured version of the same question, see
[docs/coverage.md](../coverage.md).

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
| `OFFSET n` | Query API `offset` for searches; for plain reads, skip `n` points with the cursor; for ordered reads and facets, over-fetch `limit + n` and drop the first `n` |
| `ORDER BY _id` | Scroll order (the default) |
| `ORDER BY price DESC` | `order_by` with direction; needs a range index on `price`, and a limit from the statement or `defaultLimit`, since Qdrant answers an ordered read in one page |
| `ORDER BY RANDOM()` | Query `sample: random` |
| `SELECT MIN(price)`, `MAX(price)` | `order_by` on `price` with `limit 1`; needs a range index |
| `SELECT DISTINCT lang` | Facet on `lang` |

### `OFFSET`

`OFFSET n` runs on every kind of read, which is what lets a data grid page past its first
screen. Qdrant offers no one way to do it, so each kind reaches it differently and only one of
the four is the server's own work:

| Read | How the offset is applied | What it costs |
| --- | --- | --- |
| Similarity search | Qdrant's Query API `offset`, which is a row count, sent as written | Nothing: the server skips |
| Plain read | The driver pages with the scroll cursor and discards `n` points — Qdrant's scroll `offset` is the point ID to start at, not a row count | `n` points read and dropped |
| Ordered read | The driver asks for `limit + n` in the single page Qdrant answers with and drops the first `n`; Qdrant rejects an `offset` beside an `order_by` outright | `n` extra points in one response |
| `GROUP BY` | The driver facets `limit + n` values and drops the first `n`; the facet API has no `offset` field and *ignores* one rather than reporting it | `n` extra groups counted |
| `COUNT(*)` | One row, so any `OFFSET` skips past it: no row, and nothing counted | Nothing: no call |
| System table | The rows are already in memory, so the read starts further along them | Nothing |

Where the driver does the skipping it pays for rows no statement returns, so an offset there is
capped at **10,000**; past that the statement is an error naming the cap and suggesting a
`WHERE`, rather than a read of a whole collection dressed up as a read of one screen. A search's
offset and a system table's cost the driver nothing, so neither is capped.

`OFFSET` with no `LIMIT` is fine for a plain read, which pages lazily, and for a system table.
An ordered read, a search, and a `GROUP BY` are each bounded by a `LIMIT`, the statement's
maximum rows, or the connection's `defaultLimit`, so `OFFSET` there always composes with a
limit — and the over-fetch is that limit plus the offset, whichever of the three supplied it.
Because a limit bounds the rows *returned*, a defaulted one still means what its warning says
once an offset skips ahead of them. As JDBC specifies, `setMaxRows` bounds the rows the *result
set* yields, so it counts the rows after the skip, never against it; `setFetchSize` still sets
the page a plain read is skipped through.

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
adds `is_empty` to each negated condition.

`LIKE` and `NOT LIKE` run too, as do the three full-text conditions. `'Desk%'` is a `prefix`
match and `'%desk%'` a `substring` match, both case-sensitive byte-wise, as Qdrant's exact
keyword match is, so `'%desk%'` and `'%Desk%'` find different points; a pattern with no
wildcard is the exact match `=` sends. `text_match(title, 'words')` matches every word,
`text_any` any one of them, and `phrase_match` the words in order. Each of them reaches every
element of an array field, matching the point when one element does, and each runs on a field
with no payload index — Qdrant reads the payload instead, as it does for any other unindexed
condition. A pattern applies to the text Qdrant stores, so a `TIMESTAMP WITH TIME ZONE` column
takes one: `created_at LIKE '2024-03%'`.

Two rules are worth stating because Qdrant answers rather than fails without them. A pattern
Qdrant has no match for is an error naming what to write instead, not a broader search trimmed
in the driver: `'%lamp'` has no suffix match and the error points at `'%lamp%'`, a `%` in the
middle of a pattern asks for neither of the two matches, `_` isn't a single-character wildcard
(and no `ESCAPE` clause is read, so a literal `%` or `_` can't be written), `'%'` and `'%%'`
ask for every value and point at `IS NOT NULL`, and a pattern against an `INTEGER`, `DOUBLE` or
`BOOLEAN` column is an error since Qdrant compares those rather than matching them. And a
phrase needs a text index built with `phrase_matching`: without one Qdrant answers a phrase
match with no points at all rather than an error, so the driver checks the index first and the
error names `CREATE INDEX … USING text WITH (phrase_matching = true)`, which it also writes.
`BETWEEN`, `at_least`, the geo and array functions, and `EXISTS` are still the target.

`LIKE` reads as an operator only after a column, and the text conditions are function names, so
a collection may still have payload fields called `like`, `text_match` or `phrase_match`: the
driver takes no name of its own here.

| SQL | Qdrant condition |
| --- | --- |
| `AND`, `OR`, `NOT` | `must`, `should`, `must_not` |
| `at_least(2, cond1, cond2, cond3)` | `min_should` |
| `lang = 'en'`, `year = 2024`, `active = true` | `match` value |
| `lang IN ('en', 'de')` / `NOT IN` | `match` any / except |
| `<`, `<=`, `>`, `>=`, `BETWEEN` on numbers | `range` |
| … on `TIMESTAMP WITH TIME ZONE` columns | `datetime_range` |
| `name LIKE 'Desk%'` | `match` prefix (runs) |
| `name LIKE '%lamp%'` | `match` substring (runs) |
| `name LIKE 'Desk lamp'` | `match` value: a pattern with no wildcard (runs) |
| `text_match(title, 'refund policy')` | `match` text: every word, through the full-text index (runs) |
| `text_any(title, 'refund return')` | `match` text_any: any word (runs) |
| `phrase_match(title, 'refund policy')` | `match` phrase; needs `phrase_matching` on the text index (runs) |
| `tags @> '["office"]'` | `match` on an array field: any element equals (one condition per listed element) |
| `jsonb_array_length(tags) >= 2` | `values_count` |
| `field IS NULL` / `IS NOT NULL` | `is_empty`: missing, `null`, or `[]` |
| `is_json_null(field)` | `is_null`: present and `null` |
| `dense IS NOT NULL`, `_vector IS NULL` (vector columns) | `has_vector` |
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
[Embeddings](#embeddings). `OFFSET n` runs too, as the Query API's own `offset`: it counts rows
the way SQL does, so `LIMIT` still counts the points returned after it and the scores stay the
scores of the points Qdrant ranked (see [`OFFSET`](#offset)). The rest of this section is still
the target; a `WHERE` filters the points Qdrant ranks, as in a scroll.

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

### Several searches in one call

Searches of one collection, separated by semicolons and run with `Statement.execute`, go to
Qdrant as one batch, which is one round trip and lets Qdrant run them side by side:

```sql
SELECT _id, _score FROM docs ORDER BY similarity(dense, [0.1, ...]) DESC LIMIT 5;
SELECT _id, title  FROM docs WHERE lang = 'en' ORDER BY similarity(dense, [0.7, ...]) DESC LIMIT 3;
```

**This runs today.** Each search keeps its own columns, `WHERE`, `LIMIT`, `OFFSET` and vector,
and its rows are a result set of their own, in the order written: `execute` answers with the
first, and `getMoreResults()` moves to each next one. A search answers as it would alone, down
to the bound and the warning a missing `LIMIT` gets. Only similarity searches batch, and only of
one collection, as Qdrant's batch is per collection; a script holding anything else is an error
before anything runs, since several writes in one string would stop halfway with no way to say
how far they got. It works on a `Statement`: a `PreparedStatement` is one statement.

### The best of each group

A search can keep only the best points of each group, the groups being the values of one
payload field: the best chunk of each document, say, rather than five chunks of the same one.

```sql
-- The best chunk of each document
SELECT DISTINCT ON (doc_id) _id, doc_id, _score
FROM chunks ORDER BY similarity(dense, embed('refund policy')) DESC LIMIT 10;

-- The best three chunks of each document, ten rows in all
SELECT _id, doc_id, _score
FROM chunks ORDER BY similarity(dense, embed('refund policy')) DESC
LIMIT 3 BY doc_id LIMIT 10;
```

**This runs today**, through Qdrant's grouped Query API. `DISTINCT ON (field)` is PostgreSQL's
way to say one row of each value and `LIMIT n BY field` is ClickHouse's way to say `n`; `DISTINCT
ON (f)` is `LIMIT 1 BY f`, and a statement gives one of them. Both need the search, `ORDER BY
similarity(…) DESC`, since only a ranking says which points are best; a `WHERE` filters before
the grouping, and `_score` is there as in any search.

The rows are what SQL says they are: the best points overall, with no more than `n` from any one
group, best first, and the `LIMIT` and `OFFSET` that follow count rows. Qdrant counts groups
instead and has no offset for them, so the driver asks for as many groups as there are rows to
read — the best rows always come from the best groups — and orders, skips and cuts the points
itself. The offset is therefore capped at 10,000 rows, as a scroll's is.

The field is a payload field holding text or whole numbers, the two kinds Qdrant groups by, and
needs no index. Two things differ from SQL's `GROUP BY`, and both are Qdrant's: a point without
the field is in no group and so in no row, and a point whose field is an array is in a group
for each of its values, so it can be a row more than once. Grouping by two fields, and Qdrant's
lookup of each group's own record in another collection, aren't supported yet.

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
for a `SELECT` without `FROM`; a count is one row, so no other limit changes it, and any
`OFFSET` at all skips past that row and counts nothing.

`SELECT lang, COUNT(*) FROM docs [WHERE …] GROUP BY lang LIMIT 20` also runs, as one facet
call. The field needs an index Qdrant can count the values of — `keyword`, `integer`, `bool`,
or `uuid`, which the driver checks against the collection's indexes first — and the statement
needs a limit — its `LIMIT`, its maximum rows, or the connection's `defaultLimit` — because
Qdrant counts the ten most common values when a request gives none, and returns no cursor to read the rest, so a statement says how many groups it
wants. The groups come back most common first. A grouped statement selects the grouped field
and `COUNT(*)`, in either order and under any names, and one without the count reads the
field's values themselves, which is what `SELECT DISTINCT` asks for. An array field counts once
per element, so `GROUP BY tags` counts the points under each tag, and a group's type follows
the index rather than the column, so the groups of a JSON column of keywords are text. `OFFSET n`
pages past the most common groups: Qdrant's facet API has no `offset` of its own, so the driver
counts `limit + n` values and drops the first `n` (see [`OFFSET`](#offset)).

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

`INSERT INTO docs (columns) VALUES (…), (…)` runs as one upsert in `insert_only` mode. The
column list is required, since a collection's columns come from its points. An ID the statement
gives mustn't be taken: the driver looks the IDs up first and fails with `23505` before
anything is written, and the `insert_only` upsert leaves alone any point that arrives in
between. A row without `_id` gets a random UUID, which `getGeneratedKeys` returns, with the IDs
the statement gave. `_payload` gives the whole payload, and fields named beside it go into it;
`"specs.color"` builds its object; a field the collection doesn't have yet is added; `NULL`
leaves a field or a vector out. A vector is written out (`[0.1, 0.2]`), given as JSON text as a
tool sends a cell (`'[[0.1, 0.2]]'` for a multivector, `'{"indices": […], "values": […]}'`
for a sparse vector), or made with `embed('text' [, 'provider:model'])`: Qdrant embeds a
`qdrant:` model as it stores the point, and the driver embeds any other before it sends the
point.

`ON CONFLICT [(_id)] DO NOTHING` and `ON CONFLICT [(_id)] DO UPDATE [WHERE …]` say what to do
with a row whose ID the collection already has, and map onto Qdrant's upsert modes. Without the
clause a taken ID is `23505`, found by the ID lookup above. `DO NOTHING` is the `insert_only`
mode on its own: Qdrant skips a point that's there and reports nothing, so no lookup is made and
no error raised. `DO UPDATE` is the `upsert` mode, which replaces the point with the row — the
whole point, so a column the statement doesn't name is gone rather than kept, and so `DO UPDATE`
takes no `SET` list; the error says as much. A `WHERE` after `DO UPDATE` becomes Qdrant's
`update_filter`, which it applies only to the points already there: those that match are
replaced, those that don't keep their state, and a row whose ID is new is inserted whatever the
condition says. That is what the clause means in PostgreSQL too, and it makes a conditional
refresh one statement. The conflict target is optional and may only be `_id`, since a Qdrant
point has one key. Generating IDs other than UUIDs is still the target.

`UPDATE docs SET … WHERE …` runs too, as one call to the batch update API: fields are set
together, a field inside an object (`"specs.color"`, or `specs.color` without quotes) is set
under its object's `key`, `NULL` deletes a field, and `_payload` replaces or clears the whole
payload before the other fields are set. A field the collection doesn't have yet is added. A
value is read as its column's type, so a cell's text works as a tool sends it: `'49.5'` into
a `DOUBLE`, `'false'` into a `BOOLEAN`, `'["a"]'` into a `JSON` column. The update count is
the matching points, counted first, as for `DELETE`. An `UPDATE` without `WHERE` is an error,
and so is changing `_id`. A point ID compared with text that holds a whole number
(`_id = '7'`, as DataGrip sends it) matches that number.

`UPDATE` changes vectors too, in the same batch call as any payload change beside them:
`SET dense = '[0.1, 0.2]'` writes one and `SET dense = NULL` deletes it. A vector is written
out, given as JSON text as a tool sends a cell, or made with `embed('text')`, exactly as an
`INSERT` takes it, and its dimensions and shape are checked against the collection first. The
two halves reach Qdrant differently because Qdrant differs: `delete_vectors` takes the filter as
it is, while `update_vectors` takes a list of point IDs and rejects a filter alone. So a write
scrolls the IDs the `WHERE` matches first, sends that filter along as the operation's
`update_filter` — a point that stops matching between the read and the write is left alone
rather than written to on the strength of a stale ID — and is capped at **10,000** points, the
same cap a driver-side `OFFSET` has and for the same reason: it's a read the statement pays for
and doesn't return. Past it the statement is an error suggesting a narrower `WHERE`. Deleting a
vector leaves a point that has none and can't be found by a search, which Qdrant allows even for
a collection's only vector; that is why `_vector IS NULL` is a question worth asking (see
[Filters](#filters-where)).

| SQL | Qdrant |
| --- | --- |
| `INSERT INTO docs (_id, dense, lang) VALUES (…)` | Upsert in `insert_only` mode |
| `INSERT … ON CONFLICT (_id) DO NOTHING` | Upsert (`insert_only` mode), with no ID lookup (runs) |
| `INSERT … ON CONFLICT (_id) DO UPDATE` | Upsert (`upsert` mode) (runs) |
| `… ON CONFLICT (_id) DO UPDATE WHERE version < 3` | Upsert with `update_filter`: conditional update (runs) |
| `INSERT` without `_id` | The driver generates a UUID, returned by `getGeneratedKeys` |
| `INSERT … VALUES (…, embed('text'), …)` | Vectors embedded by the driver or by Qdrant |
| `UPDATE docs SET lang = 'de' WHERE …` | `set_payload` by filter |
| `UPDATE … SET "meta.author" = 'Ann'` | `set_payload` with `key` (nested path) |
| `UPDATE … SET _payload = '{…}'` | `overwrite_payload` |
| `UPDATE … SET _payload = _payload \|\| '{"lang": "de"}'` | `set_payload`: merge the given fields |
| `UPDATE … SET lang = NULL`, `SET _payload = _payload - 'lang'` | `delete_payload` for `lang` |
| `UPDATE … SET dense = '[…]'` | `update_vectors`, for the matching IDs, read first (runs) |
| `UPDATE … SET dense = embed('text')` | `update_vectors` with the vector the driver or Qdrant made (runs) |
| `UPDATE … SET dense = NULL` | `delete_vectors`, by filter (runs) |
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
new collection again rather than leave half a table. `WITH (…)` doesn't run
yet; `CREATE INDEX` on its own and `ALTER TABLE` are described below.

`CREATE INDEX [IF NOT EXISTS] [name] ON docs (field) [USING type] [WITH (option = value, …)]`
creates a payload index on one field, and `DROP INDEX [IF EXISTS] name ON docs` or
`DROP INDEX [IF EXISTS] ON docs (field)` deletes it; both run today. Qdrant names an index by
its field, and `getIndexInfo` reports it as `<field>_idx`, so `lang_idx` and `lang` both name the
index on `lang`, and a `CREATE INDEX` that names it otherwise is an error. The type defaults to
the column's (`VARCHAR` to `keyword`, `BIGINT` to `integer`, `DOUBLE` to `float`, `BOOLEAN` to
`bool`, `TIMESTAMP WITH TIME ZONE` to `datetime`); a JSON column, or a field the collection
doesn't have yet, says `USING`. The `WITH` options are the ones Qdrant keeps for the type, and
any other is an error, since Qdrant accepts an unknown one and drops it without a word:

| Type | Options |
| --- | --- |
| `keyword`, `uuid` | `is_tenant`, `on_disk` |
| `integer` | `lookup`, `range`, `is_principal`, `on_disk` |
| `float`, `datetime` | `is_principal`, `on_disk` |
| `bool`, `geo` | `on_disk` |
| `text` | `tokenizer`, `min_token_len`, `max_token_len`, `lowercase`, `ascii_folding`, `phrase_matching`, `stopwords`, `stemmer` (a language, for Qdrant's Snowball stemmer), `on_disk` |

Qdrant would replace an index already on the field, and answers a field without one as
dropped, so the driver looks first: a second `CREATE INDEX` on a field is `42S11` (nothing to do
with `IF NOT EXISTS`), and a `DROP INDEX` of a missing one is `42S12` (nothing with `IF
EXISTS`). Changing an index's type is a `DROP INDEX` and a `CREATE INDEX`. The columns the
driver keeps follow at once, under the collection's name and its aliases', so an `ORDER BY`
that needed the index runs on the next statement.

`ALTER TABLE docs ADD [COLUMN] [IF NOT EXISTS] image VECTOR(512) DISTANCE COSINE` adds a named
vector to a collection that exists, and `ALTER TABLE docs DROP [COLUMN] [IF EXISTS] image`
deletes one, with every point's value for it; both run today. The column is written as `CREATE
TABLE` writes it, so it can be a `VECTOR(n)`, a `MULTIVECTOR(n)` or a `SPARSE_VECTOR [MODIFIER
IDF]`, and the points that were already there have no value for it until an `UPDATE` gives them
one. A vector's dimensions and distance can't be changed afterwards: that's a `DROP COLUMN` and
an `ADD COLUMN`, which loses the values. Qdrant answers an `ADD` of a vector that's there with
the same settings as done, and a `DROP` of one that isn't as done too, so the driver looks
first: a taken name is `42S21` (nothing to do with `IF NOT EXISTS`) and a missing vector `42S22`
(nothing with `IF EXISTS`).

Only vectors are columns Qdrant keeps a declaration of, so only they can be added and dropped.
`ADD COLUMN lang KEYWORD` is an error that names `CREATE INDEX`, since a payload field needs no
adding and the only thing to declare about it is an index; `DROP COLUMN lang` is an error that
names `DROP INDEX` and `UPDATE … SET lang = NULL`. The unnamed vector, `_vector`, comes from
`CREATE TABLE` or not at all: Qdrant neither adds nor deletes it. It does let a named vector be
added beside it, and then the collection has both, `_vector` and the named ones, in every
statement.

`ALTER TABLE docs SET (setting = value, …)` changes a collection's settings in place, and runs
today. Qdrant changes what the statement names and leaves the rest. The settings come in groups,
and a setting can be written whichever way reads best; all of these are the same change:

```sql
ALTER TABLE docs SET (hnsw.m = 16);              -- group.name
ALTER TABLE docs SET (hnsw_config.m = 16);       -- the group as Qdrant names it
ALTER TABLE docs SET (m = 16);                   -- the name alone, where one group has it
ALTER TABLE docs SET (hnsw = '{"m": 16}');       -- the whole group as JSON
```

| Group | Settings |
| --- | --- |
| `params` | `replication_factor`, `write_consistency_factor`, `read_fan_out_factor`, `read_fan_out_delay_ms`, `on_disk_payload` |
| `hnsw` (`hnsw_config`) | `m`, `ef_construct`, `full_scan_threshold`, `max_indexing_threads`, `payload_m`, `on_disk`, `inline_storage` |
| `optimizers` (`optimizers_config`, `optimizer_config`) | `deleted_threshold`, `vacuum_min_vector_number`, `default_segment_number`, `max_segment_size`, `memmap_threshold`, `indexing_threshold`, `flush_interval_sec`, `max_optimization_threads` (a number or `auto`), `prevent_unoptimized` |
| `strict_mode` (`strict_mode_config`) | `enabled`, `max_query_limit`, `max_timeout`, `unindexed_filtering_retrieve`, `unindexed_filtering_update`, `search_max_hnsw_ef`, `search_allow_exact`, `search_max_oversampling`, `upsert_max_batchsize`, `search_max_batchsize`, `max_collection_vector_size_bytes`, `max_collection_payload_size_bytes`, `max_points_count`, `read_rate_limit`, `write_rate_limit`, `filter_max_conditions`, `condition_max_size`, `max_payload_index_count`, `max_resident_memory_percent` |
| `quantization` (`quantization_config`) | Given whole, as JSON, since it's one of several shapes: `quantization = '{"scalar": {"type": "int8"}}'`; `quantization = disabled` turns it off |
| `metadata` | Free-form: `metadata = '{"owner": "search"}'`, or one key at a time as `metadata.owner = 'search'`. Qdrant merges it into what's there, and a key set to `NULL` is removed. Everything after `metadata.` is the key, dots and case kept, so `metadata.runax.embed.dense = 'openai:…'` sets a vector's embedding model |

Qdrant accepts a name it doesn't know, drops it, and answers that all went well, so the driver
checks every name first: an unknown setting or group is an error listing the ones there are, a
value of the wrong kind is `22023`, and one setting given two ways in a statement is an error
whichever would have won. A group's JSON form also reaches the few settings that are objects
themselves (`memory`, a collection's `payload` and `id_tracker`, strict mode's `multivector_config`
and `sparse_config`); the names inside those are Qdrant's to check. What a collection can't
change after it's created isn't here: its shard count, its sharding method, and its WAL.

`ALTER TABLE docs ALTER [COLUMN] dense SET (…)` changes one vector's settings, which override
the collection's for that vector. A dense vector or a multivector has `on_disk`, the `hnsw`
group and `quantization`; a sparse vector has `modifier` (`idf` or `none`) and an `index` group
of `on_disk`, `full_scan_threshold` and `datatype`. Here `on_disk` alone is the vector's own
storage and `hnsw.on_disk` its index's. `_vector` names the unnamed vector. A column that isn't
a vector is `42S22`: a payload field has no settings.

| SQL | Qdrant |
| --- | --- |
| `CREATE TABLE` with vector columns | Create collection; a single column named `_vector` makes the unnamed vector |
| Payload columns with `INDEX` in `CREATE TABLE` | Payload indexes, created after the collection |
| `WITH (…)` | Collection settings: shards, replication, HNSW, optimizers, quantization, strict mode, sharding method, metadata |
| `ALTER TABLE docs SET (…)` | Update collection (runs today) |
| `ALTER TABLE docs ALTER COLUMN dense SET (…)` | Update collection, one vector's settings (runs today) |
| `ALTER TABLE docs ADD COLUMN image VECTOR(512) DISTANCE COSINE` | Add a named vector (runs today) |
| `ALTER TABLE docs DROP COLUMN image` | Delete a named vector (runs today) |
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

**What runs today:** all four. A view is `SELECT *` (or `SELECT t.*`) from one collection and
nothing else, since an alias is another name for a whole collection; a view of a view is an
error naming the collection to use instead. Qdrant would move an alias that already exists on a
plain create, and answers a missing one as deleted, so the driver looks first: `CREATE VIEW` of
a name that's taken is `42S01` unless it says `OR REPLACE`, which deletes and creates the alias
in one atomic change, so a reader always sees one collection or the other; renaming to a name
that's taken is `42S01`; and `DROP VIEW` of a missing view is `42S02` unless it says
`IF EXISTS`. Dropping a collection drops its aliases too, as Qdrant does; the driver forgets the
columns it kept under their names, so they read as missing rather than as the collection that's
gone.

## Embeddings

`embed('text', 'provider:model')` turns text into a vector. The model is written as who runs
it, a colon, and the model's own name, unchanged: `openai:text-embedding-3-small`,
`voyage:voyage-3`, `qdrant:qdrant/bm25`, `qdrant:sentence-transformers/all-minilm-l6-v2`.

A model that can return fewer dimensions than it was trained for takes the size it should
return, as `openai:text-embedding-3-large?dimensions=1024`. The provider shortens the vector
itself, which keeps more of the meaning than truncating one the driver already paid for. The
size is part of the ID rather than a connection setting, so a collection's metadata names one
vector exactly and a search can't quietly ask for a different size from the one its points were
stored with: two IDs asking for different sizes are different models. A size that doesn't match
the vector's own is an error before the provider is called.

| Provider | Who embeds | Settings |
| --- | --- | --- |
| `qdrant` | Qdrant, through the Query API's document query: any kind of vector the model makes | None: every Qdrant runs `qdrant/bm25`; hosted models need Qdrant Cloud's inference |
| `openai` | The driver, through `POST {openaiUrl}/embeddings` | `openaiApiKey`; `openaiUrl` (default `https://api.openai.com/v1`) for Azure OpenAI or a proxy |
| `voyage` | The driver, through `POST {voyageUrl}/embeddings` | `voyageApiKey`; `voyageUrl` (default `https://api.voyageai.com/v1`) |

Every text one statement needs is embedded in one request: an `INSERT` of a thousand rows that
embeds a column is one call to the provider, not a thousand, split only as far as the provider's
own limits on texts and tokens per request require. Rows giving the same text for the same model
share a vector, since the answer is the same and a provider charges per text. A provider may
answer a batch in any order and says which text each embedding is for, so the vectors go back
where their texts were.

Whether a text is a search query or a document being stored is sent along with it, since some
models embed the two differently so that a question lands near the passages answering it:
`embed()` inside a ranking function is a query, and inside `INSERT` or `UPDATE` a document.
Voyage takes this as its `input_type`; OpenAI's models embed both the same way and are sent no
role. A size becomes OpenAI's `dimensions` or Voyage's `output_dimension`.

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
collection's columns, and the `ALTER TABLE` above sets it, as does the shorter
`ALTER TABLE docs SET (metadata.runax.embed.dense = 'openai:text-embedding-3-small')`; the
next statement sees it. Providers the driver calls make dense vectors,
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

**What runs today:** `SET qdrant.<option> = <value>` (or `TO <value>`) for `consistency`,
`ordering`, `wait`, `timeout`, `hnsw_ef`, `exact`, and `indexed_only`; `SET qdrant.<option> =
DEFAULT` and `RESET qdrant.<option>` put one back to Qdrant's default, and `RESET ALL` every
one. `SHOW qdrant.<option>` and `SHOW ALL` return rows of `name` and `setting`, with a null
setting for an option left at Qdrant's default. A value may be a bare word (`majority`) or
text that holds a number or a boolean (`'30'`). The options belong to the connection: every
statement it makes after a `SET` sends them, other connections don't, and a read-only
connection may change them, since they change nothing in Qdrant.

| Option | Sent with |
| --- | --- |
| `consistency`, `timeout` | Reads: scrolls, searches, counts, facets |
| `wait`, `ordering`, `timeout` | Writes: deletes, and the writes to come |
| `hnsw_ef`, `exact`, `indexed_only` | Searches, as the request's `params` |

A name without the `qdrant.` prefix, as tools send for other databases (`SET search_path`),
isn't supported, rather than ignored. `shard_key`, the quantization options, and the
`/*+ … */` hint for one statement are still the target.

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
| `system.health` | `GET /healthz`, `GET /livez`, `GET /readyz` | `probe`, `ok`, `status_code`, `message` |
| `system.optimizations` | `GET /collections/{name}/optimizations` for each | `collection_name`, `state`, `uuid`, `optimizer`, `status`, `status_detail`, `segments_count`, `points_count`, `started_at`, `finished_at`, `duration_sec`, `segments`, `progress` |
| `system.quotas` | `GET /quotas` | `enabled`, `max_resident_memory_percent`, `max_disk_usage_percent`, `release_margin_percent`, `resident_memory_percent`, `disk_usage_percent` |
| `system.cluster_telemetry` | `GET /cluster/telemetry` | `path`, `value`, `value_type` |

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

`system.collections`, `system.snapshots`, `system.shard_keys`, `system.shards`, and
`system.optimizations` read one
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

`system.optimizations` is what Qdrant's optimizers are doing to each collection's segments: a
row per optimization, whose `state` says which of Qdrant's lists it's in. `running` ones are
under way, `queued` ones are Qdrant's estimate of what runs next and have no `uuid`, `status`
or times yet, and `completed` ones are the latest Qdrant finished, of which it sends sixteen
per collection. `status` is `optimizing`, `done`, `cancelled` or `error`, with the reason for
the last two in `status_detail`. `progress` is the optimization's steps as the JSON tree Qdrant
sends, each step with its times and, for some, units done of a total; `started_at`,
`finished_at` and `duration_sec` are the root of that tree. A collection at rest has no rows,
and the segments that need no optimizing aren't listed: they aren't optimizations. Within a
collection the rows keep Qdrant's order, so the queue reads top to bottom.

`system.quotas` is always one row: the limits on memory and disk past which Qdrant refuses
writes, whether they're enforced, and how much of each is used now. `ALTER SYSTEM SET` changes
the limits, since they belong to the server rather than to any collection:

```sql
ALTER SYSTEM SET (quotas.enabled = true, quotas.max_disk_usage_percent = 90);
ALTER SYSTEM SET (max_resident_memory_percent = 85);     -- the name alone
ALTER SYSTEM SET (quotas = '{"release_margin_percent": 5}');
ALTER SYSTEM SET (quotas.max_disk_usage_percent = NULL);  -- no limit on disk
```

The settings are `enabled`, `max_resident_memory_percent`, `max_disk_usage_percent` and
`release_margin_percent`, written as a collection's are in `ALTER TABLE … SET (…)`. A statement
changes what it names and keeps the rest, though Qdrant itself replaces the whole config with
each write: the driver reads the config first and sends it back changed, exact unless something
else sets quotas between the two calls. Quotas are the only setting of the server there is so
far, and a read-only connection refuses the statement.

`system.health` is Qdrant's three health probes, one row each, always in the order `healthz`,
`livez`, `readyz`. A probe says "no" with its HTTP status, so a failing one is a row with `ok`
false, the status in `status_code`, and what Qdrant said in `message`, rather than an error.
`healthz` and `livez` are the same check, that the process answers at all; only `readyz` looks
at the shards, and answers 503 while one isn't ready. Qdrant answers all three without
an API key, and a server the driver can't reach is still an error, as for any statement.

One of these tables takes one write. `TRUNCATE TABLE system.issues` clears the issues, which is
all Qdrant offers: it forgets every issue at once and can't forget one, so `DELETE FROM
system.issues WHERE …` is an error that names the `TRUNCATE`. The statement reports no count,
since Qdrant doesn't say how many issues there were, and a read-only connection refuses it like
any other write. Clearing settles nothing: Qdrant reports an issue again when it next comes
across what caused it.

## Operations

`CALL` runs the things Qdrant does that aren't about rows. A procedure is one request, and its
name may be written alone or after `qdrant.`, `system.` or `qdrant.system.`:

```sql
CALL qdrant.create_shard_key('docs', 'eu');
CALL create_shard_key('docs', 7, shards_number => 2, replication_factor => 1);
CALL move_shard('docs', 0, 8882707261238555, 1321113523610302, method => 'snapshot');
CALL remove_peer(1321113523610302, force => true);
```

Arguments are given by position, in the order below, or by name as `name => value`, and the
ones in brackets can be left out. An argument is a constant or a `?`, so a `PreparedStatement`
runs a `CALL` with its values bound; `NULL` is an argument left out. `prepareCall` isn't
supported, since no procedure returns anything to read back. A `CALL` returns an update count
of 0, and a read-only connection refuses it.

**What runs today** is the cluster's procedures, which Qdrant takes only when it runs as a
cluster; a standalone server answers that it is one:

| Procedure | Qdrant |
| --- | --- |
| `create_shard_key(collection, shard_key [, shards_number] [, replication_factor] [, placement])` | Create shard key; `placement` is a JSON array of peer ids |
| `delete_shard_key(collection, shard_key)` | Delete shard key |
| `move_shard(collection, shard_id, from_peer_id, to_peer_id [, method])` | Move a shard to another peer |
| `replicate_shard(collection, shard_id, from_peer_id, to_peer_id [, method])` | Copy a shard to another peer |
| `abort_shard_transfer(collection, shard_id, from_peer_id, to_peer_id)` | Stop a transfer under way |
| `restart_shard_transfer(collection, shard_id, from_peer_id, to_peer_id, method)` | Start a transfer under way again |
| `drop_replica(collection, shard_id, peer_id)` | Delete one peer's copy of a shard |
| `start_resharding(collection, direction [, peer_id] [, shard_key])` | Add a shard (`up`) or remove one (`down`) |
| `abort_resharding(collection)` | Stop a resharding under way |
| `replicate_points(collection, from_shard_key, to_shard_key [, filter])` | Copy points between shard keys; `filter` is a Qdrant filter in JSON |
| `remove_peer(peer_id [, force] [, timeout])` | Remove a peer from the cluster |
| `recover_raft()` | Recover the connected peer's Raft state |

A shard key is text or a whole number, and a `method` is `stream_records`, `snapshot` or
`wal_delta`. The driver checks the arguments' count, names and kinds, and Qdrant the rest:
whether the collection, the shard and the peers are there. What they change is read back from
`system.shard_keys`, `system.shards`, `system.peers` and `system.cluster`. They are listed by
`DatabaseMetaData.getProcedures`, with their arguments in `getProcedureColumns`, so a tool shows
them.

Still to come, as procedures of the same kind:

| SQL | Qdrant |
| --- | --- |
| `CALL qdrant.create_snapshot('docs')`, `delete_snapshot` | Collection snapshots (listing them is `system.snapshots`) |
| `CALL qdrant.create_full_snapshot()`, `delete_full_snapshot` | Full snapshots (listing them is `system.snapshots` too) |

## Not supported

These are errors, not emulated:

- `JOIN`, except the group lookup above; `UNION`; subqueries other than the prefetch form and
  `EXISTS` over `jsonb_array_elements`.
- Aggregates other than `COUNT`, `MIN`, and `MAX`; `GROUP BY` without a facet-able field.
- Conditions that compare two columns, or apply functions to a column other than those
  listed here: Qdrant's filters only compare one field with constants. Checking them in the
  driver after Qdrant answers is a possible later feature (see the roadmap).
- `LIKE` patterns other than prefix (`'abc%'`), substring (`'%abc%'`) and one with no wildcard
  at all, and `ILIKE`. Qdrant has no suffix, single-character, or case-insensitive match, and
  the driver doesn't filter rows itself, so `'%lamp'` is an error rather than a broader
  `'%lamp%'` search trimmed in the driver. The error suggests `'%lamp%'`. `_` is not a wildcard
  and no `ESCAPE` clause is read, so a pattern can't match a literal `%` or `_` either.
- Transactions and savepoints: every statement applies on its own, because Qdrant has no
  transactions. An opt-in write buffer that holds writes until `commit()` may come later
  (see the roadmap); it still wouldn't make commits atomic.
- Writing to the `system` tables, other than `TRUNCATE TABLE system.issues`, or reading server
  internals any way but through them. The quotas `system.quotas` shows are changed with `ALTER
  SYSTEM SET`, not by writing to the table.
  Telemetry, Prometheus metrics, cluster and Raft state, and Qdrant's performance hints are
  all readable as [system tables](#system-tables) and, the issues aside, read-only there; of
  telemetry, only
  what Qdrant answers with by default is exposed, not its deeper `?details_level=` levels.

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
