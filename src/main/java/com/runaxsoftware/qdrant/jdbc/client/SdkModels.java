package com.runaxsoftware.qdrant.jdbc.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.RetrievedPoint;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Converts between the SDK's protobuf messages and the driver's own model.
 */
final class SdkModels {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private SdkModels() {
    }

    static CollectionInfo collectionInfo(String name, Collections.CollectionInfo info) {
        Collections.CollectionParams params = info.getConfig().getParams();
        List<VectorField> vectors = new ArrayList<>();
        Collections.VectorsConfig dense = params.getVectorsConfig();

        if (dense.hasParams()) {
            vectors.add(vectorField("", dense.getParams()));
        } else if (dense.hasParamsMap()) {
            for (Map.Entry<String, Collections.VectorParams> vector
                : dense.getParamsMap().getMapMap().entrySet()) {
                vectors.add(vectorField(vector.getKey(), vector.getValue()));
            }
        }

        for (String sparse : params.getSparseVectorsConfig().getMapMap().keySet()) {
            vectors.add(new VectorField(sparse, VectorKind.SPARSE, 0, null));
        }

        Map<String, String> indexes = new LinkedHashMap<>();
        for (Map.Entry<String, Collections.PayloadSchemaInfo> index
            : info.getPayloadSchemaMap().entrySet()) {
            indexes.put(index.getKey(), index.getValue().getDataType().name().toLowerCase(Locale.ROOT));
        }

        return new CollectionInfo(name, info.getPointsCount(), List.copyOf(vectors),
            java.util.Collections.unmodifiableMap(indexes));
    }

    private static VectorField vectorField(String name, Collections.VectorParams params) {
        VectorKind kind = params.hasMultivectorConfig() ? VectorKind.MULTIVECTOR : VectorKind.DENSE;
        return new VectorField(name, kind, (int) params.getSize(), params.getDistance().name());
    }

    static Point point(RetrievedPoint point) {
        return new Point(pointId(point.getId()), payload(point.getPayloadMap()), null);
    }

    /**
     * A point ID as JSON: a number for numeric IDs (unsigned), a string for UUIDs.
     */
    static JsonNode pointId(PointId id) {
        if (id.hasUuid()) {
            return JSON.textNode(id.getUuid());
        }

        long number = id.getNum();
        return number >= 0
            ? JSON.numberNode(number)
            : JSON.numberNode(new BigInteger(Long.toUnsignedString(number)));
    }

    /**
     * The reverse of {@link #pointId(PointId)}.
     */
    static PointId pointId(JsonNode id) {
        if (id.isTextual()) {
            return PointIdFactory.id(UUID.fromString(id.asText()));
        }

        return PointIdFactory.id(id.bigIntegerValue().longValue());
    }

    static ObjectNode payload(Map<String, Value> payload) {
        ObjectNode object = JSON.objectNode();

        for (Map.Entry<String, Value> field : payload.entrySet()) {
            object.set(field.getKey(), json(field.getValue()));
        }

        return object;
    }

    static JsonNode json(Value value) {
        switch (value.getKindCase()) {
            case DOUBLE_VALUE:
                return JSON.numberNode(value.getDoubleValue());
            case INTEGER_VALUE:
                return JSON.numberNode(value.getIntegerValue());
            case STRING_VALUE:
                return JSON.textNode(value.getStringValue());
            case BOOL_VALUE:
                return JSON.booleanNode(value.getBoolValue());
            case STRUCT_VALUE:
                return payload(value.getStructValue().getFieldsMap());
            case LIST_VALUE:
                ArrayNode array = JSON.arrayNode();
                for (Value element : value.getListValue().getValuesList()) {
                    array.add(json(element));
                }
                return array;
            default:
                return JSON.nullNode();
        }
    }
}
