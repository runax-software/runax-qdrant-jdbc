package com.runaxsoftware.qdrant.jdbc.support;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;

/**
 * Loads the sample collections into Qdrant as {@code sample_articles}, {@code sample_events},
 * and {@code sample_products}, for trying the driver by hand. Run it with
 * {@code ./gradlew seedQdrant}; see {@link TestQdrant} for choosing the server.
 */
public final class SeedSampleData {

    private static final String PREFIX = "sample_";

    private SeedSampleData() {
    }

    public static void main(String[] args) throws Exception {
        try (QdrantGateway gateway = TestQdrant.gateway()) {
            SampleCollections.create(gateway, PREFIX);
            System.out.println("Loaded " + SampleCollections.NAMES.stream()
                    .map(name -> PREFIX + name)
                    .toList() + " into Qdrant at " + gateway.baseUrl());
        }
    }
}
