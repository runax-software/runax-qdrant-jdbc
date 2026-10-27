package com.runaxsoftware.qdrant.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The driver's name and version. The version comes from the build.
 */
public final class DriverInfo {
    /**
     * The driver's name.
     */
    public static final String NAME = "Runax JDBC Driver for Qdrant";

    /**
     * The driver's version, such as {@code 0.1.0}.
     */
    public static final String VERSION = loadVersion();

    /**
     * The major part of {@link #VERSION}.
     */
    public static final int MAJOR_VERSION = versionPart(VERSION, 0);

    /**
     * The minor part of {@link #VERSION}.
     */
    public static final int MINOR_VERSION = versionPart(VERSION, 1);

    /**
     * The {@code User-Agent} the driver sends to Qdrant.
     */
    public static final String USER_AGENT = "runax-qdrant-jdbc/" + VERSION;

    private DriverInfo() {
    }

    private static String loadVersion() {
        try (InputStream in = DriverInfo.class.getResourceAsStream("driver.properties")) {
            if (in == null) {
                return "0.0.0";
            }

            Properties properties = new Properties();
            properties.load(in);

            return properties.getProperty("version", "0.0.0");
        } catch (IOException e) {
            return "0.0.0";
        }
    }

    static int versionPart(String version, int index) {
        String[] parts = version.split("[.-]");

        try {
            return index < parts.length ? Integer.parseInt(parts[index]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
