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
import com.google.common.collect.ImmutableMap;
import io.trino.plugin.base.mapping.DefaultIdentifierMapping;
import io.trino.plugin.jdbc.BaseJdbcConfig;
import io.trino.plugin.jdbc.ColumnMapping;
import io.trino.plugin.jdbc.DefaultQueryBuilder;
import io.trino.plugin.jdbc.JdbcColumnHandle;
import io.trino.plugin.jdbc.JdbcExpression;
import io.trino.plugin.jdbc.JdbcMetadataConfig;
import io.trino.plugin.jdbc.JdbcMetadataSessionProperties;
import io.trino.plugin.jdbc.JdbcTableHandle;
import io.trino.plugin.jdbc.JdbcStatisticsConfig;
import io.trino.plugin.jdbc.JdbcTypeHandle;
import io.trino.plugin.jdbc.PredicatePushdownController.DomainPushdownResult;
import io.trino.plugin.jdbc.RemoteTableName;
import io.trino.plugin.jdbc.TypeHandlingJdbcConfig;
import io.trino.plugin.jdbc.TypeHandlingJdbcSessionProperties;
import io.trino.plugin.jdbc.expression.ParameterizedExpression;
import io.trino.plugin.jdbc.logging.RemoteQueryModifier;
import io.trino.plugin.jtopen.JTOpenConfig.VarcharComparisonSemantics;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.AggregateFunction;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.expression.Call;
import io.trino.spi.expression.ConnectorExpression;
import io.trino.spi.expression.Constant;
import io.trino.spi.expression.FunctionName;
import io.trino.spi.expression.Variable;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.Range;
import io.trino.spi.predicate.ValueSet;
import io.trino.spi.session.PropertyMetadata;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.Type;
import io.trino.testing.TestingConnectorSession;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.plugin.jtopen.JTOpenConfig.VarcharComparisonSemantics.EXACT;
import static io.trino.plugin.jtopen.JTOpenConfig.VarcharComparisonSemantics.PAD_SPACE;
import static io.trino.plugin.jtopen.JTOpenSessionProperties.VARCHAR_COMPARISON_SEMANTICS;
import static io.trino.spi.expression.StandardFunctions.ARRAY_CONSTRUCTOR_FUNCTION_NAME;
import static io.trino.spi.expression.StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME;
import static io.trino.spi.expression.StandardFunctions.IN_PREDICATE_FUNCTION_NAME;
import static io.trino.spi.expression.StandardFunctions.LESS_THAN_OPERATOR_FUNCTION_NAME;
import static io.trino.spi.expression.StandardFunctions.LIKE_FUNCTION_NAME;
import static io.trino.spi.expression.StandardFunctions.NOT_EQUAL_OPERATOR_FUNCTION_NAME;
import static io.trino.spi.expression.StandardFunctions.NULLIF_FUNCTION_NAME;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.CharType.createCharType;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.TimeType.createTimeType;
import static io.trino.spi.type.TimestampType.createTimestampType;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the pushdown rules for character columns. Db2 for i compares CHAR and VARCHAR with PAD SPACE semantics and
 * with the sort sequence of the connection, so pushdown follows the SQL Server model of trinodb/trino#29952,
 * trinodb/trino#30480 and trinodb/trino#31060, with the column collation replaced by the connector configuration, and
 * makes VARCHAR equality exact with a character length check, the way the PostgreSQL connector uses COLLATE "C".
 */
public class TestJTOpenClient
{
    // Driver default: *HEX sort sequence, case-sensitive
    static final JTOpenClient CLIENT = createClient(new JTOpenConfig());
    // sort=language with the default shared weight: case-insensitive
    static final JTOpenClient CASE_INSENSITIVE_CLIENT = createClient(new JTOpenConfig().setCaseInsensitiveSortSequence(true));

    static final ConnectorSession SESSION = session(EXACT);
    static final ConnectorSession PAD_SPACE_SESSION = session(PAD_SPACE);

