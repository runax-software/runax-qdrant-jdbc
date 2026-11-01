# Coverage — how much of Qdrant, and how much SQL

Two questions this document answers precisely: how much of Qdrant's REST API the driver calls,
and how much SQL it accepts. Both are measured, not estimated — every number below comes from
the code or from a server that was asked. The point is to make the gaps legible, so a
maintainer can tell a missing feature that blocks real use from one that nobody would notice.

**Measured against** driver commit `9393e92` (with `SET`, `UPDATE` and `INSERT`, #27, #28 and
#31) and Qdrant **1.20.0** (build
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

Qdrant 1.20 documents **69 routes**. The driver calls **24** of them, which is **35%**. The
counts below are per area, using Qdrant's own OpenAPI tags, so they can be recomputed from the
spec without judgement calls.

| Area | Covered | Total | What the covered routes are for |
| --- | --- | --- | --- |
| Collections | 5 | 9 | Listing and describing tables, `CREATE TABLE`, `DROP TABLE`, `IF NOT EXISTS` |
| Points | 7 | 14 | `SELECT` (scroll), `COUNT(*)`, `GROUP BY` (facet), `DELETE`, `TRUNCATE`, `UPDATE` (batch), `INSERT` (upsert, and the ID check before it) |
| Search | 1 | 5 | `ORDER BY similarity(...) DESC`, including `embed()` |
| Aliases | 1 | 3 | Listing aliases as views, and `system.aliases` |
| Indexes | 1 | 2 | The payload indexes `CREATE TABLE` declares |
| Snapshots | 2 | 17 | `system.snapshots` only |
| Distributed | 3 | 9 | `system.cluster`, `system.peers`, `system.shards`, `system.shard_keys` |
| Service | 3 | 6 | Version on connect, `system.telemetry`, `system.metrics` |
| Beta | 1 | 2 | `system.issues` |
| Quotas | 0 | 2 | — |
| **Total** | **24** | **69** | |

### The routes the driver calls

Sixteen come from `QdrantGateway`, which is the only place that speaks HTTP, and eight more
from `QdrantSystemTables`, which reads endpoints the gateway has no model for because their
shape depends on the Qdrant version.

| Route | Called by | For |
| --- | --- | --- |
| `GET /` | `serverVersion` | The product version on connect; the only call that needs no API key |
| `GET /collections` | `listCollections` | `getTables`, and the first call of four system tables |
| `GET /collections/{c}` | `collectionInfo` | Columns, vector sizes, payload indexes, embedding metadata |
| `GET /collections/{c}/exists` | `collectionExists` | `CREATE TABLE IF NOT EXISTS` |
| `PUT /collections/{c}` | `createCollection` | `CREATE TABLE` |
| `DELETE /collections/{c}` | `deleteCollection` | `DROP TABLE`, and the rollback when an index fails |
| `PUT /collections/{c}/index` | `createIndex` | The `… KEYWORD INDEX` columns of a `CREATE TABLE` |
| `POST /collections/{c}/points/scroll` | `scroll` | `SELECT`, paged by cursor, and the sample that infers columns |
| `POST /collections/{c}/points/query` | `query` | `ORDER BY similarity(...) DESC` |
| `POST /collections/{c}/points/count` | `count` | `COUNT(*)`, and the row count a `DELETE` or `UPDATE` reports |
| `POST /collections/{c}/points/delete` | `deletePoints` | `DELETE … WHERE`, `TRUNCATE TABLE` |
| `POST /collections/{c}/points/batch` | `updatePoints` | `UPDATE`: set, delete, overwrite and clear payload in one call |
| `POST /collections/{c}/points` | `existingIds` | `INSERT`: the IDs a statement gives, looked up so a taken one is an error |
| `PUT /collections/{c}/points` | `insertPoints` | `INSERT`: one upsert in `insert_only` mode |
| `POST /collections/{c}/facet` | `facet` | `GROUP BY … COUNT(*)` |
| `GET /aliases` | `listAliases` | Aliases as views, and `system.aliases` |
| `GET /snapshots` | `QdrantSystemTables` | `system.snapshots`, the whole-storage rows |
| `GET /collections/{c}/snapshots` | `QdrantSystemTables` | `system.snapshots`, the per-collection rows |
| `GET /collections/{c}/shards` | `QdrantSystemTables` | `system.shard_keys` |
| `GET /collections/{c}/cluster` | `QdrantSystemTables` | `system.shards` |
| `GET /cluster` | `QdrantSystemTables` | `system.cluster` and `system.peers` |
| `GET /telemetry` | `QdrantSystemTables` | `system.telemetry` |
| `GET /metrics` | `QdrantSystemTables` | `system.metrics`; the one route that answers text, not JSON |
| `GET /issues` | `QdrantSystemTables` | `system.issues` |

The driver also calls `POST /embeddings` on `api.openai.com` or `api.voyageai.com` for
`embed('text', 'openai:…')`. That is not a Qdrant route and is not counted here.

### Why 35% means less than it looks, in both directions

**The denominator is padded.** Of the 45 routes the driver does not call, 15 are snapshot
plumbing — creating, downloading, uploading and recovering snapshots of a collection or of one
shard — and 6 more are cluster surgery: moving a shard, dropping a peer, recovering Raft. No
SQL client would call those; they belong to an operator with a shell, and exposing them through
JDBC would mean inventing `CALL` syntax for operations that have nothing to do with rows. The
dialect spec does plan exactly that (see its *Operations* section), but as procedures, and a
procedure that wraps one route is not what "covering an API" usually means. Set aside the 15
unused snapshot routes, the 6 unused cluster ones, the 3 health probes (`healthz`, `livez`,
`readyz`) and the 2 quota routes, and 43 remain, of which the driver calls 24 — **56%**.
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
their jobs through `points/batch`, in one call; nothing is missing there. Changing a vector is
different: `PUT …/points/vectors` and `…/vectors/delete` are unused, and no statement reaches
them either.

### Spec and server disagree, so both were checked

The published spec and the running binary are not the same list, and probing found three
differences worth recording, because a coverage number computed from either one alone would be
slightly wrong.

- **Eight routes the 1.20 spec no longer documents, the server still serves.** The legacy
  search family — `points/search`, `search/batch`, `search/groups`, `points/recommend` and its
  `batch` and `groups`, `points/discover` and `discover/batch` — is in the 1.15 spec, gone from
  the 1.20 spec, and alive on a 1.20 server: each answers a JSON validation error rather than
  an empty 404. They were superseded by the Query API, not removed. Counting them puts the live
  surface at 77 routes and the driver at 31%; this document uses the documented 69, because the
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

The ceiling is the lexer. `Lexer.java` recognises **30 keywords**, and a word outside that set
becomes an identifier, so `HAVING`, `LIKE`, `BETWEEN`, `JOIN`, `UNION`, `VIEW`, `INDEX`,
`ALTER` and `CALL` cannot be lexed as syntax at all. `Parser.java` wires **ten statement
kinds**, and `Plan.java`'s sealed interface permits **thirteen plan kinds** — the best single
index of what can run. One parser class is still an empty four-line stub with no body and no
caller: `IndexParser`. Some words mean something in one statement only, and aren't keywords,
so they stay usable as names: the type names a `CREATE TABLE` uses (`VECTOR`, `KEYWORD`,
`DISTANCE`, `MODIFIER`), which `CreateParser` reads where they can appear, and `RESET`, `SHOW`,
`ALL`, `TO` and `DEFAULT`, which `OptionParser` reads the same way.

### Statements

| Statement | State | Condition |
| --- | --- | --- |
| `SELECT … FROM <collection>` | works | Scrolls by cursor; `*` returns every column but the vectors |
| `SELECT … FROM system.<table>` | works | Ten tables; a column list and `LIMIT` only |
| `SELECT` without `FROM` | partial | Constants only; a column or `*` is an error |
| `SELECT COUNT(*)` | works | One exact count call; must be the only select item unless grouped |
| `DELETE FROM … WHERE` | works | `WHERE` is required; the count returned is a separate count call |
| `TRUNCATE TABLE` | works | Deletes every point; the collection and its indexes stay |
| `DROP TABLE [IF EXISTS]` | works | |
| `CREATE TABLE [IF NOT EXISTS]` | partial | Vector and indexed payload columns; no `WITH (…)` |
| `INSERT INTO … (columns) VALUES …` | works | One `insert_only` upsert; the column list is required; a taken ID is `23505`; a row without `_id` gets a UUID, from `getGeneratedKeys` |
| `UPDATE … SET … WHERE` | partial | Payload only, in one batch; `WHERE` is required; `SET` on a vector or `_id` is an error |
| `SET qdrant.<option>`, `RESET`, `SHOW` | works | Seven request options, kept for the connection; other databases' settings are errors |
| `INSERT … ON CONFLICT` | missing | `ON` isn't a keyword, so it fails at the end of `VALUES` |
| `CREATE INDEX`, `DROP INDEX` | missing | `CREATE` parses, then `Expected TABLE, found 'INDEX'` |
| `CREATE VIEW` / `ALTER VIEW` / `DROP VIEW` | missing | Aliases are listed as views but cannot be managed |
| `ALTER TABLE` | missing | Not a keyword: `Expected SELECT, INSERT, UPDATE, …, found 'ALTER'` |
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
| `LIMIT n` | works | A literal; `LIMIT ?` is rejected at parse time |
| `OFFSET n` | missing | `OFFSET isn't supported yet`, except in a `SELECT` without `FROM` |
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
| `field IS NULL` | `is_empty`: missing, `null`, or `[]` |
| `field IS NOT NULL` | `must_not` of `is_empty` |
| `vector IS NULL` / `IS NOT NULL` | `has_vector`, negated or not; an unnamed vector is an error, as every point has one |
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
  Point 1 is already in sample_products: INSERT adds new points, and UPDATE changes one

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

The two gaps that led this list when it was first measured, no `INSERT` and no `UPDATE`, are
closed: the driver can create a collection, fill it, save a grid's edits, and delete from it.
What remains, most important first:

1. **`OFFSET` is missing.** One clause, already supported by the route the driver calls —
   `points/scroll` takes `offset`, and the Query API takes it for searches — and every grid
   needs it to page past the first screen. The roadmap names this as the last piece of DataGrip
   compatibility, which is right: the gap is not that someone cannot express a query, it is that
   a tool's scrollbar does not work. It is in review as #30.
2. **No index management, which strands the clauses that depend on it.** `CREATE INDEX` and
   `DROP INDEX` do not parse (`IndexParser` is an empty stub), and
   `DELETE /collections/{c}/index/{field_name}` is unused, while `PUT …/index` is only ever
   called from inside a `CREATE TABLE`. The consequence compounds: `ORDER BY` needs a range
   index and `GROUP BY` needs a countable one, so sorting by a keyword column — the most
   ordinary thing a person does to a grid — fails, and the fix cannot be said in SQL.
3. **`LIKE` and text matching.** `title` in `sample_articles` carries a Qdrant `text` index and
   nothing in SQL reaches it; `match: {text}` and `match: {prefix}` are fields of a filter the
   driver already builds, so this costs no new route. `WHERE name LIKE '%lamp%'` is what people
   type first, and today `LIKE` is not even a keyword, so it fails as a syntax error.
4. **Vectors can be written once and never changed.** `INSERT` stores them, but `UPDATE … SET
   dense = …` is an error, and `PUT …/points/vectors` and `…/vectors/delete` are unused. In a
   grid, editing a vector cell fails while every other cell saves; re-embedding a collection
   after changing models means deleting and re-inserting its points.
5. **`INSERT` only inserts.** `INSERT … ON CONFLICT (_id) DO UPDATE`, Qdrant's own upsert, has
   no SQL yet, so loading a batch that may repeat IDs means splitting it into `INSERT`s and
   `UPDATE`s. Rows the driver embeds are also embedded one provider call at a time.

Below that line the remaining gaps are mostly ones a SQL user would not reach for first: hybrid
search and fusion (one route, many body shapes), `CALL` procedures for snapshots and shard
keys, `WHERE` and `ORDER BY` over the `system` tables, and the request options still to come
(`shard_key`, quantization, a `/*+ … */` hint for one statement).

## Where the documents and the code disagree

[ROADMAP.md](../ROADMAP.md) has the plan and the ticks, and
[docs/specs/sql-dialect.md](specs/sql-dialect.md) has the full target dialect, including the
syntax for everything above that does not exist yet. Neither is duplicated here. Both are
ahead of or behind the code in four places, all of which are stale prose rather than wrong
plans:

- The **README status block** says the driver "doesn't run SQL yet". It runs everything in the
  tables above.
- The **dialect spec's header** says "today the driver only describes collections through
  `DatabaseMetaData`". Same staleness, same fix.
- The dialect spec's **Not supported** section ends by saying the driver does not read
  telemetry, metrics, cluster state or issues yet. It reads all four, and the same file's
  *System tables* section documents them — the file contradicts itself.
- The roadmap's **`COUNT(*)`** item is ticked but still lists as "to come" both counting what a
  `WHERE` matches and a count per group through the facet API. Both work, and the `GROUP BY`
  item below it is ticked for the second one.

Everything else the roadmap ticks does behave as described, including the limits it admits to:
`LIMIT ?` and `OFFSET ?` are rejected, `ORDER BY _id DESC` is an error, dense vectors are the
only ones a written-out vector can search, and an ordered, grouped or ranked read is bounded
by a `LIMIT`, by `setMaxRows`, or by `defaultLimit` with a warning.
