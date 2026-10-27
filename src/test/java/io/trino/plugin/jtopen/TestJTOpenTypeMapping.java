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

import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.QueryRunner;
import io.trino.testing.datatype.CreateAndInsertDataSetup;
import io.trino.testing.datatype.CreateAsSelectDataSetup;
import io.trino.testing.datatype.DataSetup;
import io.trino.testing.datatype.SqlDataTypeTest;
import io.trino.testing.sql.TrinoSqlExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.CharType.createCharType;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TimeType.createTimeType;
import static io.trino.spi.type.TimestampType.createTimestampType;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.junit.jupiter.api.parallel.ExecutionMode.CONCURRENT;

/**
 * Round trips values through Db2 for i and Trino in both directions, against the IBM i system selected by the
 * environment variables of {@link TestingJTOpenServer}; skipped when none is configured.
 */
@TestInstance(PER_CLASS)
@Execution(CONCURRENT)
public class TestJTOpenTypeMapping
        extends AbstractTestQueryFramework
{
    private TestingJTOpenServer server;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        server = closeAfterClass(TestingJTOpenServer.fromEnvironment());
        return JTOpenQueryRunner.builder(server).build();
    }

    @Test
    public void testIntegerTypes()
    {
        SqlDataTypeTest.create()
                .addRoundTrip("smallint", "-32768", SMALLINT, "SMALLINT '-32768'")
                .addRoundTrip("smallint", "32767", SMALLINT, "SMALLINT '32767'")
                .addRoundTrip("integer", "-2147483648", INTEGER, "-2147483648")
                .addRoundTrip("integer", "2147483647", INTEGER, "2147483647")
                .addRoundTrip("bigint", "-9223372036854775808", BIGINT, "-9223372036854775808")
                .addRoundTrip("bigint", "9223372036854775807", BIGINT, "9223372036854775807")
                .addRoundTrip("integer", "NULL", INTEGER, "CAST(NULL AS integer)")
                .execute(getQueryRunner(), jtopenCreateAndInsert("test_integer"))
                .execute(getQueryRunner(), trinoCreateAsSelect("test_integer"));
    }

    @Test
    public void testFloatingPointTypes()
    {
        SqlDataTypeTest.create()
                .addRoundTrip("real", "CAST(3.14 AS real)", REAL, "REAL '3.14'")
                .addRoundTrip("real", "CAST(-1.5E-10 AS real)", REAL, "REAL '-1.5E-10'")
                .addRoundTrip("double", "CAST(1.0E100 AS double)", DOUBLE, "DOUBLE '1.0E100'")
                .addRoundTrip("double", "CAST(123456789.123456789 AS double)", DOUBLE, "DOUBLE '123456789.123456789'")
                .addRoundTrip("double", "NULL", DOUBLE, "CAST(NULL AS double)")
                .execute(getQueryRunner(), jtopenCreateAndInsert("test_floating"))
                .execute(getQueryRunner(), trinoCreateAsSelect("test_floating"));
    }

    @Test
    public void testDecimal()
    {
        SqlDataTypeTest.create()
                .addRoundTrip("decimal(3, 0)", "CAST('193' AS decimal(3, 0))", createDecimalType(3, 0), "CAST('193' AS decimal(3, 0))")
                .addRoundTrip("decimal(3, 0)", "CAST('-193' AS decimal(3, 0))", createDecimalType(3, 0), "CAST('-193' AS decimal(3, 0))")
                .addRoundTrip("decimal(3, 1)", "CAST('10.1' AS decimal(3, 1))", createDecimalType(3, 1), "CAST('10.1' AS decimal(3, 1))")
                .addRoundTrip("decimal(4, 2)", "CAST('2' AS decimal(4, 2))", createDecimalType(4, 2), "CAST('2' AS decimal(4, 2))")
                .addRoundTrip("decimal(24, 2)", "CAST('123456789012345678901.23' AS decimal(24, 2))", createDecimalType(24, 2), "CAST('123456789012345678901.23' AS decimal(24, 2))")
                .addRoundTrip("decimal(24, 4)", "CAST('12345678901234567890.3141' AS decimal(24, 4))", createDecimalType(24, 4), "CAST('12345678901234567890.3141' AS decimal(24, 4))")
                .addRoundTrip("decimal(38, 0)", "CAST('27182818284590452353602874713526624977' AS decimal(38, 0))", createDecimalType(38, 0), "CAST('27182818284590452353602874713526624977' AS decimal(38, 0))")
                .addRoundTrip("decimal(38, 0)", "NULL", createDecimalType(38, 0), "CAST(NULL AS decimal(38, 0))")
                .execute(getQueryRunner(), jtopenCreateAndInsert("test_decimal"))
                .execute(getQueryRunner(), trinoCreateAsSelect("test_decimal"));
    }

    @Test
    public void testCharacterTypes()
    {
        SqlDataTypeTest.create()
                .addRoundTrip("char(10)", "'text_a'", createCharType(10), "CAST('text_a' AS char(10))")
                .addRoundTrip("char(10)", "''", createCharType(10), "CAST('' AS char(10))")
                .addRoundTrip("varchar(10)", "'text_a'", createVarcharType(10), "CAST('text_a' AS varchar(10))")
                .addRoundTrip("varchar(10)", "'text_a '", createVarcharType(10), "CAST('text_a ' AS varchar(10))")
                .addRoundTrip("varchar(10)", "NULL", createVarcharType(10), "CAST(NULL AS varchar(10))")
                .execute(getQueryRunner(), jtopenCreateAndInsert("test_character"))
                .execute(getQueryRunner(), trinoCreateAsSelect("test_character"));
    }

    @Test
    public void testVarbinary()
    {
        SqlDataTypeTest.create()
                .addRoundTrip("blob(1K)", "BLOB(X'0102030405')", VARBINARY, "X'0102030405'")
                .addRoundTrip("blob(1K)", "BLOB(X'')", VARBINARY, "X''")
                .addRoundTrip("blob(1K)", "NULL", VARBINARY, "CAST(NULL AS varbinary)")
                .execute(getQueryRunner(), jtopenCreateAndInsert("test_blob"));

        SqlDataTypeTest.create()
                .addRoundTrip("varbinary", "X'0102030405'", VARBINARY, "X'0102030405'")
                .addRoundTrip("varbinary", "X''", VARBINARY, "X''")
                .addRoundTrip("varbinary", "NULL", VARBINARY, "CAST(NULL AS varbinary)")
                .execute(getQueryRunner(), trinoCreateAsSelect("test_varbinary"));
    }

    @Test
    public void testDate()
    {
        SqlDataTypeTest.create()
                .addRoundTrip("date", "DATE '0001-01-01'", DATE, "DATE '0001-01-01'")
                .addRoundTrip("date", "DATE '1900-01-01'", DATE, "DATE '1900-01-01'")
                .addRoundTrip("date", "DATE '1970-01-01'", DATE, "DATE '1970-01-01'")
                .addRoundTrip("date", "DATE '2024-02-29'", DATE, "DATE '2024-02-29'")
                .addRoundTrip("date", "DATE '9999-12-31'", DATE, "DATE '9999-12-31'")
                .addRoundTrip("date", "NULL", DATE, "CAST(NULL AS date)")
                .execute(getQueryRunner(), jtopenCreateAndInsert("test_date"))
                .execute(getQueryRunner(), trinoCreateAsSelect("test_date"));
    }

    @Test
    public void testTime()
    {
        SqlDataTypeTest.create()
                .addRoundTrip("time", "TIME '00:00:00'", createTimeType(0), "TIME '00:00:00'")
                .addRoundTrip("time", "TIME '12:34:56'", createTimeType(0), "TIME '12:34:56'")
                .addRoundTrip("time", "TIME '23:59:59'", createTimeType(0), "TIME '23:59:59'")
                .addRoundTrip("time", "NULL", createTimeType(0), "CAST(NULL AS time(0))")
                .execute(getQueryRunner(), jtopenCreateAndInsert("test_time"))
                .execute(getQueryRunner(), trinoCreateAsSelect("test_time"));
    }

    @Test
    public void testTimestamp()
    {
        SqlDataTypeTest.create()
                .addRoundTrip("timestamp(0)", "TIMESTAMP '2020-01-01 12:34:56'", createTimestampType(0), "TIMESTAMP '2020-01-01 12:34:56'")
                .addRoundTrip("timestamp(3)", "TIMESTAMP '2020-01-01 12:34:56.123'", createTimestampType(3), "TIMESTAMP '2020-01-01 12:34:56.123'")
                .addRoundTrip("timestamp(6)", "TIMESTAMP '2020-01-01 12:34:56.123456'", createTimestampType(6), "TIMESTAMP '2020-01-01 12:34:56.123456'")
                .addRoundTrip("timestamp(9)", "TIMESTAMP '2020-01-01 12:34:56.123456789'", createTimestampType(9), "TIMESTAMP '2020-01-01 12:34:56.123456789'")
                .addRoundTrip("timestamp(12)", "TIMESTAMP '2020-01-01 12:34:56.123456789012'", createTimestampType(12), "TIMESTAMP '2020-01-01 12:34:56.123456789012'")
                .addRoundTrip("timestamp(6)", "TIMESTAMP '0001-01-01 00:00:00.000000'", createTimestampType(6), "TIMESTAMP '0001-01-01 00:00:00.000000'")
                .addRoundTrip("timestamp(6)", "TIMESTAMP '9999-12-31 23:59:59.999999'", createTimestampType(6), "TIMESTAMP '9999-12-31 23:59:59.999999'")
                .addRoundTrip("timestamp(6)", "NULL", createTimestampType(6), "CAST(NULL AS timestamp(6))")
                .execute(getQueryRunner(), jtopenCreateAndInsert("test_timestamp"))
                .execute(getQueryRunner(), trinoCreateAsSelect("test_timestamp"));
    }

    private DataSetup trinoCreateAsSelect(String tableNamePrefix)
    {
        return new CreateAsSelectDataSetup(new TrinoSqlExecutor(getQueryRunner()), tableNamePrefix);
    }

    private DataSetup jtopenCreateAndInsert(String tableNamePrefix)
    {
        return new CreateAndInsertDataSetup(server, tableNamePrefix);
    }
}
