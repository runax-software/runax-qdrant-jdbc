package com.runaxsoftware.qdrant.jdbc.gateway.models;

/**
 * What one of Qdrant's health probes answered. A probe says "no" with its status, so a failing
 * one is an answer to report rather than an error.
 *
 * @param status the HTTP status, 200 when the check passed
 * @param body   the text Qdrant sent with it, such as {@code "all shards are ready"}, or
 *               {@code null} when it sent none
 */
public record ProbeAnswer(int status, String body) {

    /**
     * Whether the check passed.
     */
    public boolean passed() {
        return status >= 200 && status < 300;
    }
}
