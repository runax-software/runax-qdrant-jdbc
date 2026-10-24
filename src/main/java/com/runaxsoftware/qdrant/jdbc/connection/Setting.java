package com.runaxsoftware.qdrant.jdbc.connection;

import java.util.List;

/**
 * The settings a connection takes, from the URL or the connection properties. Each knows its
 * property name, its default, and the description DataGrip shows in the Advanced tab.
 */
public enum Setting {
    PASSWORD("password", null, "Qdrant API key. Leave empty if the server doesn't need one."),
    TLS("tls", "false", "Connect over TLS: true or false.", "false", "true"),
    TIMEOUT("timeout", "30", "Seconds to wait for Qdrant to connect and to answer each request."),
    SAMPLE_SIZE("sampleSize", "100", "How many points to read when working out a collection's columns."),
    LOG_FILE("logFile", null, "File to log every JDBC call to, for troubleshooting. The API key is never logged.");

    private final String key;
    private final String defaultValue;
    private final String description;
    private final List<String> choices;

    Setting(String key, String defaultValue, String description, String... choices) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.description = description;
        this.choices = List.of(choices);
    }

    public String key() {
        return this.key;
    }

    public String defaultValue() {
        return this.defaultValue;
    }

    public String description() {
        return this.description;
    }

    public List<String> choices() {
        return this.choices;
    }
}
