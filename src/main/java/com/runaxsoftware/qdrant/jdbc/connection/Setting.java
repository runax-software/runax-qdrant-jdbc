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
    SCHEMA_CACHE("schemaCache", "3600", "Seconds to keep a collection's columns before reading them again. "
        + "Listing tables, as a Refresh does, reads them again sooner; 0 reads them every time."),
    DEFAULT_LIMIT("defaultLimit", "1000", "Rows to read when a statement gives no LIMIT and no "
        + "maximum rows, for the reads Qdrant answers in one call and pages no further: an "
        + "ordered read, a similarity search, and a GROUP BY. The statement warns when this "
        + "bounds it; 0 makes such a statement an error instead of bounding it."),
    LOG_FILE("logFile", null, "File to log every JDBC call to, for troubleshooting. The API key is never logged."),
    OPENAI_API_KEY("openaiApiKey", null, "OpenAI API key, for embed('text', 'openai:...'). Never logged."),
    OPENAI_URL("openaiUrl", "https://api.openai.com/v1", "OpenAI's API URL, or one that answers like it, "
        + "such as an Azure OpenAI deployment."),
    VOYAGE_API_KEY("voyageApiKey", null, "Voyage AI API key, for embed('text', 'voyage:...'). Never logged."),
    VOYAGE_URL("voyageUrl", "https://api.voyageai.com/v1", "Voyage AI's API URL.");

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

    /**
     * Whether the value is a key, which is never shown, logged, or put in a message.
     */
    public boolean secret() {
        return this == PASSWORD || this == OPENAI_API_KEY || this == VOYAGE_API_KEY;
    }
}
