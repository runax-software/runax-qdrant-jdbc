package com.runaxsoftware.qdrant.jdbc;

import java.util.regex.Pattern;

/**
 * Matches names against the {@code LIKE} patterns of {@link java.sql.DatabaseMetaData}:
 * {@code %} for any characters, {@code _} for one, and {@code \} to escape either.
 */
final class LikePattern {

    private LikePattern() {
    }

    /**
     * Whether {@code value} matches {@code pattern}. A {@code null} pattern matches anything.
     */
    static boolean matches(String pattern, String value) {
        if (pattern == null) {
            return true;
        }

        StringBuilder regex = new StringBuilder();

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);

            if (c == '\\' && i + 1 < pattern.length()) {
                regex.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
            } else if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }

        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(value).matches();
    }
}
