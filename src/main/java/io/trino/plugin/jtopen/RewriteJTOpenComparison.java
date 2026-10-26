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
import com.google.common.collect.ImmutableSet;
import io.trino.matching.Capture;
import io.trino.matching.Captures;
import io.trino.matching.Pattern;
import io.trino.plugin.base.expression.ConnectorExpressionRule;
import io.trino.plugin.jdbc.QueryParameter;
import io.trino.plugin.jdbc.expression.ComparisonOperator;
import io.trino.plugin.jdbc.expression.ParameterizedExpression;
import io.trino.plugin.jtopen.RemoteComparison.Mode;
import io.trino.spi.expression.Call;
import io.trino.spi.expression.ConnectorExpression;
import io.trino.spi.expression.FunctionName;
import io.trino.spi.type.CharType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;

import java.util.Optional;
import java.util.Set;

import static io.trino.matching.Capture.newCapture;
import static io.trino.plugin.base.expression.ConnectorExpressionPatterns.argument;
import static io.trino.plugin.base.expression.ConnectorExpressionPatterns.argumentCount;
import static io.trino.plugin.base.expression.ConnectorExpressionPatterns.call;
import static io.trino.plugin.base.expression.ConnectorExpressionPatterns.expression;
import static io.trino.plugin.base.expression.ConnectorExpressionPatterns.functionName;
import static io.trino.plugin.base.expression.ConnectorExpressionPatterns.type;
import static io.trino.plugin.jdbc.expression.ComparisonOperator.EQUAL;
import static io.trino.plugin.jdbc.expression.ComparisonOperator.NOT_EQUAL;
import static io.trino.plugin.jtopen.RemoteComparison.characterLengthCheck;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static java.util.Objects.requireNonNull;

/**
 * Pushes {@code =} and {@code <>} between character values down as {@link RemoteComparison} allows. Numeric
 * comparisons are handled by the generic rewrite rules, and range comparisons on character values are never pushed
 * down, because Db2 for i orders them by the sort sequence of the connection rather than by code point.
 */
public class RewriteJTOpenComparison
        implements ConnectorExpressionRule<Call, ParameterizedExpression>
{
    private static final Capture<ConnectorExpression> LEFT = newCapture();
    private static final Capture<ConnectorExpression> RIGHT = newCapture();
    private static final Set<FunctionName> SUPPORTED_FUNCTIONS = ImmutableSet.of(EQUAL.getFunctionName(), NOT_EQUAL.getFunctionName());

    private static final Pattern<Call> PATTERN = call()
            .with(type().equalTo(BOOLEAN))
            .with(functionName().matching(SUPPORTED_FUNCTIONS::contains))
            .with(argumentCount().equalTo(2))
            .with(argument(0).matching(expression().with(type().matching(RewriteJTOpenComparison::isCharacterType)).capturedAs(LEFT)))
            .with(argument(1).matching(expression().with(type().matching(RewriteJTOpenComparison::isCharacterType)).capturedAs(RIGHT)));

    private final RemoteComparison remoteComparison;

    public RewriteJTOpenComparison(RemoteComparison remoteComparison)
    {
        this.remoteComparison = requireNonNull(remoteComparison, "remoteComparison is null");
    }

    @Override
    public Pattern<Call> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Optional<ParameterizedExpression> rewrite(Call call, Captures captures, RewriteContext<ParameterizedExpression> context)
    {
        Mode mode = remoteComparison.mode(call, context);
        if (mode == Mode.NOT_SUPPORTED) {
            return Optional.empty();
        }
        ConnectorExpression left = captures.get(LEFT);
        ConnectorExpression right = captures.get(RIGHT);
        Optional<ParameterizedExpression> leftSql = context.defaultRewrite(left);
        Optional<ParameterizedExpression> rightSql = context.defaultRewrite(right);
        if (leftSql.isEmpty() || rightSql.isEmpty()) {
            return Optional.empty();
        }
        ComparisonOperator comparison = ComparisonOperator.forFunctionName(call.getFunctionName());
        ImmutableList.Builder<QueryParameter> parameters = ImmutableList.<QueryParameter>builder()
                .addAll(leftSql.get().parameters())
                .addAll(rightSql.get().parameters());

        if (mode == Mode.AS_IS) {
            return Optional.of(new ParameterizedExpression(
                    "(%s) %s (%s)".formatted(leftSql.get().expression(), comparison.getOperator(), rightSql.get().expression()),
                    parameters.build()));
        }

        Optional<String> lengthCheck = characterLengthCheck(left, leftSql.get(), right, rightSql.get(), context, parameters);
        if (lengthCheck.isEmpty()) {
            return Optional.empty();
        }
        String equality = "((%s) = (%s) AND %s)".formatted(leftSql.get().expression(), rightSql.get().expression(), lengthCheck.get());
        return Optional.of(new ParameterizedExpression(comparison == EQUAL ? equality : "NOT " + equality, parameters.build()));
    }

    private static boolean isCharacterType(Type type)
    {
        return type instanceof CharType || type instanceof VarcharType;
    }
}
