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

import com.google.inject.Inject;
import io.trino.plugin.jdbc.DefaultQueryBuilder;
import io.trino.plugin.jdbc.JdbcClient;
import io.trino.plugin.jdbc.JdbcColumnHandle;
import io.trino.plugin.jdbc.JdbcTypeHandle;
import io.trino.plugin.jdbc.QueryParameter;
import io.trino.plugin.jdbc.WriteFunction;
import io.trino.plugin.jdbc.logging.RemoteQueryModifier;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.predicate.ValueSet;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.trino.plugin.jtopen.JTOpenConfig.VarcharComparisonSemantics.EXACT;
import static io.trino.plugin.jtopen.JTOpenSessionProperties.getVarcharComparisonSemantics;
import static io.trino.plugin.jtopen.RemoteComparison.parameterCastType;
import static java.lang.String.format;

/**
 * Renders equality predicates on VARCHAR columns so that Db2 for i returns Trino's results. Db2 for i compares VARCHAR
 * with PAD SPACE semantics, under which {@code 'a' = 'a '}. Under a case-sensitive sort sequence a padded equality
 * together with equal character counts is an exact equality, so {@code v = 'a'} is sent as
 * {@code (v = ? AND CHARACTER_LENGTH(v) = CHARACTER_LENGTH(CAST(? AS VARCHAR(32739))))}, which still uses an index on
 * {@code v}. Db2 for i counts the characters of both the column and the parameter, so the count is the same whatever
 * the CCSID of the column. The same idea as the {@code COLLATE "C"} rewrite of the PostgreSQL connector.
 */
public class JTOpenQueryBuilder
        extends DefaultQueryBuilder
{
    private final boolean caseInsensitiveSortSequence;

    @Inject
    public JTOpenQueryBuilder(RemoteQueryModifier queryModifier, JTOpenConfig config)
    {
        super(queryModifier);
        this.caseInsensitiveSortSequence = config.isCaseInsensitiveSortSequence();
    }

    @Override
    protected String toPredicate(JdbcClient client, ConnectorSession session, Connection connection, JdbcColumnHandle column, ValueSet valueSet, Consumer<QueryParameter> accumulator)
    {
        Optional<String> castType = exactVarcharParameterType(session, column);
        if (castType.isEmpty()) {
            return super.toPredicate(client, session, connection, column, valueSet, accumulator);
        }
        checkArgument(!valueSet.isNone(), "none values should be handled earlier");

        if (!valueSet.isDiscreteSet()) {
            ValueSet complement = valueSet.complement();
            if (complement.isDiscreteSet()) {
                return format("NOT (%s)", toPredicate(client, session, connection, column, complement, accumulator));
            }
            // Ranges on character columns are never pushed down by JTOpenClient
            return super.toPredicate(client, session, connection, column, valueSet, accumulator);
        }

        JdbcTypeHandle jdbcType = column.getJdbcTypeHandle();
        Type type = column.getColumnType();
        WriteFunction writeFunction = client.toColumnMapping(session, connection, jdbcType)
                .orElseThrow(() -> new IllegalStateException(format("Unsupported type %s with handle %s", type, jdbcType)))
                .getWriteFunction();
        String columnName = client.quoted(column.getColumnName());
        String bindExpression = writeFunction.getBindExpression();

        List<String> disjuncts = new ArrayList<>();
        for (Object value : valueSet.getDiscreteSet()) {
            accumulator.accept(new QueryParameter(jdbcType, type, Optional.of(value)));
            accumulator.accept(new QueryParameter(jdbcType, type, Optional.of(value)));
            disjuncts.add(format(
                    "(%s = %s AND CHARACTER_LENGTH(%s) = CHARACTER_LENGTH(CAST(%s AS %s)))",
                    columnName,
                    bindExpression,
                    columnName,
                    bindExpression,
                    castType.get()));
        }
        if (disjuncts.size() == 1) {
            return getOnlyElement(disjuncts);
        }
        return "(" + String.join(" OR ", disjuncts) + ")";
    }

    private Optional<String> exactVarcharParameterType(ConnectorSession session, JdbcColumnHandle column)
    {
        if (caseInsensitiveSortSequence || !(column.getColumnType() instanceof VarcharType) || getVarcharComparisonSemantics(session) != EXACT) {
            return Optional.empty();
        }
        return parameterCastType(column.getJdbcTypeHandle());
    }
}
