package com.runaxsoftware.qdrant.jdbc.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadPath;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadPath.isNested;
import static com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadPath.valueAt;

/**
 * Works out a collection's columns from its configuration and a sample of its points.
 *
 * <p>Columns come in this order:
 * <ol>
 *   <li>{@code _id}, the point ID ({@link DriverColumns#ID});</li>
 *   <li>one column per payload field, sorted by name: every top-level key in the sample plus
 *       every indexed path, so an indexed nested field such as {@code specs.color} gets a column
 *       of its own;</li>
 *   <li>{@code _payload}, the whole payload as JSON ({@link DriverColumns#PAYLOAD});</li>
 *   <li>one column per vector, as {@link VectorColumns} names and types them.</li>
 * </ol>
 *
 * <p>A payload field's type comes from its payload index when it has one, checked against the
 * values seen in the sample, or from the sample alone when it doesn't; objects, arrays, and fields
 * with mixed value types become JSON (see {@link ColumnType#forPayloadField}). Each field's
 * sample is collected in a {@link FieldSample}; nested indexed paths are read with
 * {@link PayloadPath}.
 *
 * <p>The driver's own columns start with {@code _}, so payload fields keep their names, even
 * {@code id}. A payload field named like one of {@link DriverColumns} or like a vector column
 * gets no column of its own; its values stay in {@code _payload}.
 */
public final class SchemaInference {
    private SchemaInference() {
    }

    /**
     * Works out a collection's columns: {@code _id}; one column per payload field, sorted by name;
     * {@code _payload}; then one column per vector. A payload field named like one of the driver's
     * columns or like a vector gets no column of its own; its values stay in {@code _payload}.
     *
     * @param collection the collection's configuration and payload indexes
     * @param sample     points read with their payloads, used to find fields and their types
     * @return the collection as a table
     */
    public static TableSchema infer(QdrantCollection collection, List<Point> sample) {
        List<ColumnDefinition> vectors = VectorColumns.columns(collection.vectors());
        Set<String> vectorNames = vectors.stream().map(ColumnDefinition::name).collect(Collectors.toSet());

        List<ColumnDefinition> columns = new ArrayList<>();
        columns.add(ColumnDefinition.id());

        for (Map.Entry<String, FieldSample> field : payloadFields(collection, sample).entrySet()) {
            String path = field.getKey();

            if (DriverColumns.isReserved(path) || vectorNames.contains(path)) continue;

            columns.add(payloadColumn(path, field.getValue()));
        }

        columns.add(ColumnDefinition.payload());
        columns.addAll(vectors);

        return new TableSchema(collection, List.copyOf(columns));
    }

    private static Map<String, FieldSample> payloadFields(QdrantCollection collection, List<Point> sample) {
        Map<String, FieldSample> fields = new TreeMap<>();

        for (Point point : sample) {
            if (point.payload() == null) continue;

            for (Map.Entry<String, JsonNode> entry : point.payload().properties()) {
                fields.computeIfAbsent(entry.getKey(), key -> new FieldSample()).observe(entry.getValue());
            }
        }

        for (Map.Entry<String, PayloadIndexType> index : collection.payloadIndexes().entrySet()) {
            String path = index.getKey();
            FieldSample field = fields.computeIfAbsent(path, key -> new FieldSample());
            field.indexedAs(index.getValue());

            if (isNested(path)) {
                for (Point point : sample) {
                    field.observe(valueAt(point.payload(), path));
                }
            }
        }

        return fields;
    }

    private static ColumnDefinition payloadColumn(String path, FieldSample field) {
        PayloadIndexType index = field.indexType();
        ColumnType type = ColumnType.forPayloadField(ColumnType.forIndex(index), field.kinds());
        String remarks = (isNested(path) ? "Nested payload field" : "Payload field")
            + (index == null ? "(not indexed)" : " (" + index.qdrantName() + " index)");

        return ColumnDefinition.payloadField(path, type, remarks);
    }
}
