package com.runaxsoftware.qdrant.jdbc.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.HashMap;
import java.util.Map;

class QdrantUrlTest {

    // Recognizing Qdrant URLs

    @Test
    void acceptsThePrefixInAnyCase() throws SQLException {
        assertTrue(QdrantUrl.accepts("jdbc:qdrant://localhost"));
        assertTrue(QdrantUrl.accepts("JDBC:Qdrant://localhost"));
        assertTrue(QdrantUrl.accepts("jdbc:qdrant:nohost"));
    }

    @Test
    void doesNotAcceptOtherUrls() throws SQLException {
        assertFalse(QdrantUrl.accepts("jdbc:postgresql://localhost/db"));
        assertFalse(QdrantUrl.accepts("jdbc:qdrantx://localhost"));
        assertFalse(QdrantUrl.accepts("jdbc:qdrant"));
        assertFalse(QdrantUrl.accepts(""));
    }

    @Test
    void rejectsANullUrlWhenAskedWhetherItAcceptsIt() {
        SQLException error = assertThrows(SQLException.class, () -> QdrantUrl.accepts(null));

        assertEquals("08001", error.getSQLState());
    }

    // Host and port

    @Test
    void usesTheRestPortByDefault() throws SQLException {
        QdrantUrl url = QdrantUrl.parse("jdbc:qdrant://localhost");

        assertEquals("localhost", url.host());
        assertEquals(6333, url.port());
        assertEquals(QdrantUrl.DEFAULT_PORT, url.port());
        assertEquals(Map.of(), url.parameters());
    }

    @Test
    void readsTheHostAndPort() throws SQLException {
        QdrantUrl url = QdrantUrl.parse("jdbc:qdrant://db.example.com:7000");

        assertEquals("db.example.com", url.host());
        assertEquals(7000, url.port());
    }

    @Test
    void keepsTheHostAsWrittenAndTheSchemeInAnyCase() throws SQLException {
        assertEquals("LocalHost", QdrantUrl.parse("JDBC:QDRANT://LocalHost:6333").host());
    }

    @Test
    void stripsBracketsFromIpv6Hosts() throws SQLException {
        QdrantUrl url = QdrantUrl.parse("jdbc:qdrant://[::1]:6334");

        assertEquals("::1", url.host());
        assertEquals(6334, url.port());
    }

    @Test
    void acceptsATrailingSlash() throws SQLException {
        assertEquals("localhost", QdrantUrl.parse("jdbc:qdrant://localhost:6333/").host());
    }

    @Test
    void acceptsTheLowestAndHighestPorts() throws SQLException {
        assertEquals(1, QdrantUrl.parse("jdbc:qdrant://localhost:1").port());
        assertEquals(65535, QdrantUrl.parse("jdbc:qdrant://localhost:65535").port());
    }

    @Test
    void namesAWorkingUrlAsTheExample() {
        assertEquals("jdbc:qdrant://localhost:6333", QdrantUrl.EXAMPLE);
        assertEquals("jdbc:qdrant:", QdrantUrl.PREFIX);
    }

    // Parameters

    @Test
    void decodesParameterNamesAndValues() throws SQLException {
        QdrantUrl url = QdrantUrl.parse(
            "jdbc:qdrant://localhost?logFile=%2Ftmp%2Fqdrant%20calls.log&sample%20Size=1+2");

        assertEquals("/tmp/qdrant calls.log", url.parameters().get("logFile"));
        assertEquals("1 2", url.parameters().get("sample Size"));
    }

    @Test
    void keepsParametersExactlyAsWritten() throws SQLException {
        QdrantUrl url = QdrantUrl.parse("jdbc:qdrant://localhost?tls=TRUE&timeout=%207%20");

        assertEquals("TRUE", url.parameters().get("tls"));
        assertEquals(" 7 ", url.parameters().get("timeout"));
    }

    @Test
    void keepsAnEncodedAmpersandInsideAValue() throws SQLException {
        QdrantUrl url = QdrantUrl.parse("jdbc:qdrant://localhost?logFile=a%26b.log&tls=true");

        assertEquals(Map.of("logFile", "a&b.log", "tls", "true"), url.parameters());
    }

    @Test
    void readsAParameterWithoutAValueAsEmpty() throws SQLException {
        assertEquals(Map.of("tls", ""), QdrantUrl.parse("jdbc:qdrant://localhost?tls").parameters());
        assertEquals(Map.of("tls", ""), QdrantUrl.parse("jdbc:qdrant://localhost?tls=").parameters());
    }

