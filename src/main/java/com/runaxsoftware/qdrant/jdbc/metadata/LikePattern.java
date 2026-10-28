package com.runaxsoftware.qdrant.jdbc.metadata;

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
        if (pattern == null) return true;
        StringBuilder regex = new StringBuilder();

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);

            if (c == '\\' && i + 1 < pattern.length()) regex.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
            else if (c == '%') regex.append(".*");
            else if (c == '_') regex.append('.');
            else regex.append(Pattern.quote(String.valueOf(c)));
        }

        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(value).matches();
    }

    /**
     * The one name a pattern without wildcards matches, with its escapes removed, such as
     * {@code my_docs} for {@code my\_docs}; {@code null} for a pattern with an unescaped
     * {@code %} or {@code _}, which can match many names, and for a {@code null} or empty one.
     */
    static String literal(String pattern) {
        if (pattern == null || pattern.isEmpty()) return null;
        StringBuilder name = new StringBuilder();

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);

            if (c == '\\' && i + 1 < pattern.length()) name.append(pattern.charAt(++i));
            else if (c == '%' || c == '_') return null;
            else name.append(c);
        }

        return name.toString();
    }
}
