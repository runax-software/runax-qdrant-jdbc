package com.runaxsoftware.qdrant.jdbc.gateway;

public enum HttpMethod {
    POST("POST"),
    GET("GET"),
    PUT("PUT"),
    PATCH("PATCH"),
    DELETE("DELETE");

    private final String method;

    HttpMethod(String method) {
        this.method = method;
    }

    public String method() {
        return this.method;
    }
}