    static final JdbcTypeHandle VARCHAR_TYPE_HANDLE = characterTypeHandle(Types.VARCHAR, "VARCHAR");
    static final JdbcTypeHandle CHAR_TYPE_HANDLE = characterTypeHandle(Types.CHAR, "CHAR");
    static final JdbcTypeHandle NVARCHAR_TYPE_HANDLE = characterTypeHandle(Types.NVARCHAR, "NVARCHAR");
    static final JdbcTypeHandle NCHAR_TYPE_HANDLE = characterTypeHandle(Types.NCHAR, "NCHAR");
    // VARGRAPHIC with a CCSID other than 1200 is reported as VARCHAR with this type name; a parameter cannot be cast to it safely
    static final JdbcTypeHandle VARGRAPHIC_TYPE_HANDLE = characterTypeHandle(Types.VARCHAR, "VARGRAPHIC");
    private static final List<JdbcTypeHandle> CHAR_TYPE_HANDLES = List.of(CHAR_TYPE_HANDLE, NCHAR_TYPE_HANDLE);
    private static final List<JdbcTypeHandle> VARCHAR_TYPE_HANDLES = List.of(VARCHAR_TYPE_HANDLE, NVARCHAR_TYPE_HANDLE);
    private static final JdbcTypeHandle BIGINT_TYPE_HANDLE = typeHandle(Types.BIGINT, "BIGINT");

    private static final Variable VARCHAR_VARIABLE = new Variable("v_varchar", createVarcharType(10));
    private static final Variable OTHER_VARCHAR_VARIABLE = new Variable("v_varchar2", createVarcharType(10));
    private static final Variable NVARCHAR_VARIABLE = new Variable("v_nvarchar", createVarcharType(10));
    private static final Variable VARGRAPHIC_VARIABLE = new Variable("v_vargraphic", createVarcharType(10));
    private static final Variable CHAR_VARIABLE = new Variable("v_char", createCharType(10));
    private static final Variable OTHER_CHAR_VARIABLE = new Variable("v_char2", createCharType(10));
    private static final Variable BIGINT_VARIABLE = new Variable("v_bigint", BIGINT);
    private static final Variable OTHER_BIGINT_VARIABLE = new Variable("v_bigint2", BIGINT);
    private static final Constant VARCHAR_CONSTANT = new Constant(utf8Slice("a"), createVarcharType(10));
    private static final Map<String, ColumnHandle> ASSIGNMENTS = ImmutableMap.<String, ColumnHandle>builder()
            .put("v_varchar", column("c_varchar", createVarcharType(10), VARCHAR_TYPE_HANDLE))
            .put("v_varchar2", column("c_varchar2", createVarcharType(10), VARCHAR_TYPE_HANDLE))
            .put("v_nvarchar", column("c_nvarchar", createVarcharType(10), NVARCHAR_TYPE_HANDLE))
            .put("v_vargraphic", column("c_vargraphic", createVarcharType(10), VARGRAPHIC_TYPE_HANDLE))
            .put("v_char", column("c_char", createCharType(10), CHAR_TYPE_HANDLE))
            .put("v_char2", column("c_char2", createCharType(10), CHAR_TYPE_HANDLE))
            .put("v_bigint", column("c_bigint", BIGINT, BIGINT_TYPE_HANDLE))
            .put("v_bigint2", column("c_bigint2", BIGINT, BIGINT_TYPE_HANDLE))
            .buildOrThrow();

    private static final String VARCHAR_LENGTH_CHECK = "CHARACTER_LENGTH(\"c_varchar\") = CHARACTER_LENGTH(CAST(? AS VARCHAR(32739)))";

    @Test
    public void testNullChecksAreFullyPushedDownOnCharacterColumns()
    {
        for (JTOpenClient client : List.of(CLIENT, CASE_INSENSITIVE_CLIENT)) {
            for (ConnectorSession session : List.of(SESSION, PAD_SPACE_SESSION)) {
                for (JdbcTypeHandle typeHandle : List.of(VARCHAR_TYPE_HANDLE, CHAR_TYPE_HANDLE, NVARCHAR_TYPE_HANDLE, NCHAR_TYPE_HANDLE, VARGRAPHIC_TYPE_HANDLE)) {
                    Type type = columnType(client, typeHandle);
                    // IS NOT NULL must be pushed down, or a LIMIT above it cannot be pushed down either (trinodb/trino#31060)
                    assertFullyPushedDown(client, session, typeHandle, Domain.notNull(type));
                    assertFullyPushedDown(client, session, typeHandle, Domain.onlyNull(type));
                }
            }
        }
    }

