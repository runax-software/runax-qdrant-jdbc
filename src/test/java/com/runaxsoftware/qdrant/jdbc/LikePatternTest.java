package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LikePatternTest {

    @Test
    void matchesAnythingWithoutAPattern() {
        assertTrue(LikePattern.matches(null, "products"));
    }

    @Test
    void treatsPercentAsAnyCharacters() {
        assertTrue(LikePattern.matches("prod%", "products"));
        assertTrue(LikePattern.matches("%", ""));
        assertFalse(LikePattern.matches("prod%", "articles"));
    }

    @Test
    void treatsUnderscoreAsOneCharacter() {
        assertTrue(LikePattern.matches("sample_events", "sampleXevents"));
        assertFalse(LikePattern.matches("a_c", "abbc"));
    }

    @Test
    void escapesWildcardsWithABackslash() {
        assertTrue(LikePattern.matches("sample\\_events", "sample_events"));
        assertFalse(LikePattern.matches("sample\\_events", "sampleXevents"));
        assertTrue(LikePattern.matches("100\\%", "100%"));
    }

    @Test
    void matchesRegexCharactersLiterally() {
        assertTrue(LikePattern.matches("a.b(c)", "a.b(c)"));
        assertFalse(LikePattern.matches("a.b", "axb"));
    }

    @Test
    void isCaseSensitive() {
        assertFalse(LikePattern.matches("Products", "products"));
    }

    @Test
    void matchesTheEmptyPatternOnlyToAnEmptyName() {
        assertTrue(LikePattern.matches("", ""));
        assertFalse(LikePattern.matches("", "products"));
    }

    @Test
    void treatsATrailingBackslashLiterally() {
        assertTrue(LikePattern.matches("a\\", "a\\"));
    }

    @Test
    void escapesTheEscapeCharacter() {
        assertTrue(LikePattern.matches("a\\\\b", "a\\b"));
        assertFalse(LikePattern.matches("a\\\\b", "axb"));
    }

    @Test
    void matchesNamesAcrossLines() {
        assertTrue(LikePattern.matches("a%b", "a\nb"));
    }
}
