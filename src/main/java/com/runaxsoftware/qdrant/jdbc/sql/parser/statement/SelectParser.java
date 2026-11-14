package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ExpressionParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.*;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DistanceMatrixStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.PerGroup;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SelectStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;

public final class SelectParser {

    private static final String MATRIX = "distance_matrix";

    // What distance_matrix(...) was called with
    private record MatrixCall(TableName table, String vector, Long sample, Long neighbours) {}

    private final TokenStream tokenStream;
    private final ExpressionParser expressionParser;
    private final TableParser tableParser;

    public SelectParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
        this.expressionParser = new ExpressionParser(tokenStream);
        this.tableParser = new TableParser(tokenStream);
    }

    public Statement parse() throws SQLSyntaxErrorException {
        this.tokenStream.expectKeyword("SELECT");

        // DISTINCT ON (column) keeps one row of each of the column's values; plain DISTINCT is
        // refused where the select list is read, with what to write instead
        Column distinctOn = null;
        if (this.tokenStream.peekKeyword("DISTINCT") && this.tokenStream.peekWordAfterNext("ON")) {
            this.tokenStream.advance();
            this.tokenStream.advance();
            distinctOn = parseGroupColumn();
        }

        var items = new ArrayList<SelectItem>();

        do {
            items.add(parseItem());
        } while (this.tokenStream.matchSymbol(","));

        // FROM is optional: SELECT 'keep alive' reads no collection
        TableName table = null;
        String alias = null;
        MatrixCall matrix = null;
        if (this.tokenStream.matchKeyword("FROM")) {
            table = this.tableParser.parse();

            // A name with brackets after it is a table function, and there is one
            if (this.tokenStream.peekSymbol("(")) matrix = parseMatrix(table);

            alias = parseAlias();
        } else if (this.tokenStream.peekIdentifier()) {
            // "SELECT name docs" is a missing FROM; aliases need AS
            throw this.tokenStream.error("Expected FROM");
        }

        Expression where = this.tokenStream.matchKeyword("WHERE") ? this.expressionParser.parseOr() : null;

        var groupBy = new ArrayList<Column>();
        if (this.tokenStream.matchKeyword("GROUP")) {
            this.tokenStream.expectKeyword("BY");

            do {
                groupBy.add(this.expressionParser.parseColumn());
            } while (this.tokenStream.matchSymbol(","));
        }

        var orderBy = new ArrayList<OrderItem>();
        if (this.tokenStream.matchKeyword("ORDER")) {
            this.tokenStream.expectKeyword("BY");

            do {
                Expression term = this.expressionParser.parseTerm();
                boolean descending = this.tokenStream.matchKeyword("DESC");

                if (!descending) {
                    this.tokenStream.matchKeyword("ASC");
                }

                orderBy.add(new OrderItem(term, descending));
            } while(this.tokenStream.matchSymbol(","));
        }

        Expression limit = this.tokenStream.matchKeyword("LIMIT") ? parseRowCount() : null;
        PerGroup perGroup = distinctOn == null ? null : new PerGroup(distinctOn, new Literal(1L));

        // LIMIT n BY column keeps n rows of each of the column's values; the LIMIT that may
        // follow it is the statement's own
        if (limit != null && this.tokenStream.matchKeyword("BY")) {
            if (distinctOn != null) {
                throw this.tokenStream.error("Expected one of DISTINCT ON and LIMIT ... BY: both "
                    + "say how many rows of each group to keep");
            }

            perGroup = new PerGroup(this.expressionParser.parseColumn(), limit);
            limit = this.tokenStream.matchKeyword("LIMIT") ? parseRowCount() : null;
        }

        Expression offset = this.tokenStream.matchKeyword("OFFSET") ? parseRowCount() : null;

        if (matrix != null) {
            if (!groupBy.isEmpty() || !orderBy.isEmpty() || perGroup != null) {
                throw this.tokenStream.error("Expected a column list, WHERE, LIMIT and OFFSET "
                    + "only: " + MATRIX + " takes no GROUP BY, ORDER BY, DISTINCT ON or LIMIT "
                    + "... BY");
            }

            return new DistanceMatrixStatement(items, matrix.table(), matrix.vector(),
                matrix.sample(), matrix.neighbours(), alias, where, limit, offset);
        }

        return new SelectStatement(items, table, alias, where, groupBy, orderBy, limit,
            offset, perGroup);
    }

    /**
     * What a LIMIT or OFFSET says: a whole number written out, or a {@code ?} whose value a
     * prepared statement gives before each run. The placeholder is numbered where it stands, so
     * it counts after any in WHERE or ORDER BY, and the planner checks its value once it's bound.
     */
    private Expression parseRowCount() throws SQLSyntaxErrorException {
        if (this.tokenStream.matchParameter()) {
            return new Parameter(this.tokenStream.nextParameterIndex());
        }

        return new Literal(this.tokenStream.expectWholeNumber());
    }

    // distance_matrix(collection, vector [, sample [, neighbours]]), once its name is read
    private MatrixCall parseMatrix(TableName function) throws SQLSyntaxErrorException {
        if (function.schema() != null || !function.name().equalsIgnoreCase(MATRIX)) {
            throw this.tokenStream.error("Expected a table: '" + function.qualified() + "' isn't "
                + "a table function this driver has. It has " + MATRIX
                + "(collection, vector [, sample [, neighbours]])");
        }

        this.tokenStream.expectSymbol("(");
        TableName table = this.tableParser.parse();
        this.tokenStream.expectSymbol(",");
        String vector = this.tokenStream.expectIdentifier();
        Long sample = this.tokenStream.matchSymbol(",") ? this.tokenStream.expectWholeNumber() : null;
        Long neighbours = sample != null && this.tokenStream.matchSymbol(",")
            ? this.tokenStream.expectWholeNumber() : null;
        this.tokenStream.expectSymbol(")");

        return new MatrixCall(table, vector, sample, neighbours);
    }

    // The one column in brackets after DISTINCT ON; Qdrant groups a search by one field
    private Column parseGroupColumn() throws SQLSyntaxErrorException {
        this.tokenStream.expectSymbol("(");
        Column column = this.expressionParser.parseColumn();

        if (this.tokenStream.peekSymbol(",")) {
            throw this.tokenStream.error("Expected ): Qdrant groups a search by one field");
        }

        this.tokenStream.expectSymbol(")");
        return column;
    }

    /**
     * One entry of the select list: {@code *}, {@code t.*}, a column named on its own or under
     * the table or an alias, a call such as {@code COUNT(*)}, or a constant, each with its own
     * alias or none. A star takes no alias of its own, as it stands for several columns.
     */
    private SelectItem parseItem() throws SQLSyntaxErrorException {
        if (this.tokenStream.matchSymbol("*")) {
            return new SelectItem(Star.of(), null);
        }

        if (this.tokenStream.peekKeyword("DISTINCT")) {
            throw this.tokenStream.errorAt("SELECT DISTINCT isn't supported yet: a field's "
                + "distinct values come from Qdrant's facet API", "42000");
        }

        if (!this.tokenStream.peekIdentifier()) {
            return item(this.expressionParser.parseValue());
        }

        String name = this.tokenStream.expectIdentifier();
        if (this.tokenStream.peekSymbol("(")) {
            return item(this.expressionParser.parseCall(name));
        }

        if (!this.tokenStream.matchSymbol(".")) {
            return item(Column.of(name));
        }

        return this.tokenStream.matchSymbol("*")
            ? new SelectItem(new Star(name), null)
            : item(new Column(name, this.tokenStream.expectIdentifier()));
    }

    private SelectItem item(Expression expression) throws SQLSyntaxErrorException {
        String alias = this.tokenStream.matchKeyword("AS") ? this.tokenStream.expectIdentifier() : null;

        return new SelectItem(expression, alias);
    }

    /**
     * The name a statement gives the table it reads, with or without AS, as tools write when
     * they qualify the columns they select. Keywords such as WHERE and LIMIT aren't names, so
     * a statement without an alias reads the same as before.
     */
    private String parseAlias() throws SQLSyntaxErrorException {
        if (this.tokenStream.matchKeyword("AS")) {
            return this.tokenStream.expectIdentifier();
        }

        return this.tokenStream.peekIdentifier() ? this.tokenStream.expectIdentifier() : null;
    }
}
