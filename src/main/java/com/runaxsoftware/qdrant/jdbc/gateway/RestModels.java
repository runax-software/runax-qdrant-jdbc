package com.runaxsoftware.qdrant.jdbc.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.*;

import java.util.*;

final class RestModels {
    static QdrantCollection collection(String name, JsonNode info) {
        JsonNode params = info.path("config").path("params");
        List<VectorField> vectors = new ArrayList<>();
        JsonNode dense = params.path("vectors");

        if (dense.path("size").isNumber()) {  // one unnamed vector
            vectors.add(vectorField("", dense));
        } else {  // named vectors
            for (Map.Entry<String, JsonNode> vector : dense.properties()) {
                vectors.add(vectorField(vector.getKey(), vector.getValue()));
            }
        }

        for (Map.Entry<String, JsonNode> sparse : params.path("sparse_vectors").properties()) {
            vectors.add(new VectorField(sparse.getKey(), VectorKind.SPARSE, 0, null));
        }

        Map<String, PayloadIndexType> indexes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> index : info.path("payload_schema").properties()) {
            indexes.put(index.getKey(), PayloadIndexType.fromQdrant(index.getValue().path("data_type")
                .asText("")));
        }

        return new QdrantCollection(name, info.path("points_count").asLong(0),
            List.copyOf(vectors), Collections.unmodifiableMap(indexes));
    }

    static List<QdrantAlias> aliases(JsonNode result) {
        List<QdrantAlias> aliases = new ArrayList<>();

        for (JsonNode alias : result.path("aliases")) {
            aliases.add(new QdrantAlias(alias.path("alias_name").asText(),
                alias.path("collection_name").asText()));
        }

        return List.copyOf(aliases);
    }

    static ScrollPage scrollPage(JsonNode result) {
        List<Point> points = new ArrayList<>();

        for (JsonNode point : result.path("points")) {
            points.add(new Point(point.get("id"), present(point.get("payload")), present(point.get("vector"))));
        }

        JsonNode next = result.get("next_page_offset");
        return new ScrollPage(List.copyOf(points), next == null || next.isNull() ? null : next);
    }

    static List<ScoredPoint> scoredPoints(JsonNode result) {
        List<ScoredPoint> points = new ArrayList<>();

        for (JsonNode point : result.path("points")) {
            points.add(new ScoredPoint(
                new Point(point.get("id"), present(point.get("payload")),
                    present(point.get("vector"))),
                point.path("score").asDouble(0)));
        }

        return List.copyOf(points);
    }

    static List<FacetHit> facetHits(JsonNode result) {
        List<FacetHit> hits = new ArrayList<>();

        for (JsonNode hit : result.path("hits")) {
            hits.add(new FacetHit(present(hit.get("value")), hit.path("count").asLong(0)));
        }

        return List.copyOf(hits);
    }

    private static VectorField vectorField(String name, JsonNode config) {
        VectorKind kind = config.path("multivector_config").isObject() ? VectorKind.MULTIVECTOR : VectorKind.DENSE;
        return new VectorField(name, kind, config.path("size")
            .asInt(0), config.path("distance").asText(null));
    }

    private static JsonNode present(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }
}
