package com.runaxsoftware.qdrant.jdbc.sql.parser;

/**
 * Turns a character offset in the SQL text into the line and column an editor shows.
 */
final class SourceLocation {

    private SourceLocation() {
    }

    /**
     * Describes an offset as {@code "line 2, column 5"}, both counted from 1. A line ends at
     * {@code \n}, {@code \r\n}, or {@code \r}.
     */
    static String describe(String sql, int position) {
        int line = 1;
        int lineStart = 0;

        for (int i = 0; i < position && i < sql.length(); i++) {
            char c = sql.charAt(i);
            boolean crBeforeLf = c == '\r' && i + 1 < sql.length() && sql.charAt(i + 1) == '\n';

            if ((c == '\n' || c == '\r') && !crBeforeLf) {
                line++;
                lineStart = i + 1;
            }
        }

        return "line " + line + ", column " + (position - lineStart + 1);
    }
}
