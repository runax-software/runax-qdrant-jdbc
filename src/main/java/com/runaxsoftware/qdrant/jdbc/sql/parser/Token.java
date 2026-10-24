package com.runaxsoftware.qdrant.jdbc.sql.parser;

record Token(TokenType type, String text, int position) {}