    @Test
    public void testCharEqualityIsFullyPushedDownUnderCaseSensitiveSortSequence()
    {
        // Trino ignores trailing blanks in CHAR values, like Db2 for i, so the pushed down predicate matches Trino's
        for (ConnectorSession session : List.of(SESSION, PAD_SPACE_SESSION)) {
            for (JdbcTypeHandle typeHandle : CHAR_TYPE_HANDLES) {
                Type type = columnType(CLIENT, typeHandle);
                assertFullyPushedDown(CLIENT, session, typeHandle, equalTo(type, "a"));
                assertFullyPushedDown(CLIENT, session, typeHandle, in(type, "a", "b"));
                assertFullyPushedDown(CLIENT, session, typeHandle, notEqualTo(type, "a"));
                assertFullyPushedDown(CLIENT, session, typeHandle, Domain.create(ValueSet.of(type, utf8Slice("a")), true));
            }
        }
    }

    @Test
    public void testVarcharEqualityIsFullyPushedDownUnderCaseSensitiveSortSequence()
    {
        // Exact under EXACT semantics thanks to the length check of JTOpenQueryBuilder, as it is under PAD SPACE semantics
        for (ConnectorSession session : List.of(SESSION, PAD_SPACE_SESSION)) {
            for (JdbcTypeHandle typeHandle : VARCHAR_TYPE_HANDLES) {
                Type type = columnType(CLIENT, typeHandle);
                assertFullyPushedDown(CLIENT, session, typeHandle, equalTo(type, "a"));
                assertFullyPushedDown(CLIENT, session, typeHandle, in(type, "a", "b"));
                assertFullyPushedDown(CLIENT, session, typeHandle, notEqualTo(type, "a"));
                assertFullyPushedDown(CLIENT, session, typeHandle, Domain.create(ValueSet.of(type, utf8Slice("a")), true));
            }
        }
    }

    @Test
    public void testVarcharEqualityIsPushedDownOnlyAsPrefilterWhenTheLengthCheckIsNotPossible()
    {
        // A parameter cannot be cast to the type of a VARGRAPHIC column, so only PAD SPACE semantics allow a full pushdown
        Type type = columnType(CLIENT, VARGRAPHIC_TYPE_HANDLE);
        assertPushedDownAsPrefilter(CLIENT, SESSION, VARGRAPHIC_TYPE_HANDLE, equalTo(type, "a"));
        assertPushedDownAsPrefilter(CLIENT, SESSION, VARGRAPHIC_TYPE_HANDLE, in(type, "a", "b"));
        assertNotPushedDown(CLIENT, SESSION, VARGRAPHIC_TYPE_HANDLE, notEqualTo(type, "a"));
        assertFullyPushedDown(CLIENT, PAD_SPACE_SESSION, VARGRAPHIC_TYPE_HANDLE, equalTo(type, "a"));
        assertFullyPushedDown(CLIENT, PAD_SPACE_SESSION, VARGRAPHIC_TYPE_HANDLE, notEqualTo(type, "a"));
    }

    @Test
    public void testEqualityIsPushedDownOnlyAsPrefilterUnderCaseInsensitiveSortSequence()
    {
        // Db2 for i matches 'A' for = 'a', which neither PAD SPACE semantics nor a length check can account for
        for (ConnectorSession session : List.of(SESSION, PAD_SPACE_SESSION)) {
            for (JdbcTypeHandle typeHandle : List.of(VARCHAR_TYPE_HANDLE, CHAR_TYPE_HANDLE)) {
                Type type = columnType(CASE_INSENSITIVE_CLIENT, typeHandle);
                assertPushedDownAsPrefilter(CASE_INSENSITIVE_CLIENT, session, typeHandle, equalTo(type, "a"));
                assertPushedDownAsPrefilter(CASE_INSENSITIVE_CLIENT, session, typeHandle, in(type, "a", "b"));
                assertNotPushedDown(CASE_INSENSITIVE_CLIENT, session, typeHandle, notEqualTo(type, "a"));
            }
        }
    }

    @Test
    public void testRangesAreNeverPushedDownOnCharacterColumns()
    {
        // ranges follow the sort sequence of the connection, such as EBCDIC order, not Trino's code point order
        for (JTOpenClient client : List.of(CLIENT, CASE_INSENSITIVE_CLIENT)) {
            for (ConnectorSession session : List.of(SESSION, PAD_SPACE_SESSION)) {
                for (JdbcTypeHandle typeHandle : List.of(VARCHAR_TYPE_HANDLE, CHAR_TYPE_HANDLE)) {
                    Type type = columnType(client, typeHandle);
                    assertNotPushedDown(client, session, typeHandle, Domain.create(ValueSet.ofRanges(Range.greaterThan(type, utf8Slice("a"))), false));
                    assertNotPushedDown(client, session, typeHandle, Domain.create(ValueSet.ofRanges(Range.range(type, utf8Slice("a"), true, utf8Slice("m"), true)), false));
                }
            }
        }
    }

