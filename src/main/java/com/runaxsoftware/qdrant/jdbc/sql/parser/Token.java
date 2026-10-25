package com.runaxsoftware.qdrant.jdbc.sql.parser;

public record Token(TokenType type, String text, int position) {}
