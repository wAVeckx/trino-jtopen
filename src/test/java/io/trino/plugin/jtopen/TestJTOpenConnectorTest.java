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

import io.trino.Session;
import io.trino.plugin.jdbc.BaseJdbcConnectorTest;
import io.trino.sql.planner.plan.FilterNode;
import io.trino.testing.QueryRunner;
import io.trino.testing.TestingConnectorBehavior;
import io.trino.testing.sql.SqlExecutor;
import io.trino.testing.sql.TestTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static io.trino.plugin.jtopen.JTOpenQueryRunner.CATALOG;
import static io.trino.plugin.jtopen.JTOpenSessionProperties.VARCHAR_COMPARISON_SEMANTICS;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the Trino JDBC connector test suite against the IBM i system selected by the environment variables of
 * {@link TestingJTOpenServer}; skipped when none is configured.
 */
public class TestJTOpenConnectorTest
        extends BaseJdbcConnectorTest
{
    private TestingJTOpenServer server;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        server = closeAfterClass(TestingJTOpenServer.fromEnvironment());
        return JTOpenQueryRunner.builder(server)
                .addConnectorProperties(Map.of("jtopen.stored-procedure-table-function-enabled", "true"))
                .setInitialTables(REQUIRED_TPCH_TABLES)
                .build();
    }

    @Override
    protected SqlExecutor onRemoteDatabase()
    {
        return server;
    }

    @Override
    protected boolean hasBehavior(TestingConnectorBehavior connectorBehavior)
    {
        return switch (connectorBehavior) {
            case SUPPORTS_JOIN_PUSHDOWN -> true;
            case SUPPORTS_ADD_COLUMN_WITH_COMMENT,
                 SUPPORTS_ADD_COLUMN_WITH_POSITION,
                 SUPPORTS_AGGREGATION_PUSHDOWN_COUNT_DISTINCT,
                 SUPPORTS_ARRAY,
                 SUPPORTS_CREATE_TABLE_WITH_COLUMN_COMMENT,
                 SUPPORTS_JOIN_PUSHDOWN_WITH_DISTINCT_FROM,
                 SUPPORTS_MAP_TYPE,
                 SUPPORTS_NEGATIVE_DATE,
                 // Range comparisons on character columns follow the sort sequence of the connection, not code point order
                 SUPPORTS_PREDICATE_PUSHDOWN_WITH_VARCHAR_INEQUALITY,
                 SUPPORTS_RENAME_COLUMN,
                 SUPPORTS_RENAME_SCHEMA,
                 SUPPORTS_RENAME_TABLE_ACROSS_SCHEMAS,
                 SUPPORTS_ROW_TYPE -> false;
            default -> super.hasBehavior(connectorBehavior);
        };
    }

    @Test
    public void testVarcharComparisonSemantics()
    {
        try (TestTable table = new TestTable(onRemoteDatabase(), "test_pad_space", "(v VARCHAR(10))", List.of("'a'", "'a '"))) {
            // Trino semantics: trailing blanks are significant; the equality is pushed down with a character length check
            assertThat(query("SELECT count(*) FROM " + table.getName() + " WHERE v = 'a'"))
                    .matches("VALUES BIGINT '1'")
                    .isFullyPushedDown();
            assertThat(query("SELECT count(*) FROM " + table.getName() + " WHERE v <> 'a'"))
                    .matches("VALUES BIGINT '1'")
                    .isFullyPushedDown();
            assertThat(query("SELECT count(*) FROM " + table.getName() + " WHERE v IN ('a', 'b')"))
                    .matches("VALUES BIGINT '1'")
                    .isFullyPushedDown();

            // Db2 for i semantics: the comparison is pushed down as it is and matches both rows
            Session padSpace = Session.builder(getSession())
                    .setCatalogSessionProperty(CATALOG, VARCHAR_COMPARISON_SEMANTICS, "PAD_SPACE")
                    .build();
            assertThat(query(padSpace, "SELECT count(*) FROM " + table.getName() + " WHERE v = 'a'"))
                    .matches("VALUES BIGINT '2'")
                    .isFullyPushedDown();
        }
    }

    @Test
    public void testCharEqualityIsFullyPushedDown()
    {
        try (TestTable table = new TestTable(onRemoteDatabase(), "test_char_equality", "(c CHAR(10))", List.of("'a'", "'b'"))) {
            assertThat(query("SELECT count(*) FROM " + table.getName() + " WHERE c = 'a'"))
                    .matches("VALUES BIGINT '1'")
                    .isFullyPushedDown();
            assertThat(query("SELECT count(*) FROM " + table.getName() + " WHERE c <> 'a'"))
                    .matches("VALUES BIGINT '1'")
                    .isFullyPushedDown();
            assertThat(query("SELECT count(*) FROM " + table.getName() + " WHERE c > 'a'"))
                    .matches("VALUES BIGINT '1'")
                    .isNotFullyPushedDown(FilterNode.class);
        }
    }

    @Override
    protected TestTable createTableWithDefaultColumns()
    {
        return new TestTable(
                onRemoteDatabase(),
                "table",
                "(col_required BIGINT NOT NULL," +
                        "col_nullable BIGINT," +
                        "col_default BIGINT DEFAULT 43," +
                        "col_nonnull_default BIGINT NOT NULL DEFAULT 42," +
                        "col_required2 BIGINT NOT NULL)");
    }

    @Override
    protected TestTable createTableWithUnsupportedColumn()
    {
        // DECFLOAT is not mapped
        return new TestTable(
                onRemoteDatabase(),
                "test_unsupported_column_present",
                "(one BIGINT, two DECFLOAT, three VARCHAR(10))");
    }

    @Override
    protected TestTable createTestTableForWrites(String namePrefix, String tableDefinition, String primaryKey)
    {
        TestTable testTable = super.createTestTableForWrites(namePrefix, tableDefinition, primaryKey);
        onRemoteDatabase().execute(format("ALTER TABLE %s ADD PRIMARY KEY (%s)", testTable.getName(), primaryKey));
        return testTable;
    }

    @Override
    protected TestTable createTestTableForWrites(String namePrefix, String tableDefinition, List<String> rowsToInsert, String primaryKey)
    {
        TestTable testTable = super.createTestTableForWrites(namePrefix, tableDefinition, rowsToInsert, primaryKey);
        onRemoteDatabase().execute(format("ALTER TABLE %s ADD PRIMARY KEY (%s)", testTable.getName(), primaryKey));
        return testTable;
    }

    @Override
    protected Optional<DataMappingTestSetup> filterDataMappingSmokeTestData(DataMappingTestSetup dataMappingTestSetup)
    {
        String typeName = dataMappingTestSetup.getTrinoTypeName();
        if (typeName.equals("timestamp(3) with time zone") || typeName.equals("timestamp(6) with time zone")) {
            return Optional.of(dataMappingTestSetup.asUnsupported());
        }
        if (typeName.startsWith("time")) {
            // Db2 for i TIME has no fractional seconds, so time(p) values are stored as time(0)
            return Optional.empty();
        }
        return Optional.of(dataMappingTestSetup);
    }

    @Override
    protected String errorMessageForInsertIntoNotNullColumn(String columnName)
    {
        return "(?s).*(SQL0407|[Nn]ull value not allowed).*";
    }

    @Override
    protected String errorMessageForCreateTableAsSelectNegativeDate(String date)
    {
        return "(?s).*(SQL0181|not valid).*";
    }

    @Override
    protected String errorMessageForInsertNegativeDate(String date)
    {
        return "(?s).*(SQL0181|not valid).*";
    }

    @Override
    protected OptionalInt maxSchemaNameLength()
    {
        return OptionalInt.of(128);
    }

    @Override
    protected void verifySchemaNameLengthFailurePermissible(Throwable e)
    {
        assertThat(e).hasMessageMatching("Schema name must be shorter than or equal to '128' characters but got '129'");
    }

    @Override
    protected OptionalInt maxTableNameLength()
    {
        return OptionalInt.of(128);
    }

    @Override
    protected void verifyTableNameLengthFailurePermissible(Throwable e)
    {
        assertThat(e).hasMessageMatching("Table name must be shorter than or equal to '128' characters but got '129'");
    }

    @Override
    protected OptionalInt maxColumnNameLength()
    {
        return OptionalInt.of(128);
    }

    @Override
    protected void verifyColumnNameLengthFailurePermissible(Throwable e)
    {
        assertThat(e).hasMessageMatching("Column name must be shorter than or equal to '128' characters but got '129': '.*'");
    }
}
