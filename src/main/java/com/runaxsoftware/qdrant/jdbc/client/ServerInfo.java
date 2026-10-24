package com.runaxsoftware.qdrant.jdbc.client;

/**
 * What a Qdrant server reports about itself.
 *
 * @param title   the server's title, {@code "qdrant - vector search engine"}
 * @param version the server version, such as {@code "1.20.0"}
 */
public record ServerInfo(String title, String version) {
}
