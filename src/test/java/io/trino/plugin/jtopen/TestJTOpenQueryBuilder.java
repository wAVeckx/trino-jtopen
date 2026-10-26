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

import com.google.common.collect.ImmutableMap;
import io.trino.plugin.jdbc.JdbcColumnHandle;
import io.trino.plugin.jdbc.JdbcNamedRelationHandle;
import io.trino.plugin.jdbc.PreparedQuery;
import io.trino.plugin.jdbc.RemoteTableName;
import io.trino.plugin.jdbc.logging.RemoteQueryModifier;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.Range;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.predicate.ValueSet;
import io.trino.spi.type.Type;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.plugin.jtopen.TestJTOpenClient.CASE_INSENSITIVE_CLIENT;
import static io.trino.plugin.jtopen.TestJTOpenClient.CHAR_TYPE_HANDLE;
import static io.trino.plugin.jtopen.TestJTOpenClient.CLIENT;
import static io.trino.plugin.jtopen.TestJTOpenClient.NVARCHAR_TYPE_HANDLE;
import static io.trino.plugin.jtopen.TestJTOpenClient.PAD_SPACE_SESSION;
import static io.trino.plugin.jtopen.TestJTOpenClient.SESSION;
import static io.trino.plugin.jtopen.TestJTOpenClient.VARCHAR_TYPE_HANDLE;
import static io.trino.plugin.jtopen.TestJTOpenClient.VARGRAPHIC_TYPE_HANDLE;
import static io.trino.plugin.jtopen.TestJTOpenClient.characterTypeHandle;
import static io.trino.plugin.jtopen.TestJTOpenClient.column;
import static io.trino.spi.type.CharType.createCharType;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the SQL that JTOpenQueryBuilder sends for predicates on character columns, after the pushdown controllers of
 * JTOpenClient have decided which part of a predicate is pushed down.
 */
public class TestJTOpenQueryBuilder
{
    private static final JTOpenQueryBuilder QUERY_BUILDER = new JTOpenQueryBuilder(RemoteQueryModifier.NONE, new JTOpenConfig());
    private static final JTOpenQueryBuilder CASE_INSENSITIVE_QUERY_BUILDER = new JTOpenQueryBuilder(RemoteQueryModifier.NONE, new JTOpenConfig().setCaseInsensitiveSortSequence(true));

    private static final JdbcColumnHandle VARCHAR_COLUMN = column("c_varchar", createVarcharType(10), VARCHAR_TYPE_HANDLE);
    private static final JdbcColumnHandle NVARCHAR_COLUMN = column("c_nvarchar", createVarcharType(10), NVARCHAR_TYPE_HANDLE);
    private static final JdbcColumnHandle VARGRAPHIC_COLUMN = column("c_vargraphic", createVarcharType(10), VARGRAPHIC_TYPE_HANDLE);
    private static final JdbcColumnHandle CHAR_COLUMN = column("c_char", createCharType(10), CHAR_TYPE_HANDLE);

    private static final JdbcNamedRelationHandle RELATION = new JdbcNamedRelationHandle(
            new SchemaTableName("test_schema", "test_table"),
            new RemoteTableName(Optional.empty(), Optional.empty(), "test_table"),
            Optional.empty());

    private static final String LENGTH_CHECK = "CHARACTER_LENGTH(\"c_varchar\") = CHARACTER_LENGTH(CAST(? AS VARCHAR(32739)))";

    @Test
    public void testVarcharEqualityIsExactUnderExactSemantics()
    {
        assertPredicate(QUERY_BUILDER, SESSION, VARCHAR_COLUMN, equalTo(VARCHAR_COLUMN, "a"),
                "(\"c_varchar\" = ? AND " + LENGTH_CHECK + ")", 2);
        assertPredicate(QUERY_BUILDER, SESSION, VARCHAR_COLUMN, in(VARCHAR_COLUMN, "a", "b"),
                "((\"c_varchar\" = ? AND " + LENGTH_CHECK + ") OR (\"c_varchar\" = ? AND " + LENGTH_CHECK + "))", 4);
        assertPredicate(QUERY_BUILDER, SESSION, VARCHAR_COLUMN, notEqualTo(VARCHAR_COLUMN, "a"),
                "NOT ((\"c_varchar\" = ? AND " + LENGTH_CHECK + "))", 2);
        assertPredicate(QUERY_BUILDER, SESSION, VARCHAR_COLUMN, Domain.create(ValueSet.of(VARCHAR_COLUMN.getColumnType(), utf8Slice("a")), true),
                "((\"c_varchar\" = ? AND " + LENGTH_CHECK + ") OR \"c_varchar\" IS NULL)", 2);
        assertPredicate(QUERY_BUILDER, SESSION, NVARCHAR_COLUMN, equalTo(NVARCHAR_COLUMN, "a"),
                "(\"c_nvarchar\" = ? AND CHARACTER_LENGTH(\"c_nvarchar\") = CHARACTER_LENGTH(CAST(? AS NVARCHAR(16369))))", 2);
    }

