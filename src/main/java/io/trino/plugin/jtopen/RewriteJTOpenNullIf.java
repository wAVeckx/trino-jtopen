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
import io.trino.matching.Capture;
import io.trino.matching.Captures;
import io.trino.matching.Pattern;
import io.trino.plugin.base.expression.ConnectorExpressionRule;
import io.trino.plugin.jdbc.QueryParameter;
import io.trino.plugin.jdbc.expression.ParameterizedExpression;
import io.trino.plugin.jtopen.RemoteComparison.Mode;
import io.trino.spi.expression.Call;
import io.trino.spi.expression.ConnectorExpression;

import java.util.Optional;

import static io.trino.matching.Capture.newCapture;
import static io.trino.plugin.base.expression.ConnectorExpressionPatterns.argument;
import static io.trino.plugin.base.expression.ConnectorExpressionPatterns.argumentCount;
import static io.trino.plugin.base.expression.ConnectorExpressionPatterns.call;
import static io.trino.plugin.base.expression.ConnectorExpressionPatterns.expression;
import static io.trino.plugin.base.expression.ConnectorExpressionPatterns.functionName;
import static io.trino.plugin.jtopen.RemoteComparison.characterLengthCheck;
import static io.trino.spi.expression.StandardFunctions.NULLIF_FUNCTION_NAME;
import static java.util.Objects.requireNonNull;

/**
 * Pushes {@code NULLIF} down as {@link RemoteComparison} allows: as it is, or as a CASE expression with an exact
 * equality.
 */
public class RewriteJTOpenNullIf
        implements ConnectorExpressionRule<Call, ParameterizedExpression>
{
    private static final Capture<ConnectorExpression> FIRST = newCapture();
    private static final Capture<ConnectorExpression> SECOND = newCapture();

    private static final Pattern<Call> PATTERN = call()
            .with(functionName().equalTo(NULLIF_FUNCTION_NAME))
            .with(argumentCount().equalTo(2))
            .with(argument(0).matching(expression().capturedAs(FIRST)))
            .with(argument(1).matching(expression().capturedAs(SECOND)));

    private final RemoteComparison remoteComparison;

    public RewriteJTOpenNullIf(RemoteComparison remoteComparison)
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
        ConnectorExpression first = captures.get(FIRST);
        ConnectorExpression second = captures.get(SECOND);
        Optional<ParameterizedExpression> firstSql = context.defaultRewrite(first);
        Optional<ParameterizedExpression> secondSql = context.defaultRewrite(second);
        if (firstSql.isEmpty() || secondSql.isEmpty()) {
            return Optional.empty();
        }
        ImmutableList.Builder<QueryParameter> parameters = ImmutableList.<QueryParameter>builder()
                .addAll(firstSql.get().parameters())
                .addAll(secondSql.get().parameters());

        if (mode == Mode.AS_IS) {
            return Optional.of(new ParameterizedExpression(
                    "NULLIF((%s), (%s))".formatted(firstSql.get().expression(), secondSql.get().expression()),
                    parameters.build()));
        }

        Optional<String> lengthCheck = characterLengthCheck(first, firstSql.get(), second, secondSql.get(), context, parameters);
        if (lengthCheck.isEmpty()) {
            return Optional.empty();
        }
        // The first operand appears again as the result, so its parameters are bound again
        parameters.addAll(firstSql.get().parameters());
        return Optional.of(new ParameterizedExpression(
                "CASE WHEN (%s) = (%s) AND %s THEN NULL ELSE (%s) END".formatted(
                        firstSql.get().expression(), secondSql.get().expression(), lengthCheck.get(), firstSql.get().expression()),
                parameters.build()));
    }
}
