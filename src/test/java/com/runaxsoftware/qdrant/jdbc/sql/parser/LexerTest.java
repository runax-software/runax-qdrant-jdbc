package com.runaxsoftware.qdrant.jdbc.sql.parser;

import static com.runaxsoftware.qdrant.jdbc.sql.parser.TokenType.EOF;
import static com.runaxsoftware.qdrant.jdbc.sql.parser.TokenType.IDENTIFIER;
import static com.runaxsoftware.qdrant.jdbc.sql.parser.TokenType.KEYWORD;
import static com.runaxsoftware.qdrant.jdbc.sql.parser.TokenType.NUMBER;
import static com.runaxsoftware.qdrant.jdbc.sql.parser.TokenType.PARAMETER;
import static com.runaxsoftware.qdrant.jdbc.sql.parser.TokenType.STRING;
import static com.runaxsoftware.qdrant.jdbc.sql.parser.TokenType.SYMBOL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.SQLSyntaxErrorException;
import java.util.List;

// A lexer bug usually shows up as a loop that never ends. A separate thread lets the timeout
// stop such a loop: on the test's own thread it could only ask, and the loop would ignore it
@Timeout(value = 2, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LexerTest {

    @Test
    void upperCasesKeywordsAndKeepsTheCaseOfNames() throws SQLSyntaxErrorException {
        assertEquals(
                List.of(
                        new Token(KEYWORD, "SELECT", 0),
                        new Token(IDENTIFIER, "Name", 7),
                        new Token(KEYWORD, "FROM", 12),
                        new Token(IDENTIFIER, "docs", 17),
                        new Token(EOF, "", 21)),
                new Lexer("select Name from docs").tokenize());
    }

    @Test
    void readsNamesWithUnderscoresAndDigits() throws SQLSyntaxErrorException {
        assertEquals(List.of(new Token(IDENTIFIER, "_id", 0), new Token(IDENTIFIER, "field_2", 4),
                        new Token(EOF, "", 11)),
                new Lexer("_id field_2").tokenize());
    }

    @Test
    void readsQuotedNamesAsNamesEvenWhenTheyAreKeywords() throws SQLSyntaxErrorException {
        assertEquals(List.of(new Token(IDENTIFIER, "specs.color", 0), new Token(IDENTIFIER, "order", 14),
                        new Token(EOF, "", 21)),
                new Lexer("\"specs.color\" \"order\"").tokenize());
    }

    @Test
    void readsStrings() throws SQLSyntaxErrorException {
        assertEquals(List.of(new Token(STRING, "en", 0), new Token(EOF, "", 4)),
                new Lexer("'en'").tokenize());
    }

    @Test
    void readsADoubledQuoteInAStringAsOneQuote() throws SQLSyntaxErrorException {
        assertEquals(new Token(STRING, "it's", 0), new Lexer("'it''s'").tokenize().get(0));
    }

    @Test
    void readsAnEmptyString() throws SQLSyntaxErrorException {
        assertEquals(new Token(STRING, "", 0), new Lexer("''").tokenize().get(0));
    }

    @Test
    void readsWholeDecimalAndNegativeNumbers() throws SQLSyntaxErrorException {
        assertEquals(
                List.of(
                        new Token(NUMBER, "42", 0),
                        new Token(NUMBER, "3.14", 3),
                        new Token(NUMBER, "-7", 8),
                        new Token(NUMBER, "-0.5", 11),
                        new Token(EOF, "", 15)),
                new Lexer("42 3.14 -7 -0.5").tokenize());
    }

    @Test
    void readsSymbolsOfOneAndTwoCharacters() throws SQLSyntaxErrorException {
        List<String> texts = new Lexer("<= >= <> != = < > ( ) , *").tokenize().stream()
                .filter(token -> token.type() == SYMBOL)
                .map(Token::text)
                .toList();

        assertEquals(List.of("<=", ">=", "<>", "!=", "=", "<", ">", "(", ")", ",", "*"), texts);
    }

    @Test
    void readsSymbolsWithoutSpacesAroundThem() throws SQLSyntaxErrorException {
        assertEquals(
                List.of(
                        new Token(IDENTIFIER, "price", 0),
                        new Token(SYMBOL, "<=", 5),
                        new Token(NUMBER, "10", 7),
                        new Token(EOF, "", 9)),
                new Lexer("price<=10").tokenize());
    }

    @Test
    void readsParameters() throws SQLSyntaxErrorException {
        assertEquals(List.of(new Token(PARAMETER, "?", 0), new Token(EOF, "", 1)),
                new Lexer("?").tokenize());
    }

    @Test
    void endsEveryStatementWithEof() throws SQLSyntaxErrorException {
        assertEquals(List.of(new Token(EOF, "", 0)), new Lexer("").tokenize());
        assertEquals(List.of(new Token(EOF, "", 3)), new Lexer(" \n\t").tokenize());
    }

    @Test
    void rejectsAnUnknownCharacterWithItsPosition() {
        SQLSyntaxErrorException error =
                assertThrows(SQLSyntaxErrorException.class, () -> new Lexer("SELECT #").tokenize());

        assertTrue(error.getMessage().contains("'#'"), error.getMessage());
        assertTrue(error.getMessage().contains("line 1, column 8"), error.getMessage());
    }

    @Test
    void rejectsAnUnclosedString() {
        SQLSyntaxErrorException error =
                assertThrows(SQLSyntaxErrorException.class, () -> new Lexer("x = 'abc").tokenize());

        assertTrue(error.getMessage().contains("line 1, column 5"), error.getMessage());
    }

    @Test
    void givesTheLineAndColumnOfAnErrorOnALaterLine() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
                () -> new Lexer("SELECT *\nFROM docs\r\nWHERE a = #").tokenize());

        assertEquals("Unexpected character '#' at line 3, column 11", error.getMessage());
    }

    @Test
    void rejectsAnUnclosedQuotedName() {
        assertThrows(SQLSyntaxErrorException.class, () -> new Lexer("\"abc").tokenize());
    }
}
