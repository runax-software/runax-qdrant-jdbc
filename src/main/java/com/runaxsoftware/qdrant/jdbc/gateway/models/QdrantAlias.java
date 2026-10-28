package com.runaxsoftware.qdrant.jdbc.gateway.models;

/**
 * An alias: a second name for one collection. Qdrant accepts it wherever a collection name
 * goes, such as reading the collection or scrolling its points. Alias and collection names
 * share one namespace, and an alias always points at a collection, never at another alias.
 *
 * @param name       the alias name
 * @param collection the name of the collection it points at
 */
public record QdrantAlias(String name, String collection) {
}
