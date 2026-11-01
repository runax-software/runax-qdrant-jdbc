package com.runaxsoftware.qdrant.jdbc.gateway.models;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The options a connection has set, which go with each request they apply to; an option that
 * isn't set leaves Qdrant's own default. Immutable, so a request reads one set of options from
 * start to end even when a statement on another thread changes them.
 */
public final class RequestOptions {

    public static final RequestOptions NONE = new RequestOptions(new EnumMap<>(RequestOption.class));

    private final Map<RequestOption, Object> values;

    private RequestOptions(Map<RequestOption, Object> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    /**
     * These options with one set, or reset to Qdrant's default when the value is {@code null}.
     *
     * @param value the value as {@link RequestOption#value} returns it, or {@code null}
     */
    public RequestOptions with(RequestOption option, Object value) {
        var changed = new EnumMap<RequestOption, Object>(RequestOption.class);
        changed.putAll(values);

        if (value == null) changed.remove(option);
        else changed.put(option, value);

        return new RequestOptions(changed);
    }

    /**
     * The option's value, or {@code null} when it isn't set.
     */
    public Object get(RequestOption option) {
        return values.get(option);
    }

    /**
     * Whether writes wait for Qdrant to apply them: unless the connection said not to.
     */
    public boolean waits() {
        return !Boolean.FALSE.equals(values.get(RequestOption.WAIT));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RequestOptions options && options.values.equals(values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "RequestOptions" + values;
    }
}
