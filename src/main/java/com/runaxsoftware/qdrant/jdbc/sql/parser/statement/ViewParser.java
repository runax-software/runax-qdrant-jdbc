package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.AlterViewStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CreateViewStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DropViewStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SelectStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import java.sql.SQLSyntaxErrorException;

/**
 * Reads the statements about views, which are Qdrant aliases: the rest of
 * {@code CREATE [OR REPLACE] VIEW name AS SELECT ...} once CREATE is read, of
 * {@code DROP VIEW [IF EXISTS] name} once DROP is read, and {@code ALTER VIEW name RENAME TO
 * other}. VIEW, REPLACE, RENAME and TO are words only here.
 */
public final class ViewParser {

    private final TokenStream tokenStream;

    public ViewParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
    }

    /**
     * @param orReplace whether the statement said OR REPLACE, which the caller has read
     */
    public Statement parseCreate(boolean orReplace) throws SQLSyntaxErrorException {
        expectWord("VIEW");
        TableName view = new TableParser(this.tokenStream).parse();
        this.tokenStream.expectKeyword("AS");

        if (!this.tokenStream.peekKeyword("SELECT")) {
            throw this.tokenStream.error("Expected SELECT * FROM the collection the view shows");
        }

        return new CreateViewStatement(view,
            (SelectStatement) new SelectParser(this.tokenStream).parse(), orReplace);
    }

    public Statement parseAlter() throws SQLSyntaxErrorException {
        this.tokenStream.expectKeyword("ALTER");

        if (this.tokenStream.peekKeyword("TABLE")) {
            throw this.tokenStream.error("Expected VIEW: ALTER TABLE isn't supported yet");
        }

        expectWord("VIEW");
        TableName view = new TableParser(this.tokenStream).parse();
        expectWord("RENAME");
        expectWord("TO");

        return new AlterViewStatement(view, this.tokenStream.expectIdentifier());
    }

    public Statement parseDrop() throws SQLSyntaxErrorException {
        expectWord("VIEW");

        boolean ifExists = this.tokenStream.peekWord("IF")
            && this.tokenStream.peekWordAfterNext("EXISTS");
        if (ifExists) {
            this.tokenStream.advance();
            this.tokenStream.advance();
        }

        return new DropViewStatement(new TableParser(this.tokenStream).parse(), ifExists);
    }

    private void expectWord(String word) throws SQLSyntaxErrorException {
        if (!this.tokenStream.peekWord(word)) {
            throw this.tokenStream.error("Expected " + word);
        }

        this.tokenStream.advance();
    }
}
