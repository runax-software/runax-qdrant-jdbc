package com.runaxsoftware.qdrant.jdbc.sql.parser;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.*;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.statement.SelectParser;

import java.sql.SQLSyntaxErrorException;

public final class Parser {

    public static Statement parse(String sql) throws SQLSyntaxErrorException {
        var tokenStream = new TokenStream(new Lexer(sql).tokenize());

        Statement statement = parseStatement(tokenStream);
        if (tokenStream.peek().type() != TokenType.EOF) {
            throw tokenStream.error("Expected the end of the statement");
        }
        return statement;
    }

    private static Statement parseStatement(TokenStream tokenStream) throws SQLSyntaxErrorException {
        if (tokenStream.peekKeyword("SELECT")) {
            return new SelectParser(tokenStream).parse();
        }

        throw tokenStream.error("Expected SELECT");
    }
}
