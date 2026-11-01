package com.runaxsoftware.qdrant.jdbc.sql.parser;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.*;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.statement.DeleteParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.statement.DropParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.statement.SelectParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.statement.TruncateParser;

import java.sql.SQLSyntaxErrorException;

public final class Parser {

    public static Statement parse(String sql) throws SQLSyntaxErrorException {
        var tokenStream = new TokenStream(sql, new Lexer(sql).tokenize());

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

        if (tokenStream.peekKeyword("DELETE")) {
            return new DeleteParser(tokenStream).parse();
        }
        if (tokenStream.peekKeyword("TRUNCATE")) {
            return new TruncateParser(tokenStream).parse();
        }
        if (tokenStream.peekKeyword("DROP")) {
            return new DropParser(tokenStream).parse();
        }

        throw tokenStream.error("Expected SELECT, DELETE, TRUNCATE or DROP");
    }
}