    @Test
    public void testCharComparisonExpressionPushdown()
    {
        // char = char is exact under a case-sensitive sort sequence
        assertThat(convert(CLIENT, SESSION, comparison(EQUAL_OPERATOR_FUNCTION_NAME, CHAR_VARIABLE, OTHER_CHAR_VARIABLE)))
                .map(ParameterizedExpression::expression)
                .hasValue("(\"c_char\") = (\"c_char2\")");
        assertThat(convert(CLIENT, SESSION, comparison(NOT_EQUAL_OPERATOR_FUNCTION_NAME, CHAR_VARIABLE, new Constant(utf8Slice("a"), createCharType(10)))))
                .map(ParameterizedExpression::expression)
                .hasValue("(\"c_char\") <> (?)");
        // a case-insensitive sort sequence never matches Trino
        assertThat(convert(CASE_INSENSITIVE_CLIENT, PAD_SPACE_SESSION, comparison(EQUAL_OPERATOR_FUNCTION_NAME, CHAR_VARIABLE, OTHER_CHAR_VARIABLE))).isEmpty();
        // ranges on character values are never pushed down
        assertThat(convert(CLIENT, PAD_SPACE_SESSION, comparison(LESS_THAN_OPERATOR_FUNCTION_NAME, CHAR_VARIABLE, OTHER_CHAR_VARIABLE))).isEmpty();
        // numeric comparisons always are
        assertThat(convert(CASE_INSENSITIVE_CLIENT, SESSION, comparison(EQUAL_OPERATOR_FUNCTION_NAME, BIGINT_VARIABLE, OTHER_BIGINT_VARIABLE))).isPresent();
    }

    @Test
    public void testVarcharComparisonExpressionPushdown()
    {
        // EXACT semantics: the padded equality is made exact with a character length check, counted by Db2 for i on both sides
        Optional<ParameterizedExpression> equality = convert(CLIENT, SESSION, comparison(EQUAL_OPERATOR_FUNCTION_NAME, VARCHAR_VARIABLE, VARCHAR_CONSTANT));
        assertThat(equality).map(ParameterizedExpression::expression)
                .hasValue("((\"c_varchar\") = (?) AND " + VARCHAR_LENGTH_CHECK + ")");
        assertThat(equality.orElseThrow().parameters()).hasSize(2);

        assertThat(convert(CLIENT, SESSION, comparison(NOT_EQUAL_OPERATOR_FUNCTION_NAME, VARCHAR_VARIABLE, VARCHAR_CONSTANT)))
                .map(ParameterizedExpression::expression)
                .hasValue("NOT ((\"c_varchar\") = (?) AND " + VARCHAR_LENGTH_CHECK + ")");

        assertThat(convert(CLIENT, SESSION, comparison(EQUAL_OPERATOR_FUNCTION_NAME, VARCHAR_CONSTANT, VARCHAR_VARIABLE)))
                .map(ParameterizedExpression::expression)
                .hasValue("((?) = (\"c_varchar\") AND CHARACTER_LENGTH(CAST(? AS VARCHAR(32739))) = CHARACTER_LENGTH(\"c_varchar\"))");

        assertThat(convert(CLIENT, SESSION, comparison(EQUAL_OPERATOR_FUNCTION_NAME, VARCHAR_VARIABLE, OTHER_VARCHAR_VARIABLE)))
                .map(ParameterizedExpression::expression)
                .hasValue("((\"c_varchar\") = (\"c_varchar2\") AND CHARACTER_LENGTH(\"c_varchar\") = CHARACTER_LENGTH(\"c_varchar2\"))");

        // an NVARCHAR column is UTF-16, so the parameter is counted in the same code units
        assertThat(convert(CLIENT, SESSION, comparison(EQUAL_OPERATOR_FUNCTION_NAME, NVARCHAR_VARIABLE, VARCHAR_CONSTANT)))
                .map(ParameterizedExpression::expression)
                .hasValue("((\"c_nvarchar\") = (?) AND CHARACTER_LENGTH(\"c_nvarchar\") = CHARACTER_LENGTH(CAST(? AS NVARCHAR(16369))))");

        // a parameter cannot be cast to the type of a VARGRAPHIC column
        assertThat(convert(CLIENT, SESSION, comparison(EQUAL_OPERATOR_FUNCTION_NAME, VARGRAPHIC_VARIABLE, VARCHAR_CONSTANT))).isEmpty();
        // two constants have no column to take the cast type from
        assertThat(convert(CLIENT, SESSION, comparison(EQUAL_OPERATOR_FUNCTION_NAME, VARCHAR_CONSTANT, VARCHAR_CONSTANT))).isEmpty();

        // PAD SPACE semantics: pushed down as it is
        assertThat(convert(CLIENT, PAD_SPACE_SESSION, comparison(EQUAL_OPERATOR_FUNCTION_NAME, VARCHAR_VARIABLE, OTHER_VARCHAR_VARIABLE)))
                .map(ParameterizedExpression::expression)
                .hasValue("(\"c_varchar\") = (\"c_varchar2\")");
        assertThat(convert(CLIENT, PAD_SPACE_SESSION, comparison(EQUAL_OPERATOR_FUNCTION_NAME, VARGRAPHIC_VARIABLE, VARCHAR_CONSTANT)))
                .map(ParameterizedExpression::expression)
                .hasValue("(\"c_vargraphic\") = (?)");

        // a case-insensitive sort sequence never matches Trino
        assertThat(convert(CASE_INSENSITIVE_CLIENT, PAD_SPACE_SESSION, comparison(EQUAL_OPERATOR_FUNCTION_NAME, VARCHAR_VARIABLE, OTHER_VARCHAR_VARIABLE))).isEmpty();
    }

