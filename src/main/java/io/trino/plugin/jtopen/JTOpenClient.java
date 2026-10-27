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
import com.google.inject.Inject;
import io.airlift.log.Logger;
import io.trino.plugin.base.aggregation.AggregateFunctionRewriter;
import io.trino.plugin.base.aggregation.AggregateFunctionRule;
import io.trino.plugin.base.expression.ConnectorExpressionRewriter;
import io.trino.plugin.base.mapping.IdentifierMapping;
import io.trino.plugin.jdbc.BaseJdbcClient;
import io.trino.plugin.jdbc.BaseJdbcConfig;
import io.trino.plugin.jdbc.ColumnMapping;
import io.trino.plugin.jdbc.ConnectionFactory;
import io.trino.plugin.jdbc.JdbcColumnHandle;
import io.trino.plugin.jdbc.JdbcExpression;
import io.trino.plugin.jdbc.JdbcMetadata;
import io.trino.plugin.jdbc.JdbcProcedureHandle;
import io.trino.plugin.jdbc.JdbcProcedureHandle.ProcedureQuery;
import io.trino.plugin.jdbc.JdbcSortItem;
import io.trino.plugin.jdbc.JdbcStatisticsConfig;
import io.trino.plugin.jdbc.JdbcTableHandle;
import io.trino.plugin.jdbc.JdbcTypeHandle;
import io.trino.plugin.jdbc.LongReadFunction;
import io.trino.plugin.jdbc.ObjectReadFunction;
import io.trino.plugin.jdbc.ObjectWriteFunction;
import io.trino.plugin.jdbc.PredicatePushdownController;
import io.trino.plugin.jdbc.PredicatePushdownController.DomainPushdownResult;
import io.trino.plugin.jdbc.PreparedQuery;
import io.trino.plugin.jdbc.QueryBuilder;
import io.trino.plugin.jdbc.RemoteTableName;
import io.trino.plugin.jdbc.WriteMapping;
import io.trino.plugin.jdbc.aggregation.ImplementAvgDecimal;
import io.trino.plugin.jdbc.aggregation.ImplementAvgFloatingPoint;
import io.trino.plugin.jdbc.aggregation.ImplementCorr;
import io.trino.plugin.jdbc.aggregation.ImplementCount;
import io.trino.plugin.jdbc.aggregation.ImplementCountAll;
import io.trino.plugin.jdbc.aggregation.ImplementCountDistinct;
import io.trino.plugin.jdbc.aggregation.ImplementCovariancePop;
import io.trino.plugin.jdbc.aggregation.ImplementCovarianceSamp;
import io.trino.plugin.jdbc.aggregation.ImplementMinMax;
import io.trino.plugin.jdbc.aggregation.ImplementRegrIntercept;
import io.trino.plugin.jdbc.aggregation.ImplementRegrSlope;
import io.trino.plugin.jdbc.aggregation.ImplementStddevPop;
import io.trino.plugin.jdbc.aggregation.ImplementStddevSamp;
import io.trino.plugin.jdbc.aggregation.ImplementSum;
import io.trino.plugin.jdbc.aggregation.ImplementVariancePop;
import io.trino.plugin.jdbc.aggregation.ImplementVarianceSamp;
import io.trino.plugin.jdbc.expression.JdbcConnectorExpressionRewriterBuilder;
import io.trino.plugin.jdbc.expression.ParameterizedExpression;
import io.trino.plugin.jdbc.logging.RemoteQueryModifier;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.AggregateFunction;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.JoinStatistics;
import io.trino.spi.connector.JoinType;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.expression.ConnectorExpression;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.ValueSet;
import io.trino.spi.statistics.ColumnStatistics;
import io.trino.spi.statistics.Estimate;
import io.trino.spi.statistics.TableStatistics;
import io.trino.spi.type.CharType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Decimals;
import io.trino.spi.type.LongTimestamp;
import io.trino.spi.type.TimeType;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.plugin.jdbc.DecimalSessionSessionProperties.getDecimalRounding;
import static io.trino.plugin.jdbc.DecimalSessionSessionProperties.getDecimalRoundingMode;
import static io.trino.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static io.trino.plugin.jdbc.JdbcJoinPushdownUtil.implementJoinCostAware;
import static io.trino.plugin.jdbc.JdbcMetadataSessionProperties.getDomainCompactionThreshold;
import static io.trino.plugin.jdbc.PredicatePushdownController.DISABLE_PUSHDOWN;
import static io.trino.plugin.jdbc.PredicatePushdownController.FULL_PUSHDOWN;
import static io.trino.plugin.jdbc.StandardColumnMappings.bigintColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.bigintWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.booleanColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.booleanWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.charReadFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.charWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.dateColumnMappingUsingLocalDate;
import static io.trino.plugin.jdbc.StandardColumnMappings.dateWriteFunctionUsingLocalDate;
import static io.trino.plugin.jdbc.StandardColumnMappings.decimalColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.doubleColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.doubleWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.fromLongTrinoTimestamp;
import static io.trino.plugin.jdbc.StandardColumnMappings.integerColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.integerWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.longDecimalWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.numberColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.realColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.realWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.shortDecimalWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.smallintColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.smallintWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.timeColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.timeWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.timestampWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.tinyintColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.tinyintWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.toLongTrinoTimestamp;
import static io.trino.plugin.jdbc.StandardColumnMappings.toTrinoTimestamp;
import static io.trino.plugin.jdbc.StandardColumnMappings.varbinaryColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.varbinaryWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.varcharReadFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.varcharWriteFunction;
import static io.trino.plugin.jdbc.TypeHandlingJdbcSessionProperties.getUnsupportedTypeHandling;
import static io.trino.plugin.jdbc.UnsupportedTypeHandling.CONVERT_TO_VARCHAR;
import static io.trino.plugin.jtopen.JTOpenConfig.VarcharComparisonSemantics.PAD_SPACE;
import static io.trino.plugin.jtopen.JTOpenSessionProperties.getVarcharComparisonSemantics;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.CharType.createCharType;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TimeType.TIME_SECONDS;
import static io.trino.spi.type.TimestampType.createTimestampType;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static java.lang.Math.max;
import static java.lang.String.format;
import static java.lang.String.join;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public class JTOpenClient
        extends BaseJdbcClient
{
    private static final Logger log = Logger.get(JTOpenClient.class);

    private final int varcharMaxLength;

    // DB2 for i TIMESTAMP supports up to 12 digits of fractional seconds (picoseconds)
    private static final int MAX_LOCAL_DATE_TIME_PRECISION = 12;
    public static final JdbcTypeHandle BIGINT_TYPE = new JdbcTypeHandle(Types.BIGINT, Optional.of("bigint"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    private static final String VARCHAR_FORMAT = "VARCHAR(%d)";
    // Db2 for i allows up to 32766 bytes for a CHAR column
    private static final int CHAR_MAX_LENGTH = 32766;
    private final boolean statisticsEnabled;
    private final boolean caseInsensitiveSortSequence;
    private final ConnectorExpressionRewriter<ParameterizedExpression> connectorExpressionRewriter;
    private final AggregateFunctionRewriter<JdbcExpression, ?> aggregateFunctionRewriter;

    // Db2 for i compares CHAR and VARCHAR with PAD SPACE semantics (the shorter operand is padded with blanks) and with
    // the sort sequence of the connection, which under the default *HEX sequence orders by EBCDIC code point and under
    // a shared-weight sequence ignores case. Trino compares varchar with NO PAD, is always case-sensitive and orders by
    // Unicode code point. Range predicates on character columns therefore never match Trino and always stay in the
    // engine. Equality predicates are pushed down by one of the two controllers below, depending on the column type,
    // the configured sort sequence and the varchar comparison semantics of the session.

    // Pushes =, IN, IS NULL and IS NOT NULL down only as a superset pre-filter that the engine re-checks, so that the
    // query returns Trino's results even where Db2 for i matches more rows. <> and NOT IN cannot be expressed as a
    // superset and stay in the engine.
    private static final PredicatePushdownController CHARACTER_PREFILTER_PUSHDOWN = (session, domain) -> {
        if (domain.isOnlyNull() || domain.getValues().isAll()) {
            return FULL_PUSHDOWN.apply(session, domain);
        }

        if (!domain.getValues().isDiscreteSet()) {
            return DISABLE_PUSHDOWN.apply(session, domain);
        }

        Domain simplifiedDomain = domain.simplify(getDomainCompactionThreshold(session));
        if (!simplifiedDomain.getValues().isDiscreteSet()) {
            // Domain#simplify can turn a discrete set into a range predicate
            return DISABLE_PUSHDOWN.apply(session, domain);
        }
        return new DomainPushdownResult(simplifiedDomain, domain);
    };

    // Pushes =, <>, IN, NOT IN, IS NULL and IS NOT NULL down fully. Used under a case-sensitive sort sequence for CHAR
    // columns, where Db2 for i equality matches Trino's because Trino ignores trailing blanks in CHAR values as well, and
    // for VARCHAR columns, where JTOpenQueryBuilder makes the equality exact with a character length check unless the
    // session accepts PAD SPACE comparison semantics.
    private static final PredicatePushdownController CHARACTER_EQUALITY_PUSHDOWN = (session, domain) -> {
        if (domain.isOnlyNull() || domain.getValues().isAll()) {
            return FULL_PUSHDOWN.apply(session, domain);
        }

        Domain simplifiedDomain = domain.simplify(getDomainCompactionThreshold(session));
        ValueSet values = simplifiedDomain.getValues();
        if (values.isDiscreteSet() || values.complement().isDiscreteSet()) {
            return FULL_PUSHDOWN.apply(session, simplifiedDomain);
        }
        // Domain#simplify can turn a discrete set into a range predicate
        return DISABLE_PUSHDOWN.apply(session, domain);
    };

    @Inject
    public JTOpenClient(
            BaseJdbcConfig config,
            JdbcStatisticsConfig statisticsConfig,
            JTOpenConfig jtopenConfig,
            ConnectionFactory connectionFactory,
            QueryBuilder queryBuilder,
            IdentifierMapping identifierMapping,
            RemoteQueryModifier queryModifier)
    {
        super("\"", connectionFactory, queryBuilder, config.getJdbcTypesMappedToVarchar(), identifierMapping, queryModifier, true);
        this.varcharMaxLength = jtopenConfig.getVarcharMaxLength();
        this.statisticsEnabled = statisticsConfig.isEnabled();
        this.caseInsensitiveSortSequence = jtopenConfig.isCaseInsensitiveSortSequence();
        RemoteComparison remoteComparison = new RemoteComparison(caseInsensitiveSortSequence);

        JdbcConnectorExpressionRewriterBuilder rewriterBuilder = JdbcConnectorExpressionRewriterBuilder.newBuilder()
                .addStandardRules(this::quoted)
                .add(new RewriteJTOpenCharConstant())
                .add(new RewriteJTOpenComparison(remoteComparison))
                .add(new RewriteJTOpenIn(remoteComparison))
                .add(new RewriteJTOpenNullIf(remoteComparison))
                .withTypeClass("integer_type", ImmutableSet.of("tinyint", "smallint", "integer", "bigint"))
                .withTypeClass("numeric_type", ImmutableSet.of("tinyint", "smallint", "integer", "bigint", "decimal", "real", "double"))
                .map("$equal(left: numeric_type, right: numeric_type)").to("left = right")
                .map("$not_equal(left: numeric_type, right: numeric_type)").to("left <> right")
                .map("$less_than(left: numeric_type, right: numeric_type)").to("left < right")
                .map("$less_than_or_equal(left: numeric_type, right: numeric_type)").to("left <= right")
                .map("$greater_than(left: numeric_type, right: numeric_type)").to("left > right")
                .map("$greater_than_or_equal(left: numeric_type, right: numeric_type)").to("left >= right")
                .map("$add(left: integer_type, right: integer_type)").to("left + right")
                .map("$subtract(left: integer_type, right: integer_type)").to("left - right")
                .map("$multiply(left: integer_type, right: integer_type)").to("left * right")
                .map("$divide(left: integer_type, right: integer_type)").to("left / right")
                .map("$modulo(left: integer_type, right: integer_type)").to("left % right")
                .map("$negate(value: integer_type)").to("-value")
                .map("$not($is_null(value))").to("value IS NOT NULL")
                .map("$not(value: boolean)").to("NOT value")
                .map("$is_null(value)").to("value IS NULL");
        if (!caseInsensitiveSortSequence) {
            // LIKE does not pad, and under a *HEX or unique-weight sort sequence it is case-sensitive, as in Trino. Under
            // a shared-weight sort sequence it ignores case, so it stays in the engine.
            rewriterBuilder
                    .map("$like(value: varchar, pattern: varchar): boolean").to("value LIKE pattern")
                    .map("$like(value: varchar, pattern: varchar, escape: varchar(1)): boolean").to("value LIKE pattern ESCAPE escape")
                    .map("$like(value: char, pattern: varchar): boolean").to("value LIKE pattern")
                    .map("$like(value: char, pattern: varchar, escape: varchar(1)): boolean").to("value LIKE pattern ESCAPE escape");
        }
        this.connectorExpressionRewriter = rewriterBuilder.build();

        JdbcTypeHandle bigintTypeHandle = new JdbcTypeHandle(Types.BIGINT, Optional.of("bigint"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        this.aggregateFunctionRewriter = new AggregateFunctionRewriter<>(
                this.connectorExpressionRewriter,
                ImmutableSet.<AggregateFunctionRule<JdbcExpression, ParameterizedExpression>>builder()
                        .add(new ImplementCountAll(bigintTypeHandle))
                        // min, max and count(DISTINCT) of character values follow the sort sequence of the connection, not Trino's
                        // code point order and NO PAD comparison, so they are not pushed down
                        .add(new ImplementMinMax(true))
                        .add(new ImplementCount(bigintTypeHandle))
                        .add(new ImplementCountDistinct(bigintTypeHandle, true))
                        .add(new ImplementSum(JTOpenClient::toTypeHandle))
                        .add(new ImplementAvgFloatingPoint())
                        .add(new ImplementAvgDecimal())
                        .add(new ImplementAvgBigint())
                        .add(new ImplementStddevSamp())
                        .add(new ImplementStddevPop())
                        .add(new ImplementVarianceSamp())
                        .add(new ImplementVariancePop())
                        .add(new ImplementCovarianceSamp())
                        .add(new ImplementCovariancePop())
                        .add(new ImplementCorr())
                        .add(new ImplementRegrIntercept())
                        .add(new ImplementRegrSlope())
                        .build());
    }

    @Override
    public OptionalInt getMaxColumnNameLength(ConnectorSession session)
    {
        return getMaxColumnNameLengthFromDatabaseMetaData(session);
    }

    @Override
    public Optional<ColumnMapping> toColumnMapping(ConnectorSession session, Connection connection, JdbcTypeHandle typeHandle)
    {
        Optional<ColumnMapping> mapping = getForcedMappingToVarchar(typeHandle);
        if (mapping.isPresent()) {
            return mapping;
        }
        switch (typeHandle.jdbcType()) {
            case Types.BIT, Types.BOOLEAN -> {
                return Optional.of(booleanColumnMapping());
            }
            case Types.TINYINT -> {
                return Optional.of(tinyintColumnMapping());
            }
            case Types.SMALLINT -> {
                return Optional.of(smallintColumnMapping());
            }
            case Types.INTEGER -> {
                return Optional.of(integerColumnMapping());
            }
            case Types.BIGINT -> {
                return Optional.of(bigintColumnMapping());
            }
            case Types.REAL -> {
                return Optional.of(realColumnMapping());
            }
            case Types.FLOAT, Types.DOUBLE -> {
                return Optional.of(doubleColumnMapping());
            }
            case Types.NUMERIC, Types.DECIMAL -> {
                int decimalDigits = typeHandle.requiredDecimalDigits();
                int scale = max(decimalDigits, 0);
                int precision = max(typeHandle.requiredColumnSize() + max(-decimalDigits, 0), scale); // Map decimal(p, -s) (negative scale) to decimal(p+s, 0).
                if (precision <= Decimals.MAX_PRECISION) {
                    return Optional.of(decimalColumnMapping(createDecimalType(precision, scale)));
                }
                switch (getDecimalRounding(session)) {
                    case MAP_TO_NUMBER -> {
                        return Optional.of(numberColumnMapping());
                    }
                    case STRICT -> {}
                    case ALLOW_OVERFLOW -> {
                        return Optional.of(decimalColumnMapping(createDecimalType(Decimals.MAX_PRECISION, scale), getDecimalRoundingMode(session)));
                    }
                }
            }
            // GRAPHIC and VARGRAPHIC columns are reported as CHAR and VARCHAR, or as NCHAR and NVARCHAR when their CCSID is 1200
            case Types.CHAR, Types.NCHAR -> {
                return Optional.of(charColumnMapping(typeHandle));
            }
            case Types.VARCHAR, Types.NVARCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR -> {
                return Optional.of(varcharColumnMapping(typeHandle));
            }
            case Types.CLOB, Types.NCLOB, Types.SQLXML -> {
                return Optional.of(ColumnMapping.sliceMapping(
                        createUnboundedVarcharType(),
                        (resultSet, columnIndex) -> utf8Slice(resultSet.getString(columnIndex)),
                        varcharWriteFunction(),
                        DISABLE_PUSHDOWN));
            }
            // CHAR and VARCHAR FOR BIT DATA, and columns with CCSID 65535, are reported as BINARY and VARBINARY
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                return Optional.of(varbinaryColumnMapping());
            }
            case Types.DATE -> {
                return Optional.of(dateColumnMappingUsingLocalDate());
            }
            case Types.TIME -> {
                // Db2 for i TIME has no fractional seconds
                return Optional.of(timeColumnMapping(TIME_SECONDS));
            }
            case Types.TIMESTAMP -> {
                int timeprecision = typeHandle.requiredDecimalDigits();
                if (timeprecision > MAX_LOCAL_DATE_TIME_PRECISION) {
                    timeprecision = MAX_LOCAL_DATE_TIME_PRECISION;
                }
                TimestampType timestampType = createTimestampType(timeprecision);
                return Optional.of(timestampColumnMapping(timestampType));
            }
        }

        if (getUnsupportedTypeHandling(session) == CONVERT_TO_VARCHAR) {
            return mapToUnboundedVarchar(typeHandle);
        }
        return Optional.empty();
    }

    public static ColumnMapping timestampColumnMapping(TimestampType timestampType)
    {
        if (timestampType.getPrecision() <= TimestampType.MAX_SHORT_PRECISION) {
            return ColumnMapping.longMapping(
                    timestampType,
                    timestampReadFunction(timestampType),
                    timestampWriteFunction(timestampType));
        }
        checkArgument(timestampType.getPrecision() <= MAX_LOCAL_DATE_TIME_PRECISION, "Precision is out of range: %s", timestampType.getPrecision());
        return ColumnMapping.objectMapping(
                timestampType,
                longtimestampReadFunction(timestampType),
                longTimestampWriteFunction(timestampType));
    }

    public static LongReadFunction timestampReadFunction(TimestampType timestampType)
    {
        checkArgument(timestampType.getPrecision() <= TimestampType.MAX_SHORT_PRECISION, "Precision is out of range: %s", timestampType.getPrecision());
        return (resultSet, columnIndex) -> toTrinoTimestamp(timestampType, resultSet.getTimestamp(columnIndex).toLocalDateTime());
    }

    private static ObjectReadFunction longtimestampReadFunction(TimestampType timestampType)
    {
        checkArgument(timestampType.getPrecision() <= MAX_LOCAL_DATE_TIME_PRECISION,
                "Precision is out of range: %s", timestampType.getPrecision());
        return ObjectReadFunction.of(
                LongTimestamp.class,
                (resultSet, columnIndex) -> toLongTrinoTimestamp(timestampType, resultSet.getTimestamp(columnIndex).toLocalDateTime()));
    }

    private static ObjectWriteFunction longTimestampWriteFunction(TimestampType timestampType)
    {
        checkArgument(timestampType.getPrecision() > TimestampType.MAX_SHORT_PRECISION, "Precision is out of range: %s", timestampType.getPrecision());
        return ObjectWriteFunction.of(
                LongTimestamp.class,
                (statement, index, value) -> statement.setTimestamp(index, Timestamp.valueOf(fromLongTrinoTimestamp(value, timestampType.getPrecision()))));
    }

    @Override
    public WriteMapping toWriteMapping(ConnectorSession session, Type type)
    {
        if (type == BOOLEAN) {
            // BOOLEAN columns require IBM i 7.5
            return WriteMapping.booleanMapping("BOOLEAN", booleanWriteFunction());
        }
        if (type == TINYINT) {
            // Db2 for i has no TINYINT
            return WriteMapping.longMapping("SMALLINT", tinyintWriteFunction());
        }
        if (type == SMALLINT) {
            return WriteMapping.longMapping("SMALLINT", smallintWriteFunction());
        }
        if (type == INTEGER) {
            return WriteMapping.longMapping("INTEGER", integerWriteFunction());
        }
        if (type == BIGINT) {
            return WriteMapping.longMapping("BIGINT", bigintWriteFunction());
        }
        if (type == REAL) {
            return WriteMapping.longMapping("REAL", realWriteFunction());
        }
        if (type == DOUBLE) {
            return WriteMapping.doubleMapping("DOUBLE PRECISION", doubleWriteFunction());
        }
        if (type instanceof DecimalType decimalType) {
            String dataType = format("DECIMAL(%s, %s)", decimalType.getPrecision(), decimalType.getScale());
            if (decimalType.isShort()) {
                return WriteMapping.longMapping(dataType, shortDecimalWriteFunction(decimalType));
            }
            return WriteMapping.objectMapping(dataType, longDecimalWriteFunction(decimalType));
        }
        if (type instanceof CharType charType) {
            if (charType.getLength() > CHAR_MAX_LENGTH) {
                return WriteMapping.sliceMapping(format("CLOB(%d)", charType.getLength()), charWriteFunction());
            }
            return WriteMapping.sliceMapping(format("CHAR(%d)", charType.getLength()), charWriteFunction());
        }
        if (type instanceof VarcharType varcharType) {
            if (varcharType.isUnbounded()) {
                return WriteMapping.sliceMapping(format(VARCHAR_FORMAT, varcharMaxLength), varcharWriteFunction());
            }
            int length = varcharType.getBoundedLength();
            if (length > varcharMaxLength) {
                return WriteMapping.sliceMapping(format("CLOB(%d)", length), varcharWriteFunction());
            }
            return WriteMapping.sliceMapping(format(VARCHAR_FORMAT, length), varcharWriteFunction());
        }
        if (type == VARBINARY) {
            // Trino varbinary is unbounded; VARBINARY on Db2 for i is limited to 32739 bytes
            return WriteMapping.sliceMapping("BLOB(2G)", varbinaryWriteFunction());
        }
        if (type == DATE) {
            return WriteMapping.longMapping("DATE", dateWriteFunctionUsingLocalDate());
        }
        if (type instanceof TimeType) {
            // time(p) with p > 0 is mapped to time(0) by getSupportedType, as Db2 for i TIME has no fractional seconds
            return WriteMapping.longMapping("TIME", timeWriteFunction(0));
        }
        if (type instanceof TimestampType timestampType) {
            verify(timestampType.getPrecision() <= MAX_LOCAL_DATE_TIME_PRECISION, "Unsupported precision: %s", timestampType);
            String dataType = format("TIMESTAMP(%s)", timestampType.getPrecision());
            if (timestampType.isShort()) {
                return WriteMapping.longMapping(dataType, timestampWriteFunction(timestampType));
            }
            return WriteMapping.objectMapping(dataType, longTimestampWriteFunction(timestampType));
        }
        throw new TrinoException(NOT_SUPPORTED, "Unsupported column type: " + type.getDisplayName());
    }

    @Override
    public Optional<Type> getSupportedType(ConnectorSession session, Type type)
    {
        if (type instanceof TimeType timeType && timeType.getPrecision() > 0) {
            // Db2 for i TIME has no fractional seconds
            return Optional.of(TIME_SECONDS);
        }
        return Optional.empty();
    }

    @Override
    public Optional<JdbcExpression> implementAggregation(ConnectorSession session, AggregateFunction aggregate, Map<String, ColumnHandle> assignments)
    {
        // TODO support complex ConnectorExpressions
        return aggregateFunctionRewriter.rewrite(session, aggregate, assignments);
    }

    @Override
    public boolean supportsAggregationPushdown(ConnectorSession session, JdbcTableHandle table, List<AggregateFunction> aggregates, Map<String, ColumnHandle> assignments, List<List<ColumnHandle>> groupingSets)
    {
        return preventTextualTypeAggregationPushdown(groupingSets);
    }

    private static Optional<JdbcTypeHandle> toTypeHandle(DecimalType decimalType)
    {
        return Optional.of(new JdbcTypeHandle(Types.NUMERIC, Optional.of("decimal"), Optional.of(decimalType.getPrecision()), Optional.of(decimalType.getScale()), Optional.empty(), Optional.empty()));
    }

    @Override
    public Optional<PreparedQuery> implementJoin(
            ConnectorSession session,
            JoinType joinType,
            PreparedQuery leftSource,
            Map<JdbcColumnHandle, String> leftProjections,
            PreparedQuery rightSource,
            Map<JdbcColumnHandle, String> rightProjections,
            List<ParameterizedExpression> joinConditions,
            JoinStatistics statistics)
    {
        return implementJoinCostAware(
                session,
                joinType,
                leftSource,
                rightSource,
                statistics,
                () -> super.implementJoin(session, joinType, leftSource, leftProjections, rightSource, rightProjections, joinConditions, statistics));
    }

    @Override
    public List<JdbcColumnHandle> getPrimaryKeys(ConnectorSession session, RemoteTableName remoteTableName)
    {
        List<JdbcColumnHandle> columns = getColumns(session, remoteTableName.getSchemaTableName(), remoteTableName);
        try (Connection connection = connectionFactory.openConnection(session);
                ResultSet primaryKeys = connection.getMetaData().getPrimaryKeys(
                        remoteTableName.getCatalogName().orElse(null),
                        remoteTableName.getSchemaName().orElse(null),
                        remoteTableName.getTableName())) {
            Set<String> primaryKeyNames = new HashSet<>();
            while (primaryKeys.next()) {
                primaryKeyNames.add(primaryKeys.getString("COLUMN_NAME"));
            }
            return columns.stream()
                    .filter(column -> primaryKeyNames.contains(column.getColumnName()))
                    .collect(toImmutableList());
        }
        catch (SQLException e) {
            throw new TrinoException(JDBC_ERROR, e);
        }
    }

    @Override
    public boolean supportsMerge()
    {
        return true;
    }

    @Override
    public Optional<String> getTableComment(ResultSet resultSet)
            throws SQLException
    {
        // REMARKS carries the SQL comment set with COMMENT ON, see the "remarks" connection property
        return Optional.ofNullable(emptyToNull(resultSet.getString("REMARKS")));
    }

    @Override
    protected List<String> createTableSqls(RemoteTableName remoteTableName, List<String> columns, ConnectorTableMetadata tableMetadata)
    {
        checkArgument(tableMetadata.getProperties().isEmpty(), "Unsupported table properties: %s", tableMetadata.getProperties());
        ImmutableList.Builder<String> sqls = ImmutableList.builder();
        sqls.add(format("CREATE TABLE %s (%s)", quoted(remoteTableName), join(", ", columns)));
        tableMetadata.getComment().ifPresent(comment -> sqls.add(tableCommentSql(remoteTableName, Optional.of(comment))));
        return sqls.build();
    }

    @Override
    public void setTableComment(ConnectorSession session, JdbcTableHandle handle, Optional<String> comment)
    {
        execute(session, tableCommentSql(handle.asPlainTable().getRemoteTableName(), comment));
    }

    @Override
    public void setColumnComment(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle column, Optional<String> comment)
    {
        execute(session, format(
                "COMMENT ON COLUMN %s.%s IS %s",
                quoted(handle.asPlainTable().getRemoteTableName()),
                quoted(column.getColumnName()),
                commentLiteral(comment)));
    }

    private String tableCommentSql(RemoteTableName remoteTableName, Optional<String> comment)
    {
        return format("COMMENT ON TABLE %s IS %s", quoted(remoteTableName), commentLiteral(comment));
    }

    private static String commentLiteral(Optional<String> comment)
    {
        // Db2 for i requires a string constant; an empty one removes the comment
        return varcharLiteral(comment.orElse(""));
    }

    private ColumnMapping charColumnMapping(JdbcTypeHandle typeHandle)
    {
        int charLength = typeHandle.requiredColumnSize();
        if (charLength > CharType.MAX_LENGTH) {
            return varcharColumnMapping(typeHandle);
        }
        CharType charType = createCharType(charLength);
        return ColumnMapping.sliceMapping(
                charType,
                charReadFunction(charType),
                charWriteFunction(),
                characterPushdownController(typeHandle, true));
    }

    private ColumnMapping varcharColumnMapping(JdbcTypeHandle typeHandle)
    {
        int varcharLength = typeHandle.requiredColumnSize();
        VarcharType varcharType = varcharLength <= VarcharType.MAX_LENGTH
                ? createVarcharType(varcharLength)
                : createUnboundedVarcharType();
        return ColumnMapping.sliceMapping(
                varcharType,
                varcharReadFunction(varcharType),
                varcharWriteFunction(),
                characterPushdownController(typeHandle, false));
    }

    private PredicatePushdownController characterPushdownController(JdbcTypeHandle typeHandle, boolean fixedLength)
    {
        if (caseInsensitiveSortSequence) {
            return CHARACTER_PREFILTER_PUSHDOWN;
        }
        if (fixedLength) {
            return CHARACTER_EQUALITY_PUSHDOWN;
        }
        // VARCHAR equality is exact as it is under PAD SPACE semantics, and under EXACT semantics when JTOpenQueryBuilder can
        // add the character length check, which casts the parameter to the type of the column
        boolean exactPushdownPossible = RemoteComparison.parameterCastType(typeHandle).isPresent();
        return (session, domain) -> {
            PredicatePushdownController controller = exactPushdownPossible || getVarcharComparisonSemantics(session) == PAD_SPACE
                    ? CHARACTER_EQUALITY_PUSHDOWN
                    : CHARACTER_PREFILTER_PUSHDOWN;
            return controller.apply(session, domain);
        };
    }

    @Override
    public TableStatistics getTableStatistics(ConnectorSession session, JdbcTableHandle handle)
    {
        if (!statisticsEnabled) {
            return TableStatistics.empty();
        }
        if (!handle.isNamedRelation()) {
            return TableStatistics.empty();
        }
        try {
            return readTableStatistics(session, handle);
        }
        catch (SQLException | RuntimeException e) {
            throwIfInstanceOf(e, TrinoException.class);
            throw new TrinoException(JDBC_ERROR, "Failed fetching statistics for table: " + handle, e);
        }
    }

    @Override
    public JdbcProcedureHandle getProcedureHandle(ConnectorSession session, ProcedureQuery procedureQuery)
    {
        try (Connection connection = connectionFactory.openConnection(session)) {
            try (Statement statement = connection.createStatement();
                    // Db2 for i cannot describe the result set of a procedure without running it, so the procedure runs once
                    // here to describe its result and again when the table function is read
                    ResultSet resultSet = statement.executeQuery(" %s ".formatted(procedureQuery.query()))) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                if (metadata == null) {
                    throw new TrinoException(NOT_SUPPORTED, "Procedure not supported: ResultSetMetaData not available for query: " + procedureQuery.query());
                }
                JdbcProcedureHandle procedureHandle = new JdbcProcedureHandle(procedureQuery, getColumns(session, connection, metadata));
                if (statement.getMoreResults()) {
                    throw new TrinoException(NOT_SUPPORTED, "Procedure has multiple ResultSets for query: " + procedureQuery.query());
                }
                return procedureHandle;
            }
        }
        catch (SQLException e) {
            throw new TrinoException(JDBC_ERROR, "Failed to get table handle for procedure query. " + firstNonNull(e.getMessage(), e), e);
        }
    }

    @Override
    protected void verifySchemaName(DatabaseMetaData databaseMetadata, String schemaName)
            throws SQLException
    {
        if (schemaName.length() > databaseMetadata.getMaxSchemaNameLength()) {
            throw new TrinoException(NOT_SUPPORTED, format("Schema name must be shorter than or equal to '%s' characters but got '%s'", databaseMetadata.getMaxSchemaNameLength(), schemaName.length()));
        }
    }

    @Override
    protected void verifyTableName(DatabaseMetaData databaseMetadata, String tableName)
            throws SQLException
    {
        if (tableName.length() > databaseMetadata.getMaxTableNameLength()) {
            throw new TrinoException(NOT_SUPPORTED, format("Table name must be shorter than or equal to '%s' characters but got '%s'", databaseMetadata.getMaxTableNameLength(), tableName.length()));
        }
    }

    @Override
    public Optional<ParameterizedExpression> convertPredicate(ConnectorSession session, ConnectorExpression expression, Map<String, ColumnHandle> assignments)
    {
        return connectorExpressionRewriter.rewrite(session, expression, assignments);
    }

    @Override
    protected void verifyColumnName(DatabaseMetaData databaseMetadata, String columnName)
            throws SQLException
    {
        if (columnName.length() > databaseMetadata.getMaxColumnNameLength()) {
            throw new TrinoException(NOT_SUPPORTED, format("Column name must be shorter than or equal to '%s' characters but got '%s': '%s'", databaseMetadata.getMaxColumnNameLength(), columnName.length(), columnName));
        }
    }

    private TableStatistics readTableStatistics(ConnectorSession session, JdbcTableHandle table)
            throws SQLException
    {
        checkArgument(table.isNamedRelation(), "Relation is not a table: %s", table);

        log.debug("Reading statistics for %s", table);
        try (Connection connection = connectionFactory.openConnection(session);
                Handle handle = Jdbi.open(connection)) {
            StatisticsDao statisticsDao = new StatisticsDao(handle);

            Long rowCount = statisticsDao.getRowCount(table);
            log.debug("Estimated row count of table %s is %s", table, rowCount);

            if (rowCount == null) {
                // Table not found, or is a view.
                return TableStatistics.empty();
            }

            TableStatistics.Builder tableStatistics = TableStatistics.builder();
            tableStatistics.setRowCount(Estimate.of(rowCount));

            Map<String, Map<String, Long>> allColumnStatistics = statisticsDao.getAllColumnStatistics(table);

            for (JdbcColumnHandle column : JdbcMetadata.getColumns(session, this, table)) {
                String columnName = column.getColumnName();
                Map<String, Long> columnStatistics = allColumnStatistics.getOrDefault(columnName, new HashMap<>());

                ColumnStatistics.Builder columnStatisticsBuilder = ColumnStatistics.builder();

                Long distinctValues = columnStatistics.get("NUMBER_DISTINCT_VALUES");
                Long rowCountValues = columnStatistics.get("CURRENT_ROWS");
                Long nullsCount = columnStatistics.get("NUMBER_NULLS");
                Long averageColumnLength = columnStatistics.get("AVERAGE_COLUMN_LENGTH");
                if (distinctValues != null) {
                    columnStatisticsBuilder.setDistinctValuesCount(Estimate.of(distinctValues));
                }

                if (rowCountValues != null && nullsCount != null && rowCountValues > 0) {
                    double nullsFraction = (double) nullsCount / rowCountValues;
                    columnStatisticsBuilder.setNullsFraction(Estimate.of(nullsFraction));
                }
                if (rowCountValues != null && averageColumnLength != null) {
                    long estimatedDataSize = averageColumnLength * rowCountValues;

                    columnStatisticsBuilder.setDataSize(Estimate.of(estimatedDataSize));
                }

                ColumnStatistics statistics = columnStatisticsBuilder.build();
                tableStatistics.setColumnStatistics(column, statistics);
            }

            return tableStatistics.build();
        }
    }

    private static class StatisticsDao
    {
        private final Handle handle;

        public StatisticsDao(Handle handle)
        {
            this.handle = requireNonNull(handle, "handle is null");
        }

        Long getRowCount(JdbcTableHandle table)
        {
            RemoteTableName remoteTableName = table.getRequiredNamedRelation().getRemoteTableName();
            // One row per table; NUMBER_ROWS counts the rows of all partitions and members. SQL names are used rather than
            // the 10-character system names.
            return handle.createQuery("" +
                            "SELECT NUMBER_ROWS FROM QSYS2.SYSTABLESTAT " +
                            "WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table_name ")
                    .bind("schema", remoteTableName.getSchemaName().orElse(null))
                    .bind("table_name", remoteTableName.getTableName())
                    .mapTo(Long.class)
                    .findOne()
                    .orElse(null);
        }

        Map<String, Map<String, Long>> getAllColumnStatistics(JdbcTableHandle table)
        {
            RemoteTableName remoteTableName = table.getRequiredNamedRelation().getRemoteTableName();
            // One row per column and partition or member, combined here. Only single-column statistics collected without a
            // translation table are used, so that the counts match the binary sort sequence. SYSCOLUMNSTAT reports -1 for a
            // statistic that was not collected; the combined value is NULL as soon as one partition lacks it.
            return handle.createQuery("" +
                            "SELECT COLUMN_NAME, " +
                            "CASE WHEN MIN(NUMBER_DISTINCT_VALUES) < 0 THEN NULL ELSE MAX(NUMBER_DISTINCT_VALUES) END AS NUMBER_DISTINCT_VALUES, " +
                            "SUM(CURRENT_ROWS) AS CURRENT_ROWS, " +
                            "MAX(AVERAGE_COLUMN_LENGTH) AS AVERAGE_COLUMN_LENGTH, " +
                            "CASE WHEN MIN(NUMBER_NULLS) < 0 THEN NULL ELSE SUM(NUMBER_NULLS) END AS NUMBER_NULLS " +
                            "FROM QSYS2.SYSCOLUMNSTAT " +
                            "WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table_name AND NUMBER_COLUMN_NAMES = 1 AND TRANSLATION_TABLES = '' " +
                            "GROUP BY COLUMN_NAME ")
                    .bind("schema", remoteTableName.getSchemaName().orElse(null))
                    .bind("table_name", remoteTableName.getTableName())
                    .map((rs, _) -> {
                        String columnName = rs.getString("COLUMN_NAME");
                        Map<String, Long> columnStats = new HashMap<>();
                        for (String statistic : List.of("NUMBER_DISTINCT_VALUES", "CURRENT_ROWS", "AVERAGE_COLUMN_LENGTH", "NUMBER_NULLS")) {
                            long value = rs.getLong(statistic);
                            // A statistic that was not collected is NULL; Trino rejects negative statistics
                            if (!rs.wasNull() && value >= 0) {
                                columnStats.put(statistic, value);
                            }
                        }
                        return Map.entry(columnName, columnStats);
                    })
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
    }

    @Override
    protected void renameTable(ConnectorSession session, String catalogName, String schemaName, String tableName, SchemaTableName newTable)
    {
        try (Connection connection = connectionFactory.openConnection(session)) {
            String newTableName = newTable.getTableName();
            if (connection.getMetaData().storesUpperCaseIdentifiers()) {
                newTableName = newTableName.toUpperCase(ENGLISH);
            }
            // Specifies the new name for the table without a schema name
            String sql = format(
                    "RENAME TABLE %s TO %s",
                    quoted(catalogName, schemaName, tableName),
                    quoted(newTableName));
            try {
                execute(session, connection, sql);
            }
            catch (SQLException e) {
                throw new TrinoException(JDBC_ERROR, e);
            }
        }
        catch (SQLException e) {
            throw new TrinoException(JDBC_ERROR, e);
        }
    }

    @Override
    protected Optional<BiFunction<String, Long, String>> limitFunction()
    {
        return Optional.of((sql, limit) -> sql + " FETCH FIRST " + limit + " ROWS ONLY ");
    }

    @Override
    public boolean isLimitGuaranteed(ConnectorSession session)
    {
        return true;
    }

    @Override
    public boolean supportsTopN(ConnectorSession session, JdbcTableHandle handle, List<JdbcSortItem> sortOrder)
    {
        for (JdbcSortItem sortItem : sortOrder) {
            Type sortItemType = sortItem.column().getColumnType();
            if (sortItemType instanceof CharType || sortItemType instanceof VarcharType) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isTopNGuaranteed(ConnectorSession session)
    {
        return true;
    }

    @Override
    protected Optional<TopNFunction> topNFunction()
    {
        return Optional.of((query, sortItems, limit) -> {
            String orderBy = sortItems.stream()
                    .flatMap(sortItem -> {
                        String columnName = quoted(sortItem.column().getColumnName());

                        return switch (sortItem.sortOrder()) {
                            case ASC_NULLS_LAST -> Stream.of(format("%s ASC", columnName));
                            case DESC_NULLS_FIRST -> Stream.of(format("%s DESC", columnName));
                            case ASC_NULLS_FIRST -> Stream.of(
                                    format("(CASE WHEN %s IS NULL THEN 0 ELSE 1 END)", columnName),
                                    format("%s ASC", columnName));
                            case DESC_NULLS_LAST -> Stream.of(
                                    format("(CASE WHEN %s IS NULL THEN 1 ELSE 0 END)", columnName),
                                    format("%s DESC", columnName));
                        };
                    })
                    .collect(joining(", "));
            return format("%s ORDER BY %s FETCH FIRST %s ROWS ONLY", query, orderBy, limit);
        });
    }

    @Override
    protected void copyTableSchema(ConnectorSession session, Connection connection, String catalogName, String schemaName, String tableName, String newTableName, List<String> columnNames)
    {
        String sql = format(
                "CREATE TABLE %s AS (SELECT %s FROM %s) WITH NO DATA",
                quoted(catalogName, schemaName, newTableName),
                columnNames.stream()
                        .map(this::quoted)
                        .collect(joining(", ")),
                quoted(catalogName, schemaName, tableName));
        try {
            execute(session, connection, sql);
        }
        catch (SQLException e) {
            throw new TrinoException(JDBC_ERROR, e);
        }
    }
}
