package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.SampleCollections;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * Checks the shaded jar users put into DataGrip: what it contains, and that it works on its
 * own in a separate class loader, as DataGrip loads drivers. Run through
 * {@code ./gradlew integrationTest}, which builds the jar; needs {@code docker compose up -d}.
 */
@Tag("integration")
class ShadedJarIntegrationTest {

    @Test
    void registersTheDriverAsAService() throws IOException {
        assertEquals("com.runaxsoftware.qdrant.jdbc.QdrantDriver",
            entry("META-INF/services/java.sql.Driver").strip());
    }

    @Test
    void relocatesJacksonsServiceFiles() throws IOException {
        String factories = entry(
            "META-INF/services/com.runaxsoftware.qdrant.jdbc.shaded.jackson.core.JsonFactory");

        assertTrue(factories.contains("com.runaxsoftware.qdrant.jdbc.shaded.jackson."), factories);

        try (JarFile jar = new JarFile(jar().toFile())) {
            assertEquals(List.of(), jar.stream().map(ZipEntry::getName)
                .filter(name -> name.startsWith("META-INF/services/com.fasterxml."))
                .toList());
        }
    }

    @Test
    void relocatesEveryDependency() throws IOException {
        List<String> leaked = new ArrayList<>();

        try (JarFile jar = new JarFile(jar().toFile())) {
            // Multi-release classes sit under META-INF/versions/<release>/, relocated as well
            jar.stream().map(ZipEntry::getName)
                .filter(name -> name.endsWith(".class"))
                .filter(name -> !name.replaceFirst("^META-INF/versions/\\d+/", "")
                    .startsWith("com/runaxsoftware/qdrant/jdbc/"))
                .forEach(leaked::add);
            assertNotNull(jar.getEntry(
                "com/runaxsoftware/qdrant/jdbc/shaded/jackson/databind/ObjectMapper.class"));
        }

        assertEquals(List.of(), leaked);
    }

    @Test
    void namesItsModuleAndVersion() throws IOException {
        try (JarFile jar = new JarFile(jar().toFile())) {
            Manifest manifest = jar.getManifest();

            assertEquals("com.runaxsoftware.qdrant.jdbc",
                manifest.getMainAttributes().getValue("Automatic-Module-Name"));
            assertEquals(DriverInfo.VERSION,
                manifest.getMainAttributes().getValue("Implementation-Version"));
            assertTrue(jar.stream().noneMatch(entry -> entry.getName().endsWith("module-info.class")));
        }
    }

    @Test
    void worksOnItsOwnInASeparateClassLoader() throws Exception {
        String prefix = "jdbc_jar_" + UUID.randomUUID().toString().substring(0, 8) + "_";

        try (QdrantGateway gateway = TestQdrant.gateway();
             URLClassLoader loader = new URLClassLoader(new URL[] {jar().toUri().toURL()},
                 ClassLoader.getPlatformClassLoader())) {
            SampleCollections.create(gateway, prefix);

            try {
                Driver driver = ServiceLoader.load(Driver.class, loader).stream()
                    .map(ServiceLoader.Provider::get)
                    .filter(candidate -> candidate.getClass().getName().equals(QdrantDriver.class.getName()))
                    .findFirst()
                    .orElseThrow();
                assertNotEquals(QdrantDriver.class, driver.getClass(), "loaded from the jar");

                try (Connection connection = driver.connect(TestQdrant.jdbcUrl(),
                    TestQdrant.properties())) {
                    DatabaseMetaData metaData = connection.getMetaData();
                    List<String> columns = new ArrayList<>();

                    try (ResultSet rows = metaData.getColumns(null, null,
                        prefix.replace("_", "\\_") + "articles", "%")) {
                        while (rows.next()) {
                            columns.add(rows.getString("COLUMN_NAME") + " " + rows.getString("TYPE_NAME"));
                        }
                    }

                    assertEquals(List.of("_id VARCHAR", "author JSON", "author.name VARCHAR",
                        "lang VARCHAR", "title VARCHAR", "year BIGINT", "_payload JSON",
                        "colbert MULTIVECTOR", "dense VECTOR", "keywords SPARSE_VECTOR"), columns);
                    assertTrue(connection.isValid(5));
                }
            } finally {
                SampleCollections.delete(gateway, prefix);
            }
        }
    }

    private static Path jar() {
        String path = System.getProperty("qdrant.jdbc.shadedJar");
        assumeTrue(path != null, "Run through ./gradlew integrationTest, which builds the jar");
        return Path.of(path);
    }

    private static String entry(String name) throws IOException {
        try (JarFile jar = new JarFile(jar().toFile())) {
            ZipEntry entry = jar.getEntry(name);
            assertNotNull(entry, name);

            try (InputStream in = jar.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
