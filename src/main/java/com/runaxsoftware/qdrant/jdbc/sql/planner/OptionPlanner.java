package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.runaxsoftware.qdrant.jdbc.gateway.models.RequestOption;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Parameter;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.ResetStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SetStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.ShowStatement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.SetOptionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ShowOptionsPlan;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.util.Arrays;
import java.util.List;

/**
 * Plans the statements about the connection's own request options. Their names are
 * {@code qdrant.<option>}: the prefix keeps them apart from the settings other databases take,
 * which a tool may send by habit, and which this driver reports as unsupported rather than
 * quietly ignoring.
 */
final class OptionPlanner {

    private static final String PREFIX = "qdrant";

    Plan plan(SetStatement statement) throws SQLException {
        RequestOption option = option(statement.name());

        return new SetOptionPlan(option, value(option, statement.value()));
    }

    Plan plan(ResetStatement statement) throws SQLException {
        return new SetOptionPlan(statement.name() == null ? null : option(statement.name()), null);
    }

    Plan plan(ShowStatement statement) throws SQLException {
        return new ShowOptionsPlan(statement.name() == null
            ? Arrays.asList(RequestOption.values())
            : List.of(option(statement.name())));
    }

    private static RequestOption option(List<String> name) throws SQLException {
        String written = String.join(".", name);

        if (name.size() != 2 || !name.get(0).equalsIgnoreCase(PREFIX)) {
            throw new SQLFeatureNotSupportedException("'" + written + "' isn't an option this "
                + "driver has: it has " + RequestOption.names());
        }

        return RequestOption.named(name.get(1)).orElseThrow(() -> new SQLSyntaxErrorException(
            "Unknown option '" + written + "': this driver has " + RequestOption.names(),
            "42704"));
    }

    // The value as the option takes it, or null for DEFAULT
    private static Object value(RequestOption option, Expression value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof Parameter) {
            throw new SQLFeatureNotSupportedException("SET takes its value written out, not a "
                + "parameter");
        }
        if (!(value instanceof Literal literal) || literal.value() == null) {
            throw new SQLSyntaxErrorException("qdrant." + option.key() + " takes a value written "
                + "out, or DEFAULT", "22023");
        }

        return option.value(literal.value());
    }
}
