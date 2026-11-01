package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SelectStatement;

/** A parsed SQL statement. */
public sealed interface Statement permits SelectStatement, DeleteStatement, TruncateStatement,
    DropTableStatement, CreateTableStatement, SetStatement, ResetStatement, ShowStatement,
    UpdateStatement, InsertStatement {}
