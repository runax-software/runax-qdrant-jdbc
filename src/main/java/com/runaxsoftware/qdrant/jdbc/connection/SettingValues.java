package com.runaxsoftware.qdrant.jdbc.connection;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static com.runaxsoftware.qdrant.jdbc.connection.ConnectionErrors.invalid;

/**
 * One connection's raw setting values (URL parameters, overridden by connection properties)
 * with typed getters that apply each setting's default and report invalid values.
 */

final class SettingValues {
    private final Map<String, String> values;

    private SettingValues(Map<String, String> values) {
        this.values = values;
    }

    static SettingValues merge(Map<String, String> urlParameters, Properties properties) {
        Map<String, String> merged = new HashMap<>(urlParameters);

        if (properties != null) {
            for (String name : properties.stringPropertyNames()) merged.put(name, properties.getProperty(name));
        }

        return new SettingValues(merged);
    }

    boolean bool(Setting setting) throws SQLException {
        String value = valueOrDefault(setting);

        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid(setting.key() + " must be true or false, not " + value);
        };
    }

    int wholeNumber(Setting setting, int min, int max) throws SQLException {
        String value = valueOrDefault(setting);

        try {
            int number = Integer.parseInt(value);
            if (number >= min && number <= max) return number;
        } catch (NumberFormatException e) {
            // Reported below.
        }

        throw invalid(setting.key() + " must be a whole number from " + min + " to " + max
            + ", not " + value);
    }

    Path path(Setting setting) throws SQLException {
        String value = value(setting);
        if (value == null) return null;

        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw invalid(setting.key() + " isn't a valid path: " + value, e);
        }
    }

    URI uri(Setting setting) throws SQLException {
        String value = valueOrDefault(setting);

        try {
            URI uri = new URI(value);
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                throw invalid(setting.key() + " must be an http or https URL, not " + value);
            }

            return uri;
        } catch (URISyntaxException e) {
            throw invalid(setting.key() + " isn't a valid URL: " + value, e);
        }
    }

    /** The value exactly as given, or {@code null} when missing or empty; never put in a message. */
    String secret(Setting setting) {
        String value = values.get(setting.key());
        return value == null || value.isEmpty() ? null : value;
    }

    private String valueOrDefault(Setting setting) {
        String value = value(setting);
        return value != null ? value : setting.defaultValue();
    }

    private String value(Setting setting) {
        String value = values.get(setting.key());
        return value == null || value.isBlank() ? null : value.strip();
    }
}