    @Test
    public void testInExpressionPushdown()
    {
        Call charIn = in(CHAR_VARIABLE, new Constant(utf8Slice("a"), createCharType(10)), new Constant(utf8Slice("b"), createCharType(10)));
        Call varcharIn = in(VARCHAR_VARIABLE, new Constant(utf8Slice("a"), createVarcharType(10)), new Constant(utf8Slice("b"), createVarcharType(10)));
        Call varcharInColumns = in(VARCHAR_VARIABLE, OTHER_VARCHAR_VARIABLE, new Constant(utf8Slice("b"), createVarcharType(10)));
        Call bigintIn = in(BIGINT_VARIABLE, new Constant(1L, BIGINT), new Constant(2L, BIGINT));

        assertThat(convert(CLIENT, SESSION, charIn)).map(ParameterizedExpression::expression).hasValue("(\"c_char\") IN (?, ?)");

        Optional<ParameterizedExpression> exactIn = convert(CLIENT, SESSION, varcharIn);
        assertThat(exactIn).map(ParameterizedExpression::expression)
                .hasValue("(((\"c_varchar\") = (?) AND " + VARCHAR_LENGTH_CHECK + ") OR ((\"c_varchar\") = (?) AND " + VARCHAR_LENGTH_CHECK + "))");
        assertThat(exactIn.orElseThrow().parameters()).hasSize(4);
        assertThat(convert(CLIENT, SESSION, varcharInColumns)).map(ParameterizedExpression::expression)
                .hasValue("(((\"c_varchar\") = (\"c_varchar2\") AND CHARACTER_LENGTH(\"c_varchar\") = CHARACTER_LENGTH(\"c_varchar2\")) OR ((\"c_varchar\") = (?) AND " + VARCHAR_LENGTH_CHECK + "))");

        assertThat(convert(CLIENT, PAD_SPACE_SESSION, varcharIn)).map(ParameterizedExpression::expression).hasValue("(\"c_varchar\") IN (?, ?)");
        assertThat(convert(CASE_INSENSITIVE_CLIENT, PAD_SPACE_SESSION, charIn)).isEmpty();
        assertThat(convert(CASE_INSENSITIVE_CLIENT, PAD_SPACE_SESSION, varcharIn)).isEmpty();
        assertThat(convert(CASE_INSENSITIVE_CLIENT, SESSION, bigintIn)).map(ParameterizedExpression::expression).hasValue("(\"c_bigint\") IN (?, ?)");
    }

