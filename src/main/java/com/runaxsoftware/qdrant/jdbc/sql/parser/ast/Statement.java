package com.runaxsoftware.qdrant.jdbc.sql.parser.ast;

/** A parsed SQL statement. */
public sealed interface Statement permits SelectStatement {}
