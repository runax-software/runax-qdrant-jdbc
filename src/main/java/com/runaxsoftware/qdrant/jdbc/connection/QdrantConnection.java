package com.runaxsoftware.qdrant.jdbc.connection;

import com.runaxsoftware.qdrant.jdbc.DriverInfo;
import com.runaxsoftware.qdrant.jdbc.metadata.QdrantDatabaseMetaData;
import com.runaxsoftware.qdrant.jdbc.statement.QdrantStatement;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ServerVersion;
import com.runaxsoftware.qdrant.jdbc.schema.SchemaReader;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

public class QdrantConnection implements Connection {
    private final String url;
    private final QdrantGateway gateway;
    private final ServerVersion serverVersion;
    private final SchemaReader schemaReader;
    private final Properties clientInfo = new Properties();
    private volatile boolean closed;
    private SQLWarning warnings;
    private int holdability = ResultSet.HOLD_CURSORS_OVER_COMMIT;
    private int networkTimeout;

    private QdrantConnection(String url, ConnectionSettings settings, QdrantGateway gateway,
                             ServerVersion serverVersion) {
        this.url = url;
        this.gateway = gateway;
        this.serverVersion = serverVersion;
        this.schemaReader = new SchemaReader(gateway, settings.sampleSize(), settings.schemaCache());
    }

    public static QdrantConnection open(String url, ConnectionSettings settings) throws SQLException {
        QdrantGateway gateway = QdrantGateway.open(settings.host(), settings.port(), settings.tls(), settings.apiKey(),
            settings.timeout(), DriverInfo.USER_AGENT);

        try {
            ServerVersion serverVersion = checkServer(gateway);
            gateway.listCollections();

            return new QdrantConnection(url, settings, gateway, serverVersion);
        } catch (SQLException | RuntimeException e) {
            gateway.close();
            throw e;
        }
    }

    /**
     * Asks for Qdrant's version, which needs no API key. Apart from an unreachable server, a
     * timeout, or a proxy asking for credentials, any failure means something other than Qdrant's
     * REST API answered on that port: most often the gRPC port, 6334, which doesn't speak HTTP/1.1.
     */
    private static ServerVersion checkServer(QdrantGateway gateway) throws SQLException {
        try {
            return gateway.serverVersion();
        } catch (SQLTimeoutException | SQLInvalidAuthorizationSpecException e) {
            throw e;
        } catch (SQLException e) {
            if ("08001".equals(e.getSQLState())) throw e;

            throw ConnectionErrors.invalid("Qdrant's REST API didn't answer at " + gateway.baseUrl()
                + ". The driver uses Qdrant's REST port, " + QdrantGateway.DEFAULT_PORT
                + " by default, not the gRPC port, 6334", e);
        }
    }

    public String url() {
        return url;
    }

    public QdrantGateway gateway() {
        return gateway;
    }

    public ServerVersion serverVersion() {
        return serverVersion;
    }

    public SchemaReader schemaReader() {
        return schemaReader;
    }

    public void checkOpen() throws SQLException {
        if (closed) throw new SQLNonTransientConnectionException("The connection is closed", "08003");
    }


    @Override
    public Statement createStatement() throws SQLException {
        checkOpen();
        return new QdrantStatement(this);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        throw preparedStatementsNotSupported();
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw noStoredProcedures();
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        checkOpen();
        return sql;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkOpen();

        if (!autoCommit) addWarning("Qdrant has no transactions; every change applies immediately");
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        checkOpen();
        return true;
    }

    @Override
    public void commit() throws SQLException {
        checkOpen();
    }

    @Override
    public void rollback() throws SQLException {
        checkOpen();
    }

    @Override
    public void close() throws SQLException {
        if (closed) return;

        closed = true;
        gateway.close();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkOpen();
        return new QdrantDatabaseMetaData(this);
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        checkOpen();
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        checkOpen();
        return true;
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        checkOpen();
    }

