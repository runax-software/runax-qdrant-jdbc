package com.runaxsoftware.qdrant.jdbc.gateway.models;

/**
 * The Qdrant version a connection talks to, as its health check reported it.
 *
 * @param version the full version, such as {@code "1.20.0"}
 * @param major   the major part, 1
 * @param minor   the minor part, 20
 * @param commit  the build's git commit, or {@code null} when the server doesn't say
 */
public record ServerVersion(String version, int major, int minor, String commit) {
    /**
     * Reads a version as Qdrant reports it. Anything after the minor part, such as a patch
     * number or a {@code -rc1} suffix, stays in {@link #version()} only.
     *
     * @param version the version, such as {@code "1.20.0"}; must not be {@code null}
     * @param commit  the git commit, or {@code null} or empty when the server doesn't report one
     * @return the version with its major and minor parts split out
     */
    public static ServerVersion parse(String version, String commit) {
        String[] parts = version.split("[.-]", 3);

        return new ServerVersion(version, number(parts, 0), number(parts, 1),
            commit == null || commit.isEmpty() ? null : commit);
    }

    /**
     * The version, with the commit when known: {@code "1.20.0 (222b2e4)"}.
     */
    @Override
    public String toString() {
        return commit == null ? version : version + " (" + commit + ")";
    }

    /**
     * The part at {@code index} as a number, or 0 when it's missing or not a number.
     */
    private static int number(String[] parts, int index) {
        if (index >= parts.length) return 0;

        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
