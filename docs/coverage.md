# Coverage — how much of Qdrant, and how much SQL

Two questions this document answers precisely: how much of Qdrant's REST API the driver calls,
and how much SQL it accepts. Both are measured, not estimated — every number below comes from
the code or from a server that was asked. The point is to make the gaps legible, so a
maintainer can tell a missing feature that blocks real use from one that nobody would notice.

**Measured against** driver commit `5212b3d` — the whole of `SET`, `OFFSET`, the index and view
statements, the write statements, the vector changes, the text conditions, `ON CONFLICT` and the
embedder's model and role (#27, #28, #30, #31, #33, #34, #35, #36 and #39) — with the batched
embedding of #40, the clearing of `system.issues` (#42), `system.health` (#43),
`system.optimizations` (#44), `ALTER TABLE … ADD`/`DROP COLUMN` (#45) and
`ALTER TABLE … SET` on top, and Qdrant
**1.20.0** (build
`222b2e44ab1c3f17e8246396ead6558e45041df7`), running locally over REST on port 6333, with the
three collections `./gradlew seedQdrant` loads. Qdrant 1.20 does not serve `/openapi.json` —
that path answers 404 — so the route list comes from the published spec at the matching tag,
checked against the running server.

To re-measure after the driver or the server moves:

```bash
# The routes Qdrant documents, by area, for the version you run
curl -sL https://raw.githubusercontent.com/qdrant/qdrant/v1.20.0/docs/redoc/master/openapi.json \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); \
      print("\n".join(sorted("%s\t%s\t%s" % (m.upper(), p, ",".join(o.get("tags", []))) \
      for p, ops in d["paths"].items() for m, o in ops.items() \
      if m in ("get", "post", "put", "patch", "delete"))))'

# The routes the driver calls: the gateway, plus the ones the system tables read directly
grep -rno '"/[a-zA-Z0-9_{}/?=&.-]*"' --include='*.java' src/main/java | sort -u -t: -k3

# Whether a route exists on the server you run: an unknown path answers 404 with an empty
# body, a real one answers a JSON error or a result
curl -s -o /dev/null -w '%{http_code} ' -X POST localhost:6333/collections/nope/points/vectors
curl -s -X PUT localhost:6333/collections/nope/points/vectors -d '{}'
```

Probe writes only against a collection you create and delete yourself. The `sample_*`
collections are seeded data a maintainer works with by hand; a stray `points/delete` against
one of them is indistinguishable from a bug.

## Qdrant REST coverage

Qdrant 1.20 documents **69 routes**. The driver calls **35** of them, which is **51%**. The
counts below are per area, using Qdrant's own OpenAPI tags, so they can be recomputed from the
spec without judgement calls.

| Area | Covered | Total | What the covered routes are for |
| --- | --- | --- | --- |
| Collections | **9** | **9** | Listing and describing tables, `CREATE TABLE`, `DROP TABLE`, `IF NOT EXISTS`, `system.optimizations`, and all of `ALTER TABLE`: `ADD COLUMN`, `DROP COLUMN`, `SET (…)` |
| Points | 7 | 14 | `SELECT` (scroll), `COUNT(*)`, `GROUP BY` (facet), `DELETE`, `TRUNCATE`, `UPDATE` (batch), `INSERT` (upsert, and the ID check before it) — and `LIKE` and the full-text conditions, which are fields of the filter all of them send |
| Search | 1 | 5 | `ORDER BY similarity(...) DESC`, including `embed()` |
| Aliases | **3** | **3** | Views: listing, `system.aliases`, `CREATE [OR REPLACE] VIEW`, `ALTER VIEW`, `DROP VIEW`, and the aliases a dropped collection takes with it |
| Indexes | **2** | **2** | `CREATE INDEX`, `DROP INDEX`, and the indexes `CREATE TABLE` declares |
| Snapshots | 2 | 17 | `system.snapshots` only |
| Distributed | 3 | 9 | `system.cluster`, `system.peers`, `system.shards`, `system.shard_keys` |
| Service | **6** | **6** | Version on connect, `system.telemetry`, `system.metrics`, `system.health` |
| Beta | **2** | **2** | `system.issues`, and `TRUNCATE TABLE system.issues` |
| Quotas | 0 | 2 | — |
| **Total** | **35** | **69** | |

Five areas are covered whole: every alias route, every index route, both beta routes, every
service route, and every collection route.

### The routes the driver calls

Twenty-three come from `QdrantGateway`, which is the only place that speaks HTTP, and twelve more
from `QdrantSystemTables`: nine endpoints the gateway has no model for because their shape
depends on the Qdrant version, and the three health probes, which answer in a line of text.

| Route | Called by | For |
| --- | --- | --- |
| `GET /` | `serverVersion` | The product version on connect; the only call that needs no API key |
| `GET /collections` | `listCollections` | `getTables`, and the first call of four system tables |
| `GET /collections/{c}` | `collectionInfo` | Columns, vector sizes, payload indexes, embedding metadata |
| `GET /collections/{c}/exists` | `collectionExists` | `CREATE TABLE IF NOT EXISTS` |
| `PUT /collections/{c}` | `createCollection` | `CREATE TABLE` |
| `DELETE /collections/{c}` | `deleteCollection` | `DROP TABLE`, and the rollback when an index fails |
| `PUT /collections/{c}/index` | `createIndex` | `CREATE INDEX`, and the `… KEYWORD INDEX` columns of a `CREATE TABLE` |
| `DELETE /collections/{c}/index/{field}` | `deleteIndex` | `DROP INDEX` |
| `POST /collections/{c}/points/scroll` | `scroll`, `matchingIds` | `SELECT`, paged by cursor, the sample that infers columns, and the IDs an `UPDATE` of a vector writes to |
| `POST /collections/{c}/points/query` | `query` | `ORDER BY similarity(...) DESC` |
| `POST /collections/{c}/points/count` | `count` | `COUNT(*)`, and the row count a `DELETE` or `UPDATE` reports |
| `POST /collections/{c}/points/delete` | `deletePoints` | `DELETE … WHERE`, `TRUNCATE TABLE` |
| `POST /collections/{c}/points/batch` | `updatePoints` | `UPDATE`: set, delete, overwrite and clear payload, and write and delete vectors, in one call |
| `POST /collections/{c}/points` | `existingIds` | `INSERT`: the IDs a statement gives, looked up so a taken one is an error — not called when `ON CONFLICT` says what to do instead |
| `PUT /collections/{c}/points` | `insertPoints` | `INSERT`: one upsert, in `insert_only` mode or, for `ON CONFLICT … DO UPDATE`, in `upsert` mode with an `update_filter` |
| `POST /collections/{c}/facet` | `facet` | `GROUP BY … COUNT(*)` |
| `GET /aliases` | `listAliases` | Aliases as views, `system.aliases`, and the checks before each view statement |
| `GET /collections/{c}/aliases` | `collectionAliases` | The alias names whose kept columns an index change or a `DROP TABLE` makes wrong |
| `POST /collections/aliases` | `changeAliases` | `CREATE [OR REPLACE] VIEW`, `ALTER VIEW … RENAME TO`, `DROP VIEW`, each one atomic change |
| `PATCH /collections/{c}` | `updateCollection` | `ALTER TABLE … SET (…)` and `ALTER TABLE … ALTER COLUMN … SET (…)`: the collection's settings, a vector's, and metadata |
| `PUT /collections/{c}/vectors/{v}` | `createVector` | `ALTER TABLE … ADD COLUMN`: a named dense vector, multivector or sparse vector |
| `DELETE /collections/{c}/vectors/{v}` | `deleteVector` | `ALTER TABLE … DROP COLUMN` |
| `DELETE /issues` | `clearIssues` | `TRUNCATE TABLE system.issues` |
| `GET /snapshots` | `QdrantSystemTables` | `system.snapshots`, the whole-storage rows |
| `GET /collections/{c}/snapshots` | `QdrantSystemTables` | `system.snapshots`, the per-collection rows |
| `GET /collections/{c}/shards` | `QdrantSystemTables` | `system.shard_keys` |
| `GET /collections/{c}/cluster` | `QdrantSystemTables` | `system.shards` |
| `GET /collections/{c}/optimizations` | `QdrantSystemTables` | `system.optimizations`, asked `?with=queued,completed` |
| `GET /cluster` | `QdrantSystemTables` | `system.cluster` and `system.peers` |
| `GET /telemetry` | `QdrantSystemTables` | `system.telemetry` |
| `GET /metrics` | `QdrantSystemTables` | `system.metrics`; the one route that answers text, not JSON |
| `GET /issues` | `QdrantSystemTables` | `system.issues` |
| `GET /healthz` | `QdrantSystemTables` | `system.health`, the `healthz` row |
| `GET /livez` | `QdrantSystemTables` | `system.health`, the `livez` row; Qdrant documents it as the same check as `healthz` |
| `GET /readyz` | `QdrantSystemTables` | `system.health`, the `readyz` row: the one probe that looks at the shards, and answers 503 while one isn't ready |

The driver also calls `POST /embeddings` on `api.openai.com` or `api.voyageai.com` for
`embed('text', 'openai:…')`. That is not a Qdrant route and is not counted here.

### Why 51% means less than it looks, in both directions

**The denominator is padded.** Of the 34 routes the driver does not call, 15 are snapshot
plumbing — creating, downloading, uploading and recovering snapshots of a collection or of one
shard — and 6 more are cluster surgery: moving a shard, dropping a peer, recovering Raft. No
SQL client would call those; they belong to an operator with a shell, and exposing them through
JDBC would mean inventing `CALL` syntax for operations that have nothing to do with rows. The
dialect spec does plan exactly that (see its *Operations* section), but as procedures, and a
procedure that wraps one route is not what "covering an API" usually means. Set aside the 15
unused snapshot routes, the 6 unused cluster ones and the 2 quota routes, and 46 remain, of
which the driver calls 35 — **76%**.
Neither number is wrong; they answer different questions.

**The numerator hides how much each route carries.** One route is not one feature.
`POST /collections/{c}/points/query` is a single row in the table above and is also the whole
of Qdrant's search: nearest-neighbour, recommendation, discovery, fusion, prefetch chains,
formulas, MMR, server-side embedding. The driver sends two shapes of its body — a vector under
`query.nearest`, or `{"text": …, "model": …}` when Qdrant embeds the text — out of a dozen the
route accepts. Counting it as covered is true and almost uninformative. The same goes for
`points/scroll`: the driver sends `limit`, `offset` as the page cursor, `filter`,
`with_payload`, `with_vector` and `order_by`'s key and direction, plus `consistency` and
`timeout` once a connection `SET`s them, and never `order_by.start_from` or `shard_key` —
untouched fields of a route that counts as used. `points/batch` is the opposite case: one
route that carries all of `UPDATE`.

**And one route can outweigh ten.** `PUT /collections/{c}/points` is upsert, and until #31 it
was one row in the unused list next to `cluster/recover`: the difference between a driver that
can put data into Qdrant and one that cannot. It is used now, by `INSERT`. The converse also
happens. The four single-purpose payload routes (`points/payload` in both methods,
`payload/clear`, `payload/delete`) count as uncovered, although `UPDATE` does all four of
their jobs through `points/batch`, in one call; nothing is missing there. The two vector routes,
`PUT …/points/vectors` and `…/vectors/delete`, are the same case since #35: both still
count as uncovered, and `UPDATE … SET dense = …` and `SET dense = NULL` do their work through
`points/batch` too. Four of Qdrant's fourteen point routes are therefore uncovered only in the
sense that the driver reaches the same behaviour through one route instead of four.

### Spec and server disagree, so both were checked

The published spec and the running binary are not the same list, and probing found three
differences worth recording, because a coverage number computed from either one alone would be
slightly wrong.

- **Eight routes the 1.20 spec no longer documents, the server still serves.** The legacy
  search family — `points/search`, `search/batch`, `search/groups`, `points/recommend` and its
  `batch` and `groups`, `points/discover` and `discover/batch` — is in the 1.15 spec, gone from
  the 1.20 spec, and alive on a 1.20 server: each answers a JSON validation error rather than
  an empty 404. They were superseded by the Query API, not removed. Counting them puts the live
  surface at 77 routes and the driver at 45%; this document uses the documented 69, because the
  spec is what a maintainer can check, and routes Qdrant has stopped documenting are not ones
  the driver should start calling.
- **`/locks` is really gone.** `GET` and `POST /locks` are in the 1.15 spec and answer an empty
  404 body on 1.20 — a router miss, not an error. This is the one case where the spec shrinking
  matched the binary.
- **`GET /collections/{c}/shards` exists on 1.20 and is absent from the 1.15 spec.** The driver
  reads it for `system.shard_keys`, so that table needs a Qdrant newer than 1.15; which release
  added the route was not determined. On a standalone server it answers `{}` and the table is
  empty, which is correct: Qdrant only allows a shard key to be created in a cluster.

Two more probe results, re-verified rather than assumed: `POST /collections/{c}/points/vectors`
does **not** exist — the method is `PUT`, and `POST` gets an empty 404 — while
`POST /collections/{c}/points/vectors/delete` does. And `/quotas`, new in the 1.20 spec, is
real on a self-hosted server rather than Qdrant Cloud only; `GET /quotas` answers
`{"config":{"enabled":false}, …}`. It is counted in the total above and is of no use to a SQL
client.

## SQL coverage

The ceiling is the lexer, but less of one than it looks. `Lexer.java` recognises
**31 keywords**, and a word outside that set becomes an identifier, so `HAVING`, `BETWEEN`,
`JOIN`, `UNION` and `CALL` cannot be lexed as syntax at all. `Parser.java` wires **eighteen
statement kinds**, and `Plan.java`'s sealed interface permits **twenty-two plan kinds** — the best
single index of what can run. No parser class is a stub any more.

A word that means something in one place only need not be a keyword, though: read where it can
appear, an identifier does the job and stays usable as a name. Many are: the type names a
`CREATE TABLE` uses (`VECTOR`, `KEYWORD`, `DISTANCE`, `MODIFIER`); `INDEX`, `ON`, `USING` and
`WITH` in the index statements; `VIEW`, `REPLACE`, `RENAME` and `TO` in the view statements;
`RESET`, `SHOW`, `ALL` and `DEFAULT` in the option statements; `ON`, `CONFLICT`, `DO` and
`NOTHING` in an `INSERT`'s conflict clause; and `LIKE`, read as an operator after a column,
which is the one place it can go. So `LIKE` runs without the lexer growing a keyword, a payload
field may still be called `like`, and `ON CONFLICT … DO NOTHING` cost the lexer nothing either.
The full-text conditions need even less — they're function names. This is why the keyword count
above has stopped predicting how much SQL runs.

### Statements

| Statement | State | Condition |
| --- | --- | --- |
| `SELECT … FROM <collection>` | works | Scrolls by cursor; `*` returns every column but the vectors |
| `SELECT … FROM system.<table>` | works | Twelve tables; a column list and `LIMIT` only |
| `SELECT` without `FROM` | partial | Constants only; a column or `*` is an error |
| `SELECT COUNT(*)` | works | One exact count call; must be the only select item unless grouped |
| `DELETE FROM … WHERE` | works | `WHERE` is required; the count returned is a separate count call |
| `TRUNCATE TABLE` | works | Deletes every point; the collection and its indexes stay |
| `TRUNCATE TABLE system.issues` | works | Clears the issues Qdrant reports, in one call; the count returned is 0, since Qdrant doesn't say how many there were. Every other write to a `system` table is an error |
| `DROP TABLE [IF EXISTS]` | works | |
| `CREATE TABLE [IF NOT EXISTS]` | partial | Vector and indexed payload columns; no `WITH (…)` |
| `INSERT INTO … (columns) VALUES …` | works | One upsert; the column list is required; a taken ID is `23505`; a row without `_id` gets a UUID, from `getGeneratedKeys` |
| `INSERT … ON CONFLICT (_id) DO NOTHING` | works | `insert_only`, and no ID lookup: Qdrant skips a point that's there |
| `INSERT … ON CONFLICT (_id) DO UPDATE [WHERE …]` | works | `upsert` mode, the `WHERE` as `update_filter`; replaces the whole point, so it takes no `SET` list |
| `UPDATE … SET … WHERE` | partial | Payload and vectors, in one batch; `WHERE` is required; `SET _id` is an error. Writing a vector reads the matching IDs first, so it's capped at 10,000 points |
| `SET qdrant.<option>`, `RESET`, `SHOW` | works | Seven request options, kept for the connection; other databases' settings are errors |
| `CREATE INDEX`, `DROP INDEX` | works | One field; the type defaults to the column's; each type's `WITH` options checked; a second index is `42S11`, a missing one `42S12` |
| `CREATE [OR REPLACE] VIEW` / `ALTER VIEW … RENAME TO` / `DROP VIEW` | works | A view is `SELECT *` of one collection; a taken name is `42S01`, a missing view `42S02`; `OR REPLACE` is one atomic switch |
| `ALTER TABLE … ADD [COLUMN] [IF NOT EXISTS]` | works | A named vector: `VECTOR(n)`, `MULTIVECTOR(n)` or `SPARSE_VECTOR`, as `CREATE TABLE` declares one; a taken name is `42S21`. A payload column is an error naming `CREATE INDEX` |
| `ALTER TABLE … DROP [COLUMN] [IF EXISTS]` | works | A named vector, with every point's value for it; a missing one is `42S22`. `_vector` and payload fields are errors naming what to write |
| `ALTER TABLE … SET (…)` | works | A collection's `params`, `hnsw`, `optimizers`, `strict_mode`, `quantization` and `metadata`; a setting is written `hnsw.m`, `m` where one group has it, or with its group as JSON. An unknown name is an error, since Qdrant would drop it without a word |
| `ALTER TABLE … ALTER [COLUMN] v SET (…)` | works | One vector's settings: `on_disk`, `hnsw` and `quantization` for a dense one, `modifier` and `index` for a sparse one |
| `ALTER TABLE … RENAME`, `ALTER COLUMN … TYPE` | missing | Qdrant renames nothing and a vector's dimensions are fixed |
| `CALL` | missing | Not a keyword; `getProcedures` returns no rows |
| `JOIN`, `UNION`, subqueries | missing | Rejected on purpose — see below |
| Trailing `;` | works | One semicolon ends a statement; two statements in one call is an error |

A statement runs on `Statement` and on `PreparedStatement` alike. `prepareStatement` parses
once and reports syntax errors then, binding each `?` into the parsed tree rather than into the
SQL text — in `WHERE`, the select list, a search's vector, `embed(?, ?)`, `UPDATE`'s `SET` and
`INSERT`'s `VALUES`. `getParameterMetaData` reports the count with type `OTHER` (1111), and
`getMetaData` is `null` until the statement runs. `addBatch` and `executeBatch` run a prepared
`INSERT`, `UPDATE` or `DELETE` once per set of values; Qdrant has no transactions, so a
`BatchUpdateException` reports the sets that ran before a failure.

A write goes with the connection's `qdrant.wait`, `qdrant.ordering` and `qdrant.timeout`, a
read with its `qdrant.consistency` and `qdrant.timeout`, and a search with its `qdrant.hnsw_ef`,
`qdrant.exact` and `qdrant.indexed_only`; `SHOW ALL` lists them. A value is read as its column's
type in `INSERT` and `UPDATE`, since tools send a cell as text: `'49.5'` into a `DOUBLE` column
is stored as a number, and text that can't be one is `22018`.

### Clauses

| Clause | State | Condition |
| --- | --- | --- |
| `WHERE` | partial | Column-against-constant conditions only; needs no index at all |
| `LIKE` / `NOT LIKE` | partial | `'Desk%'` is `prefix`, `'%desk%'` is `substring`, no wildcard is `=`; case-sensitive. A suffix, an interior `%`, `_`, `'%'` and `ILIKE` are errors naming what to write |
| `text_match`, `text_any` | works | `match` text and text_any; no index needed, a text index makes them fast |
| `phrase_match` | partial | Needs a text index built with `phrase_matching`, checked first because Qdrant answers without one with no points |
| `LIMIT n` | works | A literal; `LIMIT ?` is rejected at parse time |
| `OFFSET n` | partial | Every kind of read; Qdrant applies it only for a search, so the others skip in the driver, up to 10,000 rows |
| `ORDER BY _id` | partial | Ascending only; `DESC` is an error, since Qdrant scrolls by ID upwards |
| `ORDER BY <field>` | partial | Needs an `integer`, `float` or `datetime` payload index, and a bound |
| `ORDER BY similarity(v, …) DESC` | works | Needs a bound; `ASC` is an error; dense vectors, or any vector via `embed()` |
| `ORDER BY` on two things | missing | Qdrant orders by one field or ranks by one score |
| `GROUP BY <field>` + `COUNT(*)` | partial | Needs a `keyword`, `integer`, `bool` or `uuid` index, and a bound |
| `GROUP BY` on two fields | missing | Qdrant facets one field at a time |
| `HAVING` | missing | Not a keyword: `Expected the end of the statement, found 'HAVING'` |
| `SELECT DISTINCT` | missing | A keyword on purpose, so the error names the facet API |
| Table alias, qualified names | works | `SELECT t.* FROM collections.docs t`, as DataGrip generates |
| Nested payload fields | works | `"specs.color"`, and bare `specs.color` where no table is named `specs` |

Two things surprise people: where the bound on a read comes from, and which index a clause
needs.

**The bound.** Qdrant answers an ordered read, a facet and a search in a single page with no
cursor to ask for another, so something has to say how many rows to take. Three things can:
the statement's `LIMIT`, its `setMaxRows`, or the connection's `defaultLimit`, which is 1000.
When the default is what bound it, the statement succeeds and warns, with SQLState `01000`:

```
SELECT name, price FROM sample_products ORDER BY price DESC
  5 rows, and a warning: ORDER BY read at most 1000 rows, the connection's defaultLimit,
  because the statement gave no LIMIT and set no maximum rows
```

Setting `defaultLimit=0` turns that warning into an error instead — `ORDER BY needs a LIMIT:
Qdrant answers an ordered read in one page, so a statement says how many points to read` —
which is the setting to choose if a quietly bounded read is worse than a failed one. A plain
unordered `SELECT` is never bounded this way: it pages by cursor and returns the whole
collection.

**The index.** This requirement is Qdrant's, not the driver's — `order_by` needs a range
index, `facet` needs a countable one — but the driver checks it against the collection's
payload schema before calling, so the error names the index the field actually has:

```
SELECT name FROM sample_products ORDER BY name LIMIT 3
  Ordering by 'name' of sample_products needs a range index on it: Qdrant orders by a field
  indexed as integer, float, or datetime, and this one is indexed as keyword

SELECT price, COUNT(*) FROM sample_products GROUP BY price LIMIT 5
  Grouping by 'price' of sample_products needs an index Qdrant can count values of: it counts
  a field indexed as keyword, integer, bool, or uuid, and this one is indexed as float
```

Where the field is a number or a date stored without a range index, the fix is one statement
now: `CREATE INDEX ON docs (year)`, and the next statement orders by it. Ordering by text stays
out of reach, since Qdrant orders only by `integer`, `float` and `datetime` indexes.

`WHERE`, by contrast, needs no index. Qdrant will filter an unindexed field, just slowly, so
`WHERE in_stock = true` runs against `sample_products` even though `in_stock` was found by
sampling points and has no payload index.

### `WHERE` condition mapping

Every condition compares one column with constants, which is the whole of what a Qdrant filter
can express. The mapping below is what `FilterPlanner` emits.

| SQL | Qdrant condition |
| --- | --- |
| `AND`, `OR`, `NOT` | `must`, `should`, `must_not`, with `NOT` pushed down to the leaves |
| `lang = 'en'`, `year = 2024`, `active = true` | `match: {value}` |
| `price = 39.9`, `=` on a `FLOAT` field, `=` on a datetime | `range` with `gte` and `lte` both set to the value |
| `<`, `<=`, `>`, `>=` on numbers | `range` |
| `<`, `<=`, `>`, `>=` on a `DATETIME` column | `range` with the value as RFC 3339 text |
| `IN (…)` of all-text or all-whole-number values | `match: {any}` |
| `IN (…)` of mixed values | `should` over one `match` each |
| `NOT IN`, `<>`, `!=`, `NOT (…)` | `must_not` of the condition **and** `is_empty`, so a missing field does not match |
| `name LIKE 'Desk%'` | `match: {prefix}` |
| `name LIKE '%desk%'` | `match: {substring}` |
| `name LIKE 'Desk lamp'` | `match: {value}`: a pattern with no wildcard |
| `name NOT LIKE 'Desk%'` | `must_not` of the match **and** `is_empty` |
| `text_match(title, 'refund policy')` | `match: {text}`: every word |
| `text_any(title, 'refund shipping')` | `match: {text_any}`: any word |
| `phrase_match(title, 'refund policy')` | `match: {phrase}`: the words in order |
| `field IS NULL` | `is_empty`: missing, `null`, or `[]` |
| `field IS NOT NULL` | `must_not` of `is_empty` |
| `vector IS NULL` / `IS NOT NULL` | `has_vector`, negated or not, on named vectors and on the unnamed one, which `SET _vector = NULL` can take away |
| `_id = 7`, `_id IN (7, '5c56…')`, `_id = '7'` | `has_id`; a value must be a non-negative whole number or a UUID, and text that holds a whole number is that number, as DataGrip sends it |
| `_id NOT IN (…)` | `must_not` of `has_id` |

The null handling is the part that took thought and is easy to regress: SQL says a comparison
is false for a missing field and so is its negation, so `lang <> 'en'` must not return points
with no `lang`. Each negated condition therefore carries an `is_empty` alongside it inside the
same `must_not`.

### Rejected on purpose

These are errors with an explanation, not silently different behaviour. The reason is always
the same: Qdrant cannot run them, and the alternative is the driver reading a whole collection
to compute them client-side, which would turn a fast query into an unbounded one.

```
SELECT name FROM sample_products WHERE price > cost
  Expected a value, found 'cost' at line 1, column 48

SELECT name FROM sample_products WHERE LOWER(name) = 'desk lamp'
  'LOWER' isn't supported in WHERE yet: a condition compares a column with constants

SELECT SUM(price) FROM sample_products
  'SUM' isn't an aggregate this driver has: Qdrant counts points, through COUNT(*), and
  computes no other aggregate over a collection

SELECT COUNT(*), name FROM sample_products
  COUNT(*) is a row of its own: a statement that counts a collection's points selects nothing
  besides it, unless it groups them with GROUP BY

SELECT name FROM sample_products ORDER BY price, name LIMIT 3
  Ordering by more than one thing isn't supported yet

SELECT _id, _score FROM sample_products LIMIT 1
  Column '_score' is only in a similarity search: add ORDER BY similarity(vector, [0.1, ...])
  DESC

SELECT name FROM sample_products WHERE name = NULL
  Comparing 'name' with NULL is never true: use IS NULL or IS NOT NULL

UPDATE sample_products SET name = 'x'
  UPDATE without WHERE would change every point of sample_products: add a WHERE, such as
  WHERE _id = 7

INSERT INTO sample_products (_id, name) VALUES (1, 'dup')
  Point 1 is already in sample_products: INSERT adds new points, and UPDATE changes one. Add
  ON CONFLICT (_id) DO NOTHING to skip the points that are there, or DO UPDATE to replace them

INSERT INTO sample_products (_id, name) VALUES (1, 'x') ON CONFLICT (name) DO NOTHING
  ON CONFLICT takes _id, and 'name' isn't it: a Qdrant point has one key, its ID, so that's
  the only column two points can collide on

INSERT INTO sample_products (_id, name) VALUES (1, 'x') ON CONFLICT (_id) DO UPDATE SET name = 'x'
  DO UPDATE takes no SET: Qdrant replaces the whole point with the row the statement gives, so
  write ON CONFLICT (_id) DO UPDATE and give every column the point should have

SET search_path = public
  'search_path' isn't an option this driver has: it has qdrant.consistency, qdrant.ordering,
  qdrant.wait, qdrant.timeout, qdrant.hnsw_ef, qdrant.exact, qdrant.indexed_only
```

A vector's dimensions are checked against the collection before the search is sent, so a typo
is a driver error rather than a Qdrant one: `Vector '_vector' of sample_products has 4
dimensions, the vector searching it has 2`. A sparse vector or a multivector cannot be searched
with a vector written out — the error points at `embed('text', 'qdrant:qdrant/bm25')`, which
does work, since Qdrant builds the sparse vector itself.

### Vector search and `embed()`

A search is one Query API call with the `WHERE` filter and the `LIMIT`, returning `_score`:

```
SELECT _id, _score, name FROM sample_products
WHERE price > 20 ORDER BY similarity(_vector, [0.1, 0.2, 0.3, 0.4]) DESC LIMIT 2
  (_id:VARCHAR, _score:DOUBLE, name:VARCHAR)
  [3 | 0.78904533 | Standing desk] [4 | 0.7438123 | Monitor arm]
```

`embed('text', 'provider:model')` works for all three providers. `qdrant:` sends the text to
Qdrant inside the same query body, so no new route is involved and `qdrant:qdrant/bm25` needs
nothing installed; `openai:` and `voyage:` call the provider from the driver and need a key in
the connection properties. Naming no model is an error unless the collection's
`runax.embed.<vector>` metadata holds one, which is deliberate — a vector searched with the
wrong model returns plausible nonsense rather than failing. `embed()` is also a value of
`INSERT`: Qdrant embeds a `qdrant:` model as it stores the point, and the driver embeds any
other first, one provider call per row. `UPDATE` doesn't take it yet, since `UPDATE` doesn't
change vectors.

### The JDBC surface

`java.sql.DatabaseMetaData` declares 177 methods. `QdrantDatabaseMetaData` implements **20** of
them — the 19 that a tool needs to draw a tree and a grid, `getSchemas` twice for its two
overloads — and `AbstractDatabaseMetaData` supplies harmless defaults for the other 157, so no
tool ever sees an `AbstractMethodError`. What is implemented is `getTables`, `getColumns`,
`getSchemas`, `getCatalogs`, `getTableTypes`, `getPrimaryKeys`, `getIndexInfo`, `getTypeInfo`,
and the product, driver and version strings. `getProcedures` is a default and returns no rows,
so the `CALL` procedures the dialect spec plans are not announced yet.

Reads are paged by the scroll cursor rather than loaded whole. `setReadOnly(true)` refuses
every write with SQLState `25006`, and still runs `SET`, which changes only the connection.
`getGeneratedKeys` returns the IDs the latest `INSERT` added, given or made, as one `_id`
column. `prepareCall` fails with `Qdrant has no stored procedures`.
`setAutoCommit(false)` is accepted and means nothing, since Qdrant has no transactions and
every statement applies on its own; the buffered-write mode that would give it meaning is on the
roadmap. Note that most `SQLFeatureNotSupportedException`s above carry a null SQLState — the
message is good, the state is not set.

## Gaps that matter, ranked

The gaps that led this list when it was first measured — no `INSERT`, no `UPDATE`, no
`OFFSET`, no way to manage an index, vectors that could be written once and never changed, no
way to match text, an `INSERT` that could only insert, and a provider call per row — are closed:
the driver can create a collection, fill it in one embedding call, index it, save a grid's edits,
page through it, switch an alias, re-embed it, match a pattern or a phrase in it, upsert into it,
and delete from it. What remains, most important first:

1. **Writing a vector costs a read.** Qdrant's `update_vectors` takes point IDs and rejects a
   filter alone, so `UPDATE … SET dense = …` scrolls the matching IDs first and is capped at
   10,000 points. Re-embedding a collection larger than that still means splitting the statement
   up. Only Qdrant growing a filter form for that operation would lift the cap.
2. **Text matching reaches the index but not the ranking.** `text_match` filters by a text
   index; Qdrant's `text` *query*, which ranks by full-text relevance and is what a BM25-style
   search needs, has no SQL yet, so `ORDER BY text_rank(title, 'refund policy') DESC` from the
   dialect spec is still the target. `at_least` (`min_should`), `BETWEEN`, and the geo and
   nested-array conditions are the rest of the filter surface.
3. **One `embed()` per statement, not per collection.** A batch now goes to the provider in one
   request, but each statement embeds on its own: loading a collection through a thousand
   `INSERT`s is still a thousand calls, where one `INSERT` of a thousand rows is one. Nothing in
   the driver can join statements, so this is a note for whoever writes the loading side, not a
   gap to close here.

Below that line the remaining gaps are mostly ones a SQL user would not reach for first: hybrid
search and fusion (one route, many body shapes), collection settings at creation (`CREATE TABLE … WITH (…)`,
which `ALTER TABLE … SET (…)` covers afterwards, all but the shard count), `CALL` procedures for snapshots and shard keys, `WHERE` and `ORDER BY` over the
`system` tables, and the request options still to come
(`shard_key`, quantization, a `/*+ … */` hint for one statement).

## Where the documents and the code disagree

[ROADMAP.md](../ROADMAP.md) has the plan and the ticks, and
[docs/specs/sql-dialect.md](specs/sql-dialect.md) has the full target dialect, including the
syntax for everything above that does not exist yet. Neither is duplicated here.

At this commit they agree with the code. The four places they didn't — the README saying the
driver "doesn't run SQL yet", the dialect spec's header saying it "only describes collections
through `DatabaseMetaData`", that spec's *Not supported* section denying it reads telemetry,
metrics, cluster state and issues while its own *System tables* section documented all four,
and the roadmap's ticked `COUNT(*)` item still listing filtered and grouped counts as "to
come" — are fixed.

The failure mode to watch for is the one those four shared: prose that was true when written
and that nothing forces to be re-read. Two habits keep it from coming back. A sentence saying
what the driver can't do yet is worth a glance whenever that thing lands, which is why each
section of the dialect spec marks what runs rather than hiding it in prose. And a doc written
on a branch shouldn't say "this branch": it stops being one at merge, and that phrase has
already gone stale twice here.

Everything the roadmap ticks does behave as described, including the limits it admits to:
`LIMIT ?` and `OFFSET ?` are rejected, `ORDER BY _id DESC` is an error, dense vectors are the
only ones a written-out vector can search, and an ordered, grouped or ranked read is bounded
by a `LIMIT`, by `setMaxRows`, or by `defaultLimit` with a warning.