    @Test
    public void testNullIfExpressionPushdown()
    {
        Call charNullIf = new Call(createCharType(10), NULLIF_FUNCTION_NAME, List.of(CHAR_VARIABLE, OTHER_CHAR_VARIABLE));
        Call varcharNullIf = new Call(createVarcharType(10), NULLIF_FUNCTION_NAME, List.of(VARCHAR_VARIABLE, OTHER_VARCHAR_VARIABLE));
        Call varcharNullIfConstant = new Call(createVarcharType(10), NULLIF_FUNCTION_NAME, List.of(VARCHAR_VARIABLE, VARCHAR_CONSTANT));
        Call bigintNullIf = new Call(BIGINT, NULLIF_FUNCTION_NAME, List.of(BIGINT_VARIABLE, OTHER_BIGINT_VARIABLE));

        assertThat(convert(CLIENT, SESSION, charNullIf)).map(ParameterizedExpression::expression).hasValue("NULLIF((\"c_char\"), (\"c_char2\"))");
        assertThat(convert(CLIENT, SESSION, varcharNullIf)).map(ParameterizedExpression::expression)
                .hasValue("CASE WHEN (\"c_varchar\") = (\"c_varchar2\") AND CHARACTER_LENGTH(\"c_varchar\") = CHARACTER_LENGTH(\"c_varchar2\") THEN NULL ELSE (\"c_varchar\") END");
        Optional<ParameterizedExpression> exactNullIf = convert(CLIENT, SESSION, varcharNullIfConstant);
        assertThat(exactNullIf).map(ParameterizedExpression::expression)
                .hasValue("CASE WHEN (\"c_varchar\") = (?) AND " + VARCHAR_LENGTH_CHECK + " THEN NULL ELSE (\"c_varchar\") END");
        assertThat(exactNullIf.orElseThrow().parameters()).hasSize(2);
        assertThat(convert(CLIENT, PAD_SPACE_SESSION, varcharNullIf)).map(ParameterizedExpression::expression).hasValue("NULLIF((\"c_varchar\"), (\"c_varchar2\"))");
        assertThat(convert(CASE_INSENSITIVE_CLIENT, PAD_SPACE_SESSION, charNullIf)).isEmpty();
        assertThat(convert(CASE_INSENSITIVE_CLIENT, SESSION, bigintNullIf)).map(ParameterizedExpression::expression).hasValue("NULLIF((\"c_bigint\"), (\"c_bigint2\"))");
    }

    @Test
    public void testLikeIsPushedDownOnlyUnderCaseSensitiveSortSequence()
    {
        Call like = new Call(BOOLEAN, LIKE_FUNCTION_NAME, List.of(VARCHAR_VARIABLE, new Constant(utf8Slice("a%"), VARCHAR)));
        assertThat(convert(CLIENT, SESSION, like)).isPresent();
        assertThat(convert(CASE_INSENSITIVE_CLIENT, SESSION, like)).isEmpty();
    }

    @Test
    public void testCharacterMinMaxAndCountDistinctAreNotPushedDown()
    {
        // Db2 for i orders by the sort sequence (EBCDIC under *HEX) and compares with PAD SPACE semantics
        for (String function : List.of("min", "max")) {
            assertThat(aggregate(new AggregateFunction(function, createVarcharType(10), List.of(VARCHAR_VARIABLE), List.of(), false, Optional.empty()))).isEmpty();
            assertThat(aggregate(new AggregateFunction(function, createCharType(10), List.of(CHAR_VARIABLE), List.of(), false, Optional.empty()))).isEmpty();
            assertThat(aggregate(new AggregateFunction(function, BIGINT, List.of(BIGINT_VARIABLE), List.of(), false, Optional.empty()))).isPresent();
        }
        assertThat(aggregate(new AggregateFunction("count", BIGINT, List.of(VARCHAR_VARIABLE), List.of(), true, Optional.empty()))).isEmpty();
        assertThat(aggregate(new AggregateFunction("count", BIGINT, List.of(CHAR_VARIABLE), List.of(), true, Optional.empty()))).isEmpty();
        assertThat(aggregate(new AggregateFunction("count", BIGINT, List.of(BIGINT_VARIABLE), List.of(), true, Optional.empty()))).isPresent();
    }

