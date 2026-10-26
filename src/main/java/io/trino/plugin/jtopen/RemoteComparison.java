/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.jtopen;

import com.google.common.collect.ImmutableList;
import io.trino.plugin.base.expression.ConnectorExpressionRule.RewriteContext;
import io.trino.plugin.jdbc.JdbcColumnHandle;
import io.trino.plugin.jdbc.JdbcTypeHandle;
import io.trino.plugin.jdbc.QueryParameter;
import io.trino.plugin.jdbc.expression.ParameterizedExpression;
import io.trino.spi.expression.ConnectorExpression;
import io.trino.spi.expression.Constant;
import io.trino.spi.expression.Variable;
import io.trino.spi.type.CharType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;

import java.util.List;
import java.util.Optional;

import static io.trino.plugin.jtopen.JTOpenConfig.VarcharComparisonSemantics.PAD_SPACE;
import static io.trino.plugin.jtopen.JTOpenSessionProperties.getVarcharComparisonSemantics;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;

/**
 * Decides how a comparison over the values in an expression can be pushed down to Db2 for i, which compares CHAR and
 * VARCHAR with PAD SPACE semantics (the shorter operand is padded with blanks) and with the sort sequence of the
 * connection, while Trino compares varchar with NO PAD and is always case-sensitive.
 * <ul>
 * <li>Under a shared-weight sort sequence Db2 for i ignores case, so no character comparison is pushed down.</li>
 * <li>CHAR operands compare the same way in both systems, because Trino ignores trailing blanks in CHAR values too.</li>
 * <li>VARCHAR operands are pushed down as they are when the session accepts PAD SPACE semantics. Otherwise the
 * comparison is pushed down together with a check that both operands have the same character count, which under a
 * case-sensitive sort sequence turns the padded equality into Trino's exact equality. Db2 for i counts the characters
 * of both operands, a constant after a cast to the type of the column it is compared with, so that the count is the
 * same whatever the CCSID of the column.</li>
 * </ul>
 */
final class RemoteComparison
{
    enum Mode
    {
        /**
         * Db2 for i would compare differently from Trino. The comparison stays in the engine.
         */
        NOT_SUPPORTED,
        /**
         * Db2 for i compares the same way as Trino, or the session accepts its PAD SPACE semantics. The comparison is
         * pushed down as it is.
         */
        AS_IS,
        /**
         * Db2 for i pads VARCHAR operands. The comparison is pushed down with a character length check that makes it
         * exact.
         */
        WITH_LENGTH_CHECK,
    }

    // The longest VARCHAR and NVARCHAR types Db2 for i allows. Casting a parameter to them never truncates it, so the
    // character count of the parameter is compared, not that of a value cut to the length of the column.
    static final String VARCHAR_PARAMETER_TYPE = "VARCHAR(32739)";
    static final String NVARCHAR_PARAMETER_TYPE = "NVARCHAR(16369)";

    private final boolean caseInsensitiveSortSequence;

    RemoteComparison(boolean caseInsensitiveSortSequence)
    {
        this.caseInsensitiveSortSequence = caseInsensitiveSortSequence;
    }

    Mode mode(ConnectorExpression expression, RewriteContext<?> context)
    {
        List<ConnectorExpression> nodes = flatten(expression);
        boolean fixedLength = nodes.stream().anyMatch(node -> node.getType() instanceof CharType);
        boolean varcharPresent = nodes.stream().anyMatch(node -> node.getType() instanceof VarcharType);
        if (!fixedLength && !varcharPresent) {
            return Mode.AS_IS;
        }
        if (caseInsensitiveSortSequence) {
            return Mode.NOT_SUPPORTED;
        }
        if (!varcharPresent || getVarcharComparisonSemantics(context.getSession()) == PAD_SPACE) {
            return Mode.AS_IS;
        }
        if (fixedLength) {
            // CHARACTER_LENGTH of a CHAR column counts its blank padding, so mixed CHAR and VARCHAR operands cannot be checked
            return Mode.NOT_SUPPORTED;
        }
        for (ConnectorExpression node : nodes) {
            if (node instanceof Variable variable && variable.getType() instanceof VarcharType && parameterCastType(columnTypeHandle(variable, context)).isEmpty()) {
                return Mode.NOT_SUPPORTED;
            }
        }
        return Mode.WITH_LENGTH_CHECK;
    }

    /**
     * The type a parameter is cast to before its characters are counted, for the column it is compared with. Only
     * VARCHAR and NVARCHAR columns are supported; comparisons with other character columns stay in the engine.
     */
    static Optional<String> parameterCastType(JdbcTypeHandle typeHandle)
    {
        return typeHandle.jdbcTypeName().flatMap(name -> switch (name.toUpperCase(ENGLISH)) {
            case "VARCHAR" -> Optional.of(VARCHAR_PARAMETER_TYPE);
            case "NVARCHAR" -> Optional.of(NVARCHAR_PARAMETER_TYPE);
            default -> Optional.empty();
        });
    }

    /**
     * {@code CHARACTER_LENGTH(left) = CHARACTER_LENGTH(right)} for two rewritten operands. A constant operand is cast to
     * the type of the column it is compared with and its parameter is bound again, in operand order. Empty when an
     * operand is neither a column nor a constant compared with a column.
     */
    static Optional<String> characterLengthCheck(
            ConnectorExpression left,
            ParameterizedExpression leftSql,
            ConnectorExpression right,
            ParameterizedExpression rightSql,
            RewriteContext<?> context,
            ImmutableList.Builder<QueryParameter> parameters)
    {
        Optional<String> leftLength = characterLength(left, leftSql, right, context);
        Optional<String> rightLength = characterLength(right, rightSql, left, context);
        if (leftLength.isEmpty() || rightLength.isEmpty()) {
            return Optional.empty();
        }
        if (left instanceof Constant) {
            parameters.addAll(leftSql.parameters());
        }
        if (right instanceof Constant) {
            parameters.addAll(rightSql.parameters());
        }
        return Optional.of(format("CHARACTER_LENGTH(%s) = CHARACTER_LENGTH(%s)", leftLength.get(), rightLength.get()));
    }

    private static Optional<String> characterLength(ConnectorExpression operand, ParameterizedExpression sql, ConnectorExpression other, RewriteContext<?> context)
    {
        if (operand instanceof Variable) {
            return Optional.of(sql.expression());
        }
        if (operand instanceof Constant && other instanceof Variable variable) {
            return parameterCastType(columnTypeHandle(variable, context))
                    .map(castType -> format("CAST(%s AS %s)", sql.expression(), castType));
        }
        return Optional.empty();
    }

    private static JdbcTypeHandle columnTypeHandle(Variable variable, RewriteContext<?> context)
    {
        return ((JdbcColumnHandle) context.getAssignment(variable.getName())).getJdbcTypeHandle();
    }

    private static List<ConnectorExpression> flatten(ConnectorExpression expression)
    {
        ImmutableList.Builder<ConnectorExpression> nodes = ImmutableList.builder();
        collect(expression, nodes);
        return nodes.build();
    }

    private static void collect(ConnectorExpression expression, ImmutableList.Builder<ConnectorExpression> nodes)
    {
        nodes.add(expression);
        for (ConnectorExpression child : expression.getChildren()) {
            collect(child, nodes);
        }
    }
}
