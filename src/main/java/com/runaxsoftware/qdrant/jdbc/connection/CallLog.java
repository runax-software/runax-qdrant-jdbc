package com.runaxsoftware.qdrant.jdbc.connection;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static com.runaxsoftware.qdrant.jdbc.connection.ConnectionErrors.invalid;

public class CallLog {
    private static final Set<Class<?>> LOGGED_TYPES = Set.of(Connection.class, Statement.class,
        PreparedStatement.class, CallableStatement.class, DatabaseMetaData.class,
        ResultSet.class, ResultSetMetaData.class);

    private static final int MAX_VALUE_LENGTH = 200;

    private final Path file;
    private final BufferedWriter writer;
    private boolean closed;

    private CallLog(Path file, BufferedWriter writer) {
        this.file = file;
        this.writer = writer;
    }

    /**
     * Opens the log file for appending, creating it if needed.
     */
    static CallLog open(Path file) throws SQLException {
        try {
            return new CallLog(file, Files.newBufferedWriter(
                file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND
            ));
        } catch (NoSuchFileException e) {
            throw invalid("Can't write the JDBC call log to " + file
                + ": its folder doesn't exist", e);
        } catch (AccessDeniedException e) {
            throw invalid("Can't write the JDBC call log to " + file
                + ": no permission to write there", e);
        } catch (IOException e) {
            throw invalid("Can't write the JDBC call log to " + file
                + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns a connection that logs every call, and closes the log when it closes.
     */
    Connection wrap(Connection connection) {
        return (Connection) proxy(Connection.class, connection);
    }

    synchronized void write(String message) {
        if (closed) return;

        try {
            writer.write(Instant.now() + " [" + Thread.currentThread().getName() + "] " + message);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            closed = true;
        }
    }

    synchronized void close() {
        if (closed) return;
        closed = true;

        try {
            writer.close();
        } catch (IOException e) {
            // Nothing more to log to.
        }
    }

    @Override
    public String toString() {
        return "CallLog[" + file + "]";
    }

    private Object proxy(Class<?> type, Object target) {
        return Proxy.newProxyInstance(CallLog.class.getClassLoader(), new Class<?>[]{type}, new Handler(type, target));
    }

    private final class Handler implements InvocationHandler {
        private final Class<?> type;
        private final Object target;

        Handler(Class<?> type, Object target) {
            this.type = type;
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> target.toString();
                };
            }

            String call = type.getSimpleName() + "." + method.getName() + "(" + render(args) + ")";
            Object result;

            try {
                result = method.invoke(target, args);
            } catch (InvocationTargetException e) {
                write(call + " threw " + e.getCause());
                throw e.getCause();
            }

            write(call + (method.getReturnType() == void.class ? "" : " = " + render(result)));

            if (type == Connection.class && method.getName().equals("close")) {
                close();
            }

            Class<?> returnType = method.getReturnType();

            return result != null && LOGGED_TYPES.contains(returnType)
                ? proxy(returnType, result)
                : result;
        }
    }

    private static String render(Object[] args) {
        if (args == null) return "";

        return Arrays.stream(args).map(CallLog::render).collect(Collectors.joining(", "));
    }

    private static String render(Object value) {
        if (value == null) return "null";
        if (value instanceof Properties) return "Properties[" + ((Properties) value).size() + "]";
        if (value instanceof String) return '"' + shorten((String) value) + '"';
        if (value instanceof int[]) return Arrays.toString((int[]) value);

        if (value instanceof Object[]) {
            return Arrays.toString(Arrays.stream((Object[]) value).map(CallLog::render).toArray());
        }

        for (Class<?> type : LOGGED_TYPES) if (type.isInstance(value)) return type.getSimpleName();

        return shorten(value.toString());
    }

    private static String shorten(String value) {
        return value.length() > MAX_VALUE_LENGTH
            ? value.substring(0, MAX_VALUE_LENGTH) + "..."
            : value;
    }
}
