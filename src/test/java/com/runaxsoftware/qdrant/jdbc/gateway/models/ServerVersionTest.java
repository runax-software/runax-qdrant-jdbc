package com.runaxsoftware.qdrant.jdbc.gateway.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ServerVersionTest {

    @Test
    void splitsOutTheMajorAndMinorParts() {
        ServerVersion version = ServerVersion.parse("1.20.0", "222b2e4");

        assertEquals("1.20.0", version.version());
        assertEquals(1, version.major());
        assertEquals(20, version.minor());
        assertEquals("222b2e4", version.commit());
    }

    @ParameterizedTest
    @CsvSource({
        "1.20.0,      1, 20",
        "1.20.0-rc1,  1, 20",
        "1.9,         1,  9",
        "2,           2,  0",
        "1-rc1,       1,  0",
        "dev,         0,  0",
        "1.x.0,       1,  0",
        "v1.2.3,      0,  2",
        "'',          0,  0"})
    void readsWhatItCanAndUsesZeroForTheRest(String text, int major, int minor) {
        ServerVersion version = ServerVersion.parse(text, null);

        assertEquals(text, version.version());
        assertEquals(major, version.major());
        assertEquals(minor, version.minor());
    }

    @Test
    void keepsNoCommitWhenTheServerSendsNoneOrAnEmptyOne() {
        assertNull(ServerVersion.parse("1.20.0", null).commit());
        assertNull(ServerVersion.parse("1.20.0", "").commit());
    }

    @Test
    void showsTheCommitWhenKnown() {
        assertEquals("1.20.0 (222b2e4)", ServerVersion.parse("1.20.0", "222b2e4").toString());
        assertEquals("1.20.0", ServerVersion.parse("1.20.0", null).toString());
    }

    @Test
    void comparesByValue() {
        assertEquals(new ServerVersion("1.20.0", 1, 20, null), ServerVersion.parse("1.20.0", ""));
    }

    @Test
    void needsAVersion() {
        assertThrows(NullPointerException.class, () -> ServerVersion.parse(null, null));
    }
}