    @Test
    public void testNullChecksAreRenderedPlainly()
    {
        assertPredicate(QUERY_BUILDER, SESSION, VARCHAR_COLUMN, Domain.notNull(VARCHAR_COLUMN.getColumnType()), "\"c_varchar\" IS NOT NULL", 0);
        assertPredicate(QUERY_BUILDER, SESSION, VARCHAR_COLUMN, Domain.onlyNull(VARCHAR_COLUMN.getColumnType()), "\"c_varchar\" IS NULL", 0);
    }

    @Test
    public void testDefaultRenderingWhereNoLengthCheckApplies()
    {
        // PAD SPACE semantics accept the padded equality
        assertPredicate(QUERY_BUILDER, PAD_SPACE_SESSION, VARCHAR_COLUMN, equalTo(VARCHAR_COLUMN, "a"), "\"c_varchar\" = ?", 1);
        assertPredicate(QUERY_BUILDER, PAD_SPACE_SESSION, VARCHAR_COLUMN, in(VARCHAR_COLUMN, "a", "b"), "\"c_varchar\" IN (?,?)", 2);
        // CHAR columns are exact as they are
        assertPredicate(QUERY_BUILDER, SESSION, CHAR_COLUMN, equalTo(CHAR_COLUMN, "a"), "\"c_char\" = ?", 1);
        // a parameter cannot be cast to the type of a VARGRAPHIC column; the client pushes such predicates only as a pre-filter
        assertPredicate(QUERY_BUILDER, SESSION, VARGRAPHIC_COLUMN, equalTo(VARGRAPHIC_COLUMN, "a"), "\"c_vargraphic\" = ?", 1);
        // under a case-insensitive sort sequence the client pushes equality only as a pre-filter
        assertPredicate(CASE_INSENSITIVE_QUERY_BUILDER, SESSION, VARCHAR_COLUMN, equalTo(VARCHAR_COLUMN, "a"), "\"c_varchar\" = ?", 1);
        // ranges are never pushed down by the client, so nothing is sent for them
        assertPredicate(QUERY_BUILDER, SESSION, VARCHAR_COLUMN, Domain.create(ValueSet.ofRanges(Range.greaterThan(VARCHAR_COLUMN.getColumnType(), utf8Slice("a"))), false), "1=1", 0);
    }

    @Test
    public void testTypeNamesAreMatchedCaseInsensitively()
    {
        JdbcColumnHandle column = column("c", createVarcharType(10), characterTypeHandle(Types.VARCHAR, "varchar"));
        assertPredicate(QUERY_BUILDER, SESSION, column, equalTo(column, "a"),
                "(\"c\" = ? AND CHARACTER_LENGTH(\"c\") = CHARACTER_LENGTH(CAST(? AS VARCHAR(32739))))", 2);
    }

    private static void assertPredicate(JTOpenQueryBuilder queryBuilder, ConnectorSession session, JdbcColumnHandle column, Domain domain, String expectedPredicate, int expectedParameters)
    {
        JTOpenClient client = queryBuilder == CASE_INSENSITIVE_QUERY_BUILDER ? CASE_INSENSITIVE_CLIENT : CLIENT;
        PreparedQuery query = queryBuilder.prepareSelectQuery(
                client,
                session,
                null,
                RELATION,
                Optional.empty(),
                List.of(column),
                Map.of(),
                TupleDomain.withColumnDomains(ImmutableMap.<ColumnHandle, Domain>of(column, domain)),
                Optional.empty());
        assertThat(query.query()).isEqualTo("SELECT " + client.quoted(column.getColumnName()) + " FROM \"test_table\" WHERE " + expectedPredicate);
        assertThat(query.parameters()).hasSize(expectedParameters);
    }

    private static Domain equalTo(JdbcColumnHandle column, String value)
    {
        return Domain.singleValue(column.getColumnType(), utf8Slice(value));
    }

    private static Domain notEqualTo(JdbcColumnHandle column, String value)
    {
        Type type = column.getColumnType();
        return Domain.create(ValueSet.ofRanges(Range.lessThan(type, utf8Slice(value)), Range.greaterThan(type, utf8Slice(value))), false);
    }

    private static Domain in(JdbcColumnHandle column, String... values)
    {
        return Domain.multipleValues(column.getColumnType(), List.of(values).stream().map(value -> (Object) utf8Slice(value)).toList());
    }
}
