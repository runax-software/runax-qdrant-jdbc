# Runax JDBC Driver for Qdrant — Roadmap

Planned work, roughly in the order it will land.

- [ ] **Connection** — `jdbc:qdrant://host:6333`, TLS, and the Qdrant API key taken
  from the password field.
- [ ] **Collections as tables** — `DatabaseMetaData` lists collections, so DataGrip's
  database explorer fills in on its own.
- [ ] **Columns** — `id` (text, since Qdrant IDs are unsigned 64-bit integers or UUIDs),
  a raw `payload` JSON column, one column per indexed payload field (found from the
  collection's payload indexes and a sample of points), and vector columns for named,
  sparse, and multi-vectors.
- [ ] **SELECT** — `WHERE` mapped to Qdrant filters, `LIMIT`, and result sets that page
  through Qdrant's scroll cursor instead of loading everything. Vectors are fetched only
  when a query names their column.
- [ ] **COUNT(\*)** through the count API.
- [ ] **Similarity search with vector literals** —
  `ORDER BY similarity(vector, [0.1, ...])` through the Query API, with a `_score`
  column.
- [ ] **DataGrip compatibility** — harmless defaults for the `Connection` and
  `Statement` methods DataGrip calls on every connection, and support for the SQL it
  generates when opening, filtering, and sorting a table.
- [ ] **Integration tests** against Qdrant in Docker.
- [ ] **Packaging** — one self-contained jar, a DataGrip setup guide, and publishing to
  Maven Central as `com.runaxsoftware:qdrant-jdbc`.
- [ ] **`embed('text')`** through any OpenAI-compatible embeddings endpoint (OpenAI,
  Ollama, vLLM, LM Studio).
- [ ] **Qdrant Cloud inference** — let Qdrant embed the text server-side where available.
- [ ] **Embedding model per vector** — configured per collection and named vector, not
  per connection, so a query can't silently use the wrong model.
- [ ] **GROUP BY** with `COUNT(*)` through the facet API.
- [ ] **ORDER BY** on range-indexed payload fields.
- [ ] **Prepared statements** with parameters.
- [ ] **Grid edits** — `UPDATE ... WHERE id = ?` from DataGrip's data editor, mapped to
  Qdrant's set-payload call.
- [ ] **DELETE** mapped to deleting points.
- [ ] **DataGrip plugin** (`runax-qdrant-datagrip`, a separate repository) — a search
  panel that writes SQL into a console, collection tools, and a bundled driver
  definition.
- [ ] **Creating collections** — needs SQL syntax for vector size and distance.

## Not planned

- **JOINs and arbitrary aggregates** (`SUM`, `AVG`, ...) — Qdrant can't run them, and
  emulating them means scanning whole collections on the client. They fail with an
  error instead.
- **Native code and local embedding models** — the driver stays pure Java.
