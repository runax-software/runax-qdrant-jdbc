package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ExpressionParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CallArgument;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CallStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code CALL [qdrant.]procedure(value, ..., name => value, ...)}. An argument is a
 * constant or a {@code ?}, given by its position or, after {@code name =>}, by its name. CALL is
 * a word only at the start of a statement, so it stays usable as a name.
 */
public final class CallParser {

    private final TokenStream tokenStream;

    public CallParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
    }

    public Statement parse() throws SQLSyntaxErrorException {
        if (!this.tokenStream.matchWord("CALL")) {
            throw this.tokenStream.error("Expected CALL");
        }

        var names = new ArrayList<String>();
        do {
            names.add(this.tokenStream.expectIdentifier());
        } while (this.tokenStream.matchSymbol("."));

        String procedure = names.remove(names.size() - 1);
        List<CallArgument> arguments = new ArrayList<>();
        var expressions = new ExpressionParser(this.tokenStream);

        this.tokenStream.expectSymbol("(");
        if (!this.tokenStream.peekSymbol(")")) {
            do {
                String name = null;
                if (this.tokenStream.peekIdentifier()) {
                    name = this.tokenStream.expectIdentifier();
                    this.tokenStream.expectSymbol("=>");
                }

                arguments.add(new CallArgument(name, expressions.parseValue()));
            } while (this.tokenStream.matchSymbol(","));
        }
        this.tokenStream.expectSymbol(")");

        return new CallStatement(names, procedure, arguments);
    }
}
