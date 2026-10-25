//package com.runaxsoftware.qdrant.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A connection to one Qdrant server.
 *
 * <p>Qdrant has no transactions, catalogs, or schemas, so the related settings are accepted
 * and ignored: auto-commit is always on in effect, and commit and rollback do nothing. Tools
 * such as DataGrip set these on every connection, so they don't fail.
 */
public final class QdrantConnection implements Connection {
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
    public DatabaseMetaData getMetaData() throws SQLException {
        checkOpen();
        return new QdrantDatabaseMetaData(this);
    }
}
