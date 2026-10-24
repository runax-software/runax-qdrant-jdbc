package com.runaxsoftware.qdrant.jdbc;

import com.runaxsoftware.qdrant.jdbc.client.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.client.ServerInfo;
import com.runaxsoftware.qdrant.jdbc.schema.SchemaReader;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * A connection to one Qdrant server.
 *
 * <p>Qdrant has no transactions, catalogs, or schemas, so the related settings are accepted
 * and ignored: auto-commit is always on in effect, and commit and rollback do nothing. Tools
 * such as DataGrip set these on every connection, so they don't fail.
 */
public final class QdrantConnection implements Connection {

    private final String url;
    private final QdrantGateway gateway;
    private final ServerInfo serverInfo;
    private final SchemaReader schemaReader;
    private final Properties clientInfo = new Properties();
    private volatile boolean closed;
    private SQLWarning warnings;
    private int holdability = ResultSet.HOLD_CURSORS_OVER_COMMIT;
    private int networkTimeout;

    private QdrantConnection(String url, ConnectionSettings settings, QdrantGateway gateway,
                             ServerInfo serverInfo) {
        this.url = url;
        this.gateway = gateway;
        this.serverInfo = serverInfo;
        this.schemaReader = new SchemaReader(gateway, settings.sampleSize());
    }

    /**
     * Connects and checks that Qdrant accepts the API key. Qdrant's health check answers
     * without a key, so the driver also lists the collections, which needs one when the server
     * has it.
     */
    static QdrantConnection open(String url, ConnectionSettings settings) throws SQLException {
        QdrantGateway gateway = QdrantGateway.open(settings.host(), settings.port(),
            settings.tls(), settings.apiKey(), settings.timeout(), DriverInfo.USER_AGENT);
        try {
            ServerInfo serverInfo = checkServer(gateway);
            gateway.listCollections();

            return new QdrantConnection(url, settings, gateway, serverInfo);
        } catch (SQLException | RuntimeException e) {
            gateway.close();
            throw e;
        }
    }

    /**
     * Runs Qdrant's health check. It needs no API key and can't fail on bad input, so any error
     * other than an unreachable server or a timeout means something else answered on that
     * port, most often Qdrant's REST API on 6333 instead of the gRPC API.
     */
    private static ServerInfo checkServer(QdrantGateway gateway) throws SQLException {
        try {
            return gateway.serverInfo();
        } catch (SQLNonTransientConnectionException | SQLTimeoutException e) {
            throw e;
        } catch (SQLException e) {
            throw new SQLNonTransientConnectionException("Qdrant's gRPC API didn't answer at "
                + gateway.address() + " (" + e.getMessage() + "). The driver uses Qdrant's gRPC port, "
                + QdrantGateway.DEFAULT_PORT + " by default, not the REST port, 6333", "08001", e);
        }
    }

    String url() {
        return url;
    }

    QdrantGateway gateway() {
        return gateway;
    }

    ServerInfo serverInfo() {
        return serverInfo;
    }

    SchemaReader schemaReader() {
        return schemaReader;
    }

    void checkOpen() throws SQLException {
        if (closed) {
            throw new SQLNonTransientConnectionException("The connection is closed", "08003");
        }
    }

    // Statements

    @Override
    public Statement createStatement() throws SQLException {
        checkOpen();
        return new QdrantStatement(this);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency)
        throws SQLException {
        checkOpen();

        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY
            || resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
            addWarning("Result sets are forward-only and read-only; using those instead");
        }

        return new QdrantStatement(this);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency,
                                     int resultSetHoldability) throws SQLException {
        return createStatement(resultSetType, resultSetConcurrency);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        throw preparedStatementsNotSupported();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType,
                                              int resultSetConcurrency) throws SQLException {
        throw preparedStatementsNotSupported();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType,
                                              int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        throw preparedStatementsNotSupported();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys)
        throws SQLException {
        throw preparedStatementsNotSupported();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes)
        throws SQLException {
        throw preparedStatementsNotSupported();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames)
        throws SQLException {
        throw preparedStatementsNotSupported();
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw noStoredProcedures();
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
        throws SQLException {
        throw noStoredProcedures();
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
                                         int resultSetHoldability) throws SQLException {
        throw noStoredProcedures();
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        checkOpen();
        return sql;
    }

    // Transactions: Qdrant applies every change on its own

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkOpen();

        if (!autoCommit) {
            addWarning("Qdrant has no transactions; every change applies immediately");
        }
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
    public void setTransactionIsolation(int level) throws SQLException {
        checkOpen();
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        checkOpen();
        return TRANSACTION_NONE;
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

    // Connection state

    /**
     * Closes the gRPC channel.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            gateway.close();
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /**
     * Checks the connection by listing the collections, which also confirms the API key still
     * works. The request uses the connection's {@code timeout} setting.
     */
    @Override
    public boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) {
            throw new SQLException("The timeout can't be negative", "HY000");
        }

        if (closed) {
            return false;
        }

        try {
            gateway.listCollections();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        if (executor == null) {
            throw new SQLException("The executor is null", "HY000");
        }

        if (!closed) {
            closed = true;
            executor.execute(gateway::close);
        }
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
        return null;
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        checkOpen();
    }

    @Override
    public String getSchema() throws SQLException {
        checkOpen();
        return null;
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
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        if (closed) {
            throw new SQLClientInfoException("The connection is closed", "08003", 0, Map.of());
        }

        if (value == null) {
            clientInfo.remove(name);
        } else {
            clientInfo.setProperty(name, value);
        }
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        if (closed) {
            throw new SQLClientInfoException("The connection is closed", "08003", 0, Map.of());
        }

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
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        checkOpen();

        if (milliseconds < 0) {
            throw new SQLException("The timeout can't be negative", "HY000");
        }

        networkTimeout = milliseconds;
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        checkOpen();
        return networkTimeout;
    }

    // Types the driver doesn't create

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

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw new SQLFeatureNotSupportedException("ARRAY values aren't supported");
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw new SQLFeatureNotSupportedException("STRUCT values aren't supported");
    }

    // Wrapper

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) {
            return type.cast(this);
        }

        throw new SQLException("Not a wrapper for " + type.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return type.isInstance(this);
    }

    @Override
    public String toString() {
        return "QdrantConnection[" + gateway.address() + ", Qdrant " + serverInfo.version() + "]";
    }

    private void addWarning(String message) {
        SQLWarning warning = new SQLWarning(message);

        if (warnings == null) {
            warnings = warning;
        } else {
            warnings.setNextWarning(warning);
        }
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
