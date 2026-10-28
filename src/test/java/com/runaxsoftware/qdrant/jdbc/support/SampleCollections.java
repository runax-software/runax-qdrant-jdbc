package com.runaxsoftware.qdrant.jdbc.support;

import static com.runaxsoftware.qdrant.jdbc.support.Json.array;
import static com.runaxsoftware.qdrant.jdbc.support.Json.object;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantAlias;

import java.math.BigInteger;
import java.sql.SQLException;
import java.util.List;

/**
 * Three small collections that cover the cases the driver has to handle: an unnamed vector,
 * named dense, multi-, and sparse vectors, numeric and UUID point IDs (including the largest
 * unsigned one), indexed and unindexed payload fields, nested objects, arrays, fields with
 * mixed value types, and a payload field named like a reserved column. They're created through
 * Qdrant's REST API, the one the driver uses.
 */
public final class SampleCollections {

    /**
     * The collection names, without a prefix.
     */
    public static final List<String> NAMES = List.of("articles", "events", "products");

    /**
     * The largest point ID Qdrant accepts, an unsigned 64-bit integer.
     */
    public static final BigInteger LARGEST_ID = new BigInteger("18446744073709551615");

    private SampleCollections() {
    }

    /**
     * Creates the collections, replacing any with the same names.
     */
    public static void create(QdrantGateway gateway, String prefix) throws SQLException {
        delete(gateway, prefix);
        createProducts(gateway, prefix + "products");
        createArticles(gateway, prefix + "articles");
        createEvents(gateway, prefix + "events");
    }

    /**
     * Deletes the collections that exist.
     */
    public static void delete(QdrantGateway gateway, String prefix) throws SQLException {
        List<String> existing = gateway.listCollections();

        for (String name : NAMES) {
            if (existing.contains(prefix + name)) {
                gateway.request(HttpMethod.DELETE, "/collections/" + prefix + name, null);
            }
        }
    }

    /**
     * Points an alias at a collection: creates the alias, or moves it in one atomic change,
     * the way an application switches to a rebuilt collection.
     */
    public static void alias(QdrantGateway gateway, String alias, String collection)
        throws SQLException {
        ArrayNode actions = array();

        for (QdrantAlias existing : gateway.listAliases()) {
            if (existing.name().equals(alias)) {
                actions.add(object("delete_alias", object("alias_name", alias)));
            }
        }

        actions.add(object("create_alias", object("alias_name", alias,
            "collection_name", collection)));
        gateway.request(HttpMethod.POST, "/collections/aliases", object("actions", actions));
    }

    /**
     * Deletes the aliases whose names start with the prefix, leaving their collections.
     */
    public static void deleteAliases(QdrantGateway gateway, String prefix) throws SQLException {
        ArrayNode actions = array();

        for (QdrantAlias alias : gateway.listAliases()) {
            if (alias.name().startsWith(prefix)) {
                actions.add(object("delete_alias", object("alias_name", alias.name())));
            }
        }

        if (!actions.isEmpty()) {
            gateway.request(HttpMethod.POST, "/collections/aliases", object("actions", actions));
        }
    }

    /**
     * An unnamed vector, typed payload indexes, a nested indexed field, and arrays.
     */
    private static void createProducts(QdrantGateway gateway, String name) throws SQLException {
        createCollection(gateway, name, object("vectors", dense(4, "Cosine")));
        index(gateway, name, "name", "keyword");
        index(gateway, name, "price", "float");
        index(gateway, name, "created_at", "datetime");
        index(gateway, name, "specs.color", "keyword");
        index(gateway, name, "tags", "keyword");

        upsert(gateway, name,
            point(1, array(0.9, 0.1, 0.1, 0.2), object(
                "name", "Desk lamp", "price", 39.9, "in_stock", true,
                "tags", array("lighting", "office"), "created_at", "2024-03-01T10:00:00Z",
                "specs", object("color", "black", "weight", 1.2))),
            point(2, array(0.2, 0.8, 0.3, 0.1), object(
                "name", "Office chair", "price", 189.0, "in_stock", true,
                "tags", array("furniture", "office"), "created_at", "2024-04-12T08:30:00Z",
                "specs", object("color", "grey", "weight", 12.5))),
            point(3, array(0.1, 0.7, 0.6, 0.2), object(
                "name", "Standing desk", "price", 449.0, "in_stock", false,
                "tags", array("furniture"), "created_at", "2024-06-20T14:15:00Z",
                "specs", object("color", "white", "weight", 30))),
            point(4, array(0.7, 0.2, 0.5, 0.3), object(
                "name", "Monitor arm", "price", 89.5, "in_stock", true,
                "tags", array("office"), "created_at", "2024-07-02T09:00:00Z",
                "specs", object("color", "black", "weight", 2.1))),
            point(5, array(0.3, 0.3, 0.2, 0.9), object(
                "name", "Cable tray", "price", 19, "in_stock", true,
                "tags", array(), "created_at", "2024-08-15T16:45:00Z",
                "specs", object("color", "white", "weight", 0.4))));
    }

