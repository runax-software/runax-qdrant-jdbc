package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;

import java.sql.SQLSyntaxErrorException;

/**
 * Reads the name of a table: {@code docs}, {@code system.collections}, or
 * {@code qdrant.system.collections}. Tools qualify the names they generate, so a statement
 * says which schema it reads even though Qdrant has no schemas of its own. Which names exist
 * is the planner's to say; this only reads the parts.
 */
public final class TableParser {

    private static final int MAX_PARTS = 3;

    private final TokenStream tokenStream;

    public TableParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
    }

    public TableName parse() throws SQLSyntaxErrorException {
        var parts = new String[MAX_PARTS];
        int count = 0;

        do {
            if (count == MAX_PARTS) {
                throw tokenStream.error("A table name has at most catalog, schema, and name");
            }

            parts[count++] = tokenStream.expectIdentifier();
        } while (tokenStream.matchSymbol("."));

        return switch (count) {
            case 1 -> TableName.of(parts[0]);
            case 2 -> TableName.of(parts[0], parts[1]);
            default -> new TableName(parts[0], parts[1], parts[2]);
        };
    }
}
