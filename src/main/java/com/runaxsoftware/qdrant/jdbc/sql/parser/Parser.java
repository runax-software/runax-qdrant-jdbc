package com.runaxsoftware.qdrant.jdbc.sql.parser;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.*;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.statement.CreateParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.statement.DeleteParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.statement.DropParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.statement.InsertParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.statement.OptionParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.statement.SelectParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.statement.TruncateParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.statement.UpdateParser;

import java.sql.SQLSyntaxErrorException;

public final class Parser {

    public static Statement parse(String sql) throws SQLSyntaxErrorException {
        var tokenStream = new TokenStream(sql, new Lexer(sql).tokenize());

        Statement statement = parseStatement(tokenStream);

        // One semicolon may end the statement; a second statement after it is an error
        if (tokenStream.matchSymbol(";") && tokenStream.peek().type() != TokenType.EOF) {
            throw tokenStream.error("Expected the end of the statement: run one statement at a "
                + "time");
        }
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
        if (tokenStream.peekKeyword("CREATE")) {
            return new CreateParser(tokenStream).parse();
        }
        if (tokenStream.peekKeyword("INSERT")) {
            return new InsertParser(tokenStream).parse();
        }
        if (tokenStream.peekKeyword("UPDATE")) {
            return new UpdateParser(tokenStream).parse();
        }
        if (tokenStream.peekKeyword("SET") || tokenStream.peekWord("RESET")
            || tokenStream.peekWord("SHOW")) {
            return new OptionParser(tokenStream).parse();
        }

        throw tokenStream.error("Expected SELECT, INSERT, UPDATE, DELETE, TRUNCATE, DROP, CREATE, "
            + "SET, RESET or SHOW");
    }
}
