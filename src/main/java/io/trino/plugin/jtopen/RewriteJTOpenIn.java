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
import io.trino.matching.Captures;
import io.trino.plugin.jdbc.QueryParameter;
import io.trino.plugin.jdbc.expression.ParameterizedExpression;
import io.trino.plugin.jdbc.expression.RewriteIn;
import io.trino.plugin.jtopen.RemoteComparison.Mode;
import io.trino.spi.expression.Call;
import io.trino.spi.expression.ConnectorExpression;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.trino.plugin.jdbc.JdbcMetadataSessionProperties.getDomainCompactionThreshold;
import static io.trino.plugin.jtopen.RemoteComparison.characterLengthCheck;
import static java.util.Objects.requireNonNull;

/**
 * Pushes {@code IN} down as {@link RemoteComparison} allows: as it is, or as one exact equality per value.
 */
public class RewriteJTOpenIn
        extends RewriteIn
{
    private final RemoteComparison remoteComparison;

    public RewriteJTOpenIn(RemoteComparison remoteComparison)
    {
        this.remoteComparison = requireNonNull(remoteComparison, "remoteComparison is null");
    }

    @Override
    public Optional<ParameterizedExpression> rewrite(Call call, Captures captures, RewriteContext<ParameterizedExpression> context)
    {
        Mode mode = remoteComparison.mode(call, context);
        if (mode == Mode.NOT_SUPPORTED) {
            return Optional.empty();
        }
        if (mode == Mode.AS_IS) {
            return super.rewrite(call, captures, context);
        }

        // The pattern guarantees a value and an array constructor of the values to compare with
        ConnectorExpression value = call.getArguments().get(0);
        List<ConnectorExpression> expressions = ((Call) call.getArguments().get(1)).getArguments();
        if (expressions.size() > getDomainCompactionThreshold(context.getSession())) {
            // We don't want to push down too long IN query text
            return Optional.empty();
        }
        Optional<ParameterizedExpression> valueSql = context.defaultRewrite(value);
        if (valueSql.isEmpty()) {
            return Optional.empty();
        }

        ImmutableList.Builder<QueryParameter> parameters = ImmutableList.builder();
        List<String> disjuncts = new ArrayList<>();
        for (ConnectorExpression expression : expressions) {
            Optional<ParameterizedExpression> sql = context.defaultRewrite(expression);
            if (sql.isEmpty()) {
                return Optional.empty();
            }
            parameters.addAll(valueSql.get().parameters()).addAll(sql.get().parameters());
            Optional<String> lengthCheck = characterLengthCheck(value, valueSql.get(), expression, sql.get(), context, parameters);
            if (lengthCheck.isEmpty()) {
                return Optional.empty();
            }
            disjuncts.add("((%s) = (%s) AND %s)".formatted(valueSql.get().expression(), sql.get().expression(), lengthCheck.get()));
        }
        return Optional.of(new ParameterizedExpression("(" + String.join(" OR ", disjuncts) + ")", parameters.build()));
    }
}