    /**
     * Named dense, multi-, and sparse vectors; UUID IDs; full-text and integer indexes.
     */
    private static void createArticles(QdrantGateway gateway, String name) throws SQLException {
        createCollection(gateway, name, object(
            "vectors", object(
                "dense", dense(4, "Dot"),
                "colbert", dense(2, "Cosine").set("multivector_config",
                    object("comparator", "max_sim"))),
            "sparse_vectors", object("keywords", object())));
        index(gateway, name, "lang", "keyword");
        index(gateway, name, "year", "integer");
        index(gateway, name, "title", "text");
        index(gateway, name, "author.name", "keyword");

        upsert(gateway, name,
            point("5c56c793-69f3-4fbf-87e6-c4bf54c28c26",
                articleVectors(new double[]{0.1, 0.2, 0.3, 0.4}, 1, 7),
                object("title", "Refund policy explained", "lang", "en", "year", 2024,
                    "author", object("name", "Ann", "country", "UK"))),
            point("0b3e9a2c-7a51-4c6e-9d0f-2f4a6c8e1b37",
                articleVectors(new double[]{0.4, 0.3, 0.2, 0.1}, 2, 9),
                object("title", "Rückgaberecht im Überblick", "lang", "de", "year", 2023,
                    "author", object("name", "Jonas", "country", "DE"))),
            point("e7c1f0d4-3b2a-4f6e-8a9d-1c5b7e3f2a60",
                articleVectors(new double[]{0.2, 0.2, 0.5, 0.1}, 1, 4),
                object("title", "Shipping times by region", "lang", "en", "year", 2025,
                    "author", object("name", "Mei", "country", "SG"))));
    }

    /**
     * No payload indexes, so every column comes from sampling: mixed number types, mixed
     * value types, an object in one point only, an empty payload, a payload field called
     * {@code id}, and numeric and UUID IDs side by side.
     */
    private static void createEvents(QdrantGateway gateway, String name) throws SQLException {
        createCollection(gateway, name, object("vectors", dense(2, "Euclid")));

        upsert(gateway, name,
            point(1, array(0.1, 0.2), object(
                "kind", "signup", "count", 3, "flag", true, "id", "evt-1")),
            point(2, array(0.3, 0.1), object(
                "kind", "purchase", "count", 2.5, "flag", "yes",
                "meta", object("source", "web", "campaign", "spring"))),
            point(LARGEST_ID, array(0.5, 0.5), object("kind", "refund", "count", 1)),
            point("9f8e7d6c-5b4a-4321-8fed-cba987654321", array(0.9, 0.4), object()));
    }

    private static ObjectNode dense(int size, String distance) {
        return object("size", size, "distance", distance);
    }

    private static JsonNode articleVectors(double[] dense, int firstTerm, int secondTerm) {
        return object(
            "dense", array(dense[0], dense[1], dense[2], dense[3]),
            "colbert", array(array(dense[0], dense[1]), array(dense[2], dense[3])),
            "keywords", object("indices", array(firstTerm, secondTerm),
                "values", array(0.5, 0.9)));
    }

    private static ObjectNode point(Object id, JsonNode vector, ObjectNode payload) {
        return object("id", id, "vector", vector, "payload", payload);
    }

    private static void createCollection(QdrantGateway gateway, String name, ObjectNode body)
        throws SQLException {
        gateway.request(HttpMethod.PUT, "/collections/" + name, body);
    }

    private static void index(QdrantGateway gateway, String collection, String field,
                              String type) throws SQLException {
        gateway.request(HttpMethod.PUT, "/collections/" + collection + "/index?wait=true",
            object("field_name", field, "field_schema", type));
    }

    private static void upsert(QdrantGateway gateway, String collection, ObjectNode... points)
        throws SQLException {
        gateway.request(HttpMethod.PUT, "/collections/" + collection + "/points?wait=true",
            object("points", array((Object[]) points)));
    }
}