    @Override
    public String getCatalog() throws SQLException {
        checkOpen();
        return QdrantDatabaseMetaData.CATALOG;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        checkOpen();
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        checkOpen();
        return TRANSACTION_NONE;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return warnings;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
        warnings = null;
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        checkOpen();

        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY || resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
            addWarning("Result sets are forward-only and read-only; using those instead");
        }

        return new QdrantStatement(this);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        throw preparedStatementsNotSupported();
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        throw noStoredProcedures();
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        checkOpen();
        return new HashMap<>();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        checkOpen();
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        checkOpen();
        this.holdability = holdability;
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return holdability;
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        throw noTransactions();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        throw noTransactions();
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        throw noTransactions();
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw noTransactions();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
        throws SQLException {
        return createStatement(resultSetType, resultSetConcurrency);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        throw preparedStatementsNotSupported();
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        throw noStoredProcedures();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        throw preparedStatementsNotSupported();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        throw preparedStatementsNotSupported();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        throw preparedStatementsNotSupported();
    }

    @Override
    public Clob createClob() throws SQLException {
        throw new SQLFeatureNotSupportedException("CLOB values aren't supported");
    }

    @Override
    public Blob createBlob() throws SQLException {
        throw new SQLFeatureNotSupportedException("BLOB values aren't supported");
    }

    @Override
    public NClob createNClob() throws SQLException {
        throw new SQLFeatureNotSupportedException("NCLOB values aren't supported");
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw new SQLFeatureNotSupportedException("SQLXML values aren't supported");
    }


    /**
     * Checks the connection by listing the collections, which also confirms the API key still
     * works. The request uses the connection's {@code timeout} setting.
     */
    @Override
    public boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) throw new SQLException("The timeout can't be negative", "HY000");
        if (closed) return false;

        try {
            gateway.listCollections();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        if (closed) throw new SQLClientInfoException("The connection is closed", "08003", 0, Map.of());

        if (value == null) clientInfo.remove(name);
        else clientInfo.setProperty(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        if (closed) throw new SQLClientInfoException("The connection is closed", "08003", 0, Map.of());

        clientInfo.clear();
        clientInfo.putAll(properties);
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        checkOpen();
        return clientInfo.getProperty(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        checkOpen();
        Properties copy = new Properties();
        copy.putAll(clientInfo);

        return copy;
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw new SQLFeatureNotSupportedException("ARRAY values aren't supported");
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw new SQLFeatureNotSupportedException("STRUCT values aren't supported");
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        checkOpen();
    }

    @Override
    public String getSchema() throws SQLException {
        checkOpen();
        return QdrantDatabaseMetaData.SCHEMA;
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        if (executor == null) throw new SQLException("The executor is null", "HY000");

        if (!closed) {
            closed = true;
            executor.execute(gateway::close);
        }
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        checkOpen();
        if (milliseconds < 0) throw new SQLException("The timeout can't be negative", "HY000");

        networkTimeout = milliseconds;
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        checkOpen();
        return networkTimeout;
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) return type.cast(this);

        throw new SQLException("Not a wrapper for " + type.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this);
    }

    private void addWarning(String message) {
        SQLWarning warning = new SQLWarning(message);

        if (warnings == null) {
            warnings = warning;
        } else {
            warnings.setNextWarning(warning);
        }
    }

    @Override
    public String toString() {
        return "QdrantConnection[" + gateway.baseUrl() + ", Qdrant " + serverVersion.version() + "]";
    }

    private static SQLFeatureNotSupportedException preparedStatementsNotSupported() {
        return new SQLFeatureNotSupportedException("Prepared statements aren't supported yet");
    }

    private static SQLFeatureNotSupportedException noStoredProcedures() {
        return new SQLFeatureNotSupportedException("Qdrant has no stored procedures");
    }

    private static SQLFeatureNotSupportedException noTransactions() {
        return new SQLFeatureNotSupportedException("Qdrant has no transactions");
    }
}