    @Test
    public void testRenameTableAcrossSchemasIsRejected()
    {
        // The remote schema name differs in case from the Trino name, as it does on Db2 for i
        JdbcTableHandle nation = new JdbcTableHandle(new SchemaTableName("trinotest", "nation"), new RemoteTableName(Optional.empty(), Optional.of("TRINOTEST"), "NATION"), Optional.empty());
        assertThatThrownBy(() -> CLIENT.renameTable(SESSION, nation, new SchemaTableName("other_schema", "renamed")))
                .isInstanceOf(TrinoException.class)
                .hasMessage("This connector does not support renaming tables across schemas");
        // A rename within the schema passes the check and reaches the connection factory of the test client
        assertThatThrownBy(() -> CLIENT.renameTable(SESSION, nation, new SchemaTableName("trinotest", "renamed")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void testReadMappings()
    {
        assertThat(columnType(CLIENT, CHAR_TYPE_HANDLE)).isEqualTo(createCharType(10));
        assertThat(columnType(CLIENT, NCHAR_TYPE_HANDLE)).isEqualTo(createCharType(10));
        assertThat(columnType(CLIENT, VARCHAR_TYPE_HANDLE)).isEqualTo(createVarcharType(10));
        assertThat(columnType(CLIENT, NVARCHAR_TYPE_HANDLE)).isEqualTo(createVarcharType(10));
        assertThat(columnType(CLIENT, typeHandle(Types.CLOB, "CLOB"))).isEqualTo(createUnboundedVarcharType());
        assertThat(columnType(CLIENT, typeHandle(Types.SQLXML, "XML"))).isEqualTo(createUnboundedVarcharType());
        assertThat(columnType(CLIENT, typeHandle(Types.BLOB, "BLOB"))).isEqualTo(VARBINARY);
        assertThat(columnType(CLIENT, typeHandle(Types.BINARY, "CHAR () FOR BIT DATA"))).isEqualTo(VARBINARY);
        assertThat(columnType(CLIENT, typeHandle(Types.TIME, "TIME"))).isEqualTo(createTimeType(0));
        assertThat(columnType(CLIENT, new JdbcTypeHandle(Types.TIMESTAMP, Optional.of("TIMESTAMP"), Optional.of(32), Optional.of(12), Optional.empty(), Optional.empty())))
                .isEqualTo(createTimestampType(12));
        // DECFLOAT is reported as OTHER and is not supported
        assertThat(CLIENT.toColumnMapping(SESSION, null, typeHandle(Types.OTHER, "DECFLOAT"))).isEmpty();
    }

    @Test
    public void testWriteMappings()
    {
        assertThat(CLIENT.toWriteMapping(SESSION, TINYINT).getDataType()).isEqualTo("SMALLINT");
        assertThat(CLIENT.toWriteMapping(SESSION, DOUBLE).getDataType()).isEqualTo("DOUBLE PRECISION");
        assertThat(CLIENT.toWriteMapping(SESSION, createDecimalType(10, 2)).getDataType()).isEqualTo("DECIMAL(10, 2)");
        assertThat(CLIENT.toWriteMapping(SESSION, createCharType(10)).getDataType()).isEqualTo("CHAR(10)");
        assertThat(CLIENT.toWriteMapping(SESSION, createVarcharType(10)).getDataType()).isEqualTo("VARCHAR(10)");
        assertThat(CLIENT.toWriteMapping(SESSION, createUnboundedVarcharType()).getDataType()).isEqualTo("VARCHAR(32739)");
        assertThat(CLIENT.toWriteMapping(SESSION, createVarcharType(40000)).getDataType()).isEqualTo("CLOB(40000)");
        assertThat(CLIENT.toWriteMapping(SESSION, VARBINARY).getDataType()).isEqualTo("BLOB(2G)");
        assertThat(CLIENT.toWriteMapping(SESSION, createTimeType(0)).getDataType()).isEqualTo("TIME");
        assertThat(CLIENT.toWriteMapping(SESSION, createTimestampType(6)).getDataType()).isEqualTo("TIMESTAMP(6)");
        assertThat(CLIENT.toWriteMapping(SESSION, createTimestampType(12)).getDataType()).isEqualTo("TIMESTAMP(12)");
    }

    @Test
    public void testSupportedType()
    {
        assertThat(CLIENT.getSupportedType(SESSION, createTimeType(6))).hasValue(createTimeType(0));
        assertThat(CLIENT.getSupportedType(SESSION, createTimeType(0))).isEmpty();
        assertThat(CLIENT.getSupportedType(SESSION, createTimestampType(12))).isEmpty();
    }

    static JTOpenClient createClient(JTOpenConfig config)
    {
        return new JTOpenClient(
                new BaseJdbcConfig(),
                new JdbcStatisticsConfig(),
                config,
                _ -> {
                    throw new UnsupportedOperationException();
                },
                new DefaultQueryBuilder(RemoteQueryModifier.NONE),
                new DefaultIdentifierMapping(),
                RemoteQueryModifier.NONE);
    }

    static ConnectorSession session(VarcharComparisonSemantics semantics)
    {
        return TestingConnectorSession.builder()
                .setPropertyMetadata(ImmutableList.<PropertyMetadata<?>>builder()
                        .addAll(new JdbcMetadataSessionProperties(new JdbcMetadataConfig(), Optional.empty()).getSessionProperties())
                        .addAll(new TypeHandlingJdbcSessionProperties(new TypeHandlingJdbcConfig()).getSessionProperties())
                        .addAll(new JTOpenSessionProperties(new JTOpenConfig()).getSessionProperties())
                        .build())
                // session property values are given in their SQL form, as the engine would pass them
                .setPropertyValues(ImmutableMap.of(VARCHAR_COMPARISON_SEMANTICS, semantics.name()))
                .build();
    }

    static JdbcTypeHandle characterTypeHandle(int jdbcType, String typeName)
    {
        return new JdbcTypeHandle(jdbcType, Optional.of(typeName), Optional.of(10), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static JdbcTypeHandle typeHandle(int jdbcType, String typeName)
    {
        return new JdbcTypeHandle(jdbcType, Optional.of(typeName), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    static JdbcColumnHandle column(String name, Type type, JdbcTypeHandle typeHandle)
    {
        return JdbcColumnHandle.builder()
                .setColumnName(name)
                .setColumnType(type)
                .setJdbcTypeHandle(typeHandle)
                .build();
    }

    private static Domain equalTo(Type type, String value)
    {
        return Domain.singleValue(type, utf8Slice(value));
    }

    private static Domain notEqualTo(Type type, String value)
    {
        return Domain.create(ValueSet.ofRanges(Range.lessThan(type, utf8Slice(value)), Range.greaterThan(type, utf8Slice(value))), false);
    }

    private static Domain in(Type type, String... values)
    {
        return Domain.multipleValues(type, List.of(values).stream().map(value -> (Object) utf8Slice(value)).toList());
    }

    private static Call comparison(FunctionName operator, ConnectorExpression left, ConnectorExpression right)
    {
        return new Call(BOOLEAN, operator, List.of(left, right));
    }

    private static Call in(ConnectorExpression value, ConnectorExpression... values)
    {
        return new Call(BOOLEAN, IN_PREDICATE_FUNCTION_NAME, List.of(
                value,
                new Call(new ArrayType(value.getType()), ARRAY_CONSTRUCTOR_FUNCTION_NAME, List.of(values))));
    }

    private static Optional<JdbcExpression> aggregate(AggregateFunction aggregate)
    {
        return CLIENT.implementAggregation(SESSION, aggregate, ASSIGNMENTS);
    }

    private static Optional<ParameterizedExpression> convert(JTOpenClient client, ConnectorSession session, ConnectorExpression expression)
    {
        return client.convertPredicate(session, expression, ASSIGNMENTS);
    }

    private static Type columnType(JTOpenClient client, JdbcTypeHandle typeHandle)
    {
        return columnMapping(client, typeHandle).getType();
    }

    private static ColumnMapping columnMapping(JTOpenClient client, JdbcTypeHandle typeHandle)
    {
        return client.toColumnMapping(SESSION, null, typeHandle).orElseThrow();
    }

    private static DomainPushdownResult applyPushdown(JTOpenClient client, ConnectorSession session, JdbcTypeHandle typeHandle, Domain domain)
    {
        return columnMapping(client, typeHandle).getPredicatePushdownController().apply(session, domain);
    }

    private static void assertFullyPushedDown(JTOpenClient client, ConnectorSession session, JdbcTypeHandle typeHandle, Domain domain)
    {
        DomainPushdownResult result = applyPushdown(client, session, typeHandle, domain);
        assertThat(result.getPushedDown()).isEqualTo(domain);
        assertThat(result.getRemainingFilter()).isEqualTo(Domain.all(domain.getType()));
    }

    private static void assertPushedDownAsPrefilter(JTOpenClient client, ConnectorSession session, JdbcTypeHandle typeHandle, Domain domain)
    {
        DomainPushdownResult result = applyPushdown(client, session, typeHandle, domain);
        assertThat(result.getPushedDown()).isEqualTo(domain);
        assertThat(result.getRemainingFilter()).isEqualTo(domain);
    }

    private static void assertNotPushedDown(JTOpenClient client, ConnectorSession session, JdbcTypeHandle typeHandle, Domain domain)
    {
        DomainPushdownResult result = applyPushdown(client, session, typeHandle, domain);
        assertThat(result.getPushedDown()).isEqualTo(Domain.all(domain.getType()));
        assertThat(result.getRemainingFilter()).isEqualTo(domain);
    }
}
