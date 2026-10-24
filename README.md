# Runax JDBC Driver for Qdrant

A JDBC driver that lets SQL tools browse and query [Qdrant](https://qdrant.tech)
collections with SQL, including vector similarity search. JetBrains DataGrip is the
first target; any JDBC client (DBeaver, BI tools) should work the same way.

> **Status: early development.** Nothing is published yet and the driver can't connect
> to Qdrant so far. Everything below describes the planned design — see
> [ROADMAP.md](ROADMAP.md) for the plan.

- **Collections as tables** — collections show up in the database explorer, and points
  as rows: an `id`, a raw `payload` JSON column, one column per indexed payload field,
  and the collection's vectors.
- **SQL over the Qdrant API** — `WHERE` becomes a Qdrant filter, plus `LIMIT`,
  `COUNT(*)`, `GROUP BY` through facets, and `ORDER BY` on range-indexed fields.
- **Similarity search** — rank points by similarity to a vector or to text embedded on
  the fly, with the score as a `_score` column.
- **Honest errors** — SQL that Qdrant can't run (joins, arbitrary aggregates) fails with
  a clear error instead of scanning a whole collection on the client.
- **Pure Java** — no native code; a single jar to drop into DataGrip.

## Connection

```
jdbc:qdrant://localhost:6333
```

The driver talks to Qdrant's REST API (port `6333` by default). Put the Qdrant API key
in the password field, so DataGrip keeps it in its credential store rather than in the
project's data source files.

## Querying

Regular SQL against a collection:

```sql
SELECT id, payload
FROM docs
WHERE lang = 'en' AND year >= 2024
LIMIT 50
```

Similarity search, combined with a filter:

```sql
SELECT id, _score, payload
FROM docs
WHERE lang = 'en'
ORDER BY similarity(dense, embed('refund policy')) DESC
LIMIT 10
```

`dense` is the name of the vector to search, and `embed(...)` turns text into a vector
with the embedding model configured for that vector. A vector literal works in its place.

### How SQL maps to Qdrant

| SQL | Qdrant | Notes |
| --- | --- | --- |
| `WHERE` on payload fields | Filter (`must` / `should` / `must_not`) | Fast with payload indexes |
| `LIMIT` | `limit` | |
| `OFFSET n` | Scroll | Qdrant pages with a cursor, so the driver skips `n` points |
| `ORDER BY field` | Scroll `order_by` | Needs a range index on the field |
| `COUNT(*)` | Count API | |
| `GROUP BY field` + `COUNT(*)` | Facet API | Needs a keyword index on the field |
| `ORDER BY similarity(...)` | Query API | Score returned as `_score` |
| `JOIN`, `SUM`, `AVG`, subqueries | — | Rejected with an error |

## Building

```bash
./gradlew build
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for prerequisites and running Qdrant locally.

## Related

A DataGrip plugin with a search panel and collection tools is planned as a separate
project, `runax-qdrant-datagrip`, built on this driver.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE).

This project is not affiliated with or endorsed by Qdrant.
