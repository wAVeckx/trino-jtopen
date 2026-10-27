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
import io.trino.matching.Pattern;
import io.trino.plugin.base.expression.ConnectorExpressionRule;
import io.trino.plugin.jdbc.QueryParameter;
import io.trino.plugin.jdbc.expression.ParameterizedExpression;
import io.trino.spi.expression.Constant;
import io.trino.spi.type.CharType;

import java.util.Optional;

import static io.trino.plugin.base.expression.ConnectorExpressionPatterns.constant;
import static io.trino.plugin.base.expression.ConnectorExpressionPatterns.type;

/**
 * Binds {@code char} constants as query parameters, the way the standard rules bind {@code varchar} constants. The
 * bound value carries no trailing blanks, which Db2 for i supplies when comparing it with a CHAR column.
 */
public class RewriteJTOpenCharConstant
        implements ConnectorExpressionRule<Constant, ParameterizedExpression>
{
    private static final Pattern<Constant> PATTERN = constant().with(type().matching(CharType.class::isInstance));

    @Override
    public Pattern<Constant> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Optional<ParameterizedExpression> rewrite(Constant constant, Captures captures, RewriteContext<ParameterizedExpression> context)
    {
        Object value = constant.getValue();
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(new ParameterizedExpression("?", ImmutableList.of(new QueryParameter(constant.getType(), Optional.of(value)))));
    }
}
