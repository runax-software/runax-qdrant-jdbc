package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.AddColumnStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DropColumnStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import java.sql.SQLSyntaxErrorException;

/**
 * Reads {@code ALTER TABLE docs ADD [COLUMN] [IF NOT EXISTS] column} and
 * {@code ALTER TABLE docs DROP [COLUMN] [IF EXISTS] name}, and hands {@code ALTER VIEW} to the
 * view parser. ADD, COLUMN, IF and EXISTS are words only here.
 */
public final class AlterParser {

    private final TokenStream tokenStream;

    public AlterParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
    }

    public Statement parse() throws SQLSyntaxErrorException {
        this.tokenStream.expectKeyword("ALTER");

        if (!this.tokenStream.matchKeyword("TABLE")) {
            if (!this.tokenStream.peekWord("VIEW")) {
                throw this.tokenStream.error("Expected TABLE or VIEW");
            }

            return new ViewParser(this.tokenStream).parseAlter();
        }

        TableName table = new TableParser(this.tokenStream).parse();

        if (this.tokenStream.matchWord("ADD")) {
            this.tokenStream.matchWord("COLUMN");

            boolean ifNotExists = this.tokenStream.peekWord("IF");
            if (ifNotExists) {
                this.tokenStream.advance();
                this.tokenStream.expectKeyword("NOT");
                expectWord("EXISTS");
            }

            return new AddColumnStatement(table, new ColumnParser(this.tokenStream).parse(),
                ifNotExists);
        }

        if (this.tokenStream.matchKeyword("DROP")) {
            this.tokenStream.matchWord("COLUMN");

            boolean ifExists = this.tokenStream.peekWord("IF")
                && this.tokenStream.peekWordAfterNext("EXISTS");
            if (ifExists) {
                this.tokenStream.advance();
                this.tokenStream.advance();
            }

            return new DropColumnStatement(table, this.tokenStream.expectIdentifier(), ifExists);
        }

        throw this.tokenStream.error("Expected ADD COLUMN or DROP COLUMN: that's what ALTER "
            + "TABLE changes so far");
    }

    private void expectWord(String word) throws SQLSyntaxErrorException {
        if (!this.tokenStream.matchWord(word)) {
            throw this.tokenStream.error("Expected " + word);
        }
    }
}