    @Test
    void keepsTheLastValueOfARepeatedParameter() throws SQLException {
        assertEquals("2", QdrantUrl.parse("jdbc:qdrant://localhost?timeout=1&timeout=2")
            .parameters().get("timeout"));
    }

    @Test
    void skipsEmptyParameters() throws SQLException {
        assertEquals(Map.of("tls", "true", "unknown", "1"),
            QdrantUrl.parse("jdbc:qdrant://localhost?&tls=true&&unknown=1&").parameters());
    }

    @Test
    void readsParametersAfterATrailingSlash() throws SQLException {
        assertEquals(Map.of("tls", "true"),
            QdrantUrl.parse("jdbc:qdrant://localhost:6333/?tls=true").parameters());
    }

    @Test
    void cantChangeItsParameters() throws SQLException {
        QdrantUrl url = QdrantUrl.parse("jdbc:qdrant://localhost?tls=true");

        assertThrows(UnsupportedOperationException.class,
            () -> url.parameters().put("tls", "false"));
    }

    @Test
    void copiesTheParametersItIsGiven() {
        Map<String, String> parameters = new HashMap<>(Map.of("tls", "true"));
        QdrantUrl url = new QdrantUrl("localhost", 6333, parameters);

        parameters.put("tls", "false");

        assertEquals(Map.of("tls", "true"), url.parameters());
    }

    // Rejected URLs

    @Test
    void rejectsANullUrl() {
        SQLException error = invalid(null);

        assertTrue(error.getMessage().contains("No URL given"), error.getMessage());
        assertTrue(error.getMessage().contains(QdrantUrl.EXAMPLE), error.getMessage());
    }

    @Test
    void rejectsOtherUrls() {
        SQLException error = invalid("jdbc:postgresql://localhost/db");

        assertTrue(error.getMessage().contains("Not a Qdrant URL"), error.getMessage());
        assertTrue(error.getMessage().contains(QdrantUrl.EXAMPLE), error.getMessage());
    }

    @Test
    void pointsToWhereTheUrlCantBeRead() {
        SQLException error = invalid("jdbc:qdrant://local host:6333");
        java.net.URISyntaxException cause =
            assertInstanceOf(java.net.URISyntaxException.class, error.getCause());

        assertTrue(error.getMessage().startsWith("Can't read the URL jdbc:qdrant://local host:6333"),
            error.getMessage());
        // The position counts from the start of the whole URL, 5 characters ("jdbc:") past the
        // index java.net.URI reports; which character URI blames differs between JDKs
        assertTrue(error.getMessage().contains("at position " + (cause.getIndex() + 5)),
            error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:qdrant:nohost", "jdbc:qdrant:///", "jdbc:qdrant://:6333"})
    void rejectsUrlsWithoutAHost(String url) {
        SQLException error = invalid(url);

        assertTrue(error.getMessage().contains("Can't find a host"), error.getMessage());
        assertTrue(error.getMessage().contains(QdrantUrl.EXAMPLE), error.getMessage());
    }

    @Test
    void rejectsAPath() {
        SQLException error = invalid("jdbc:qdrant://localhost:6333/qdrant");

        assertTrue(error.getMessage().contains("remove /qdrant"), error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:qdrant://localhost:0", "jdbc:qdrant://localhost:65536"})
    void rejectsPortsOutOfRange(String url) {
        SQLException error = invalid(url);

        assertTrue(error.getMessage().contains("out of range"), error.getMessage());
        assertTrue(error.getMessage().contains("1 to 65535"), error.getMessage());
    }

    @Test
    void rejectsAnApiKeyInTheUrl() {
        SQLException error = invalid("jdbc:qdrant://localhost?password=secret");

        assertTrue(error.getMessage().contains("password field"), error.getMessage());
        assertFalse(error.getMessage().contains("secret"), error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:postgresql://localhost/db?token=hidden",
        "jdbc:qdrant://local host?token=hidden",
        "jdbc:qdrant:///?token=hidden",
        "jdbc:qdrant://localhost/path?token=hidden",
        "jdbc:qdrant://localhost:0?token=hidden",
        "jdbc:qdrant://localhost?password=hidden&token=hidden"})
    void leavesParametersOutOfErrorMessages(String url) {
        SQLException error = invalid(url);

        assertFalse(error.getMessage().contains("hidden"), error.getMessage());
        assertFalse(error.getMessage().contains("token"), error.getMessage());
    }

    private static SQLException invalid(String url) {
        SQLNonTransientConnectionException error =
            assertThrows(SQLNonTransientConnectionException.class, () -> QdrantUrl.parse(url));

        assertEquals("08001", error.getSQLState());
        return error;
    }
}
