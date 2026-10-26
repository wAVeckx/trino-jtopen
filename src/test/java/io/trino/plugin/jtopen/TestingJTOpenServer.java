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

import com.ibm.as400.access.AS400JDBCDriver;
import io.trino.testing.sql.SqlExecutor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * A live IBM i system for the integration tests, selected with environment variables. The tests are skipped when
 * {@value #JDBC_URL_VARIABLE} is not set.
 * <ul>
 * <li>{@value #JDBC_URL_VARIABLE}: JDBC URL, for example {@code jdbc:as400://myibmi}</li>
 * <li>{@value #USER_VARIABLE} and {@value #PASSWORD_VARIABLE}: credentials of a profile that may create schemas and tables</li>
 * <li>{@value #SCHEMA_VARIABLE}: schema (library) the tests create their tables in, created when missing; default {@value #DEFAULT_SCHEMA}</li>
 * </ul>
 */
public final class TestingJTOpenServer
        implements SqlExecutor, AutoCloseable
{
    public static final String JDBC_URL_VARIABLE = "JTOPEN_TEST_JDBC_URL";
    public static final String USER_VARIABLE = "JTOPEN_TEST_USER";
    public static final String PASSWORD_VARIABLE = "JTOPEN_TEST_PASSWORD";
    public static final String SCHEMA_VARIABLE = "JTOPEN_TEST_SCHEMA";
    public static final String DEFAULT_SCHEMA = "TRINOTEST";

    private final AS400JDBCDriver driver = new AS400JDBCDriver();
    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final String schema;

    public static TestingJTOpenServer fromEnvironment()
    {
        String jdbcUrl = System.getenv(JDBC_URL_VARIABLE);
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            abort("Set %s, %s and %s to run the integration tests against an IBM i system".formatted(JDBC_URL_VARIABLE, USER_VARIABLE, PASSWORD_VARIABLE));
        }
        String schema = System.getenv().getOrDefault(SCHEMA_VARIABLE, DEFAULT_SCHEMA).toUpperCase(ENGLISH);
        TestingJTOpenServer server = new TestingJTOpenServer(jdbcUrl, requireEnvironment(USER_VARIABLE), requireEnvironment(PASSWORD_VARIABLE), schema);
        server.createSchemaIfMissing();
        return server;
    }

    private TestingJTOpenServer(String jdbcUrl, String user, String password, String schema)
    {
        this.jdbcUrl = requireNonNull(jdbcUrl, "jdbcUrl is null");
        this.user = requireNonNull(user, "user is null");
        this.password = requireNonNull(password, "password is null");
        this.schema = requireNonNull(schema, "schema is null");
    }

    private static String requireEnvironment(String variable)
    {
        String value = System.getenv(variable);
        if (value == null) {
            throw new IllegalStateException("Environment variable %s is not set".formatted(variable));
        }
        return value;
    }

    public String getJdbcUrl()
    {
        return jdbcUrl;
    }

    public String getUser()
    {
        return user;
    }

    public String getPassword()
    {
        return password;
    }

    /**
     * The schema the tests work in, as named on IBM i.
     */
    public String getSchema()
    {
        return schema;
    }

    /**
     * Runs a statement on IBM i, with unqualified names resolved in the test schema.
     */
    @Override
    public void execute(String sql)
    {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA " + schema);
            statement.execute(sql);
        }
        catch (SQLException e) {
            throw new RuntimeException("Failed to execute statement on IBM i: " + sql, e);
        }
    }

    private void createSchemaIfMissing()
    {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM QSYS2.SYSSCHEMAS WHERE SCHEMA_NAME = ?")) {
            statement.setString(1, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return;
                }
            }
            try (Statement create = connection.createStatement()) {
                create.execute("CREATE SCHEMA " + schema);
            }
        }
        catch (SQLException e) {
            throw new RuntimeException("Failed to prepare the test schema " + schema, e);
        }
    }

    private Connection connect()
            throws SQLException
    {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        // Same date handling as the connector, so that literals in test statements are read the same way
        properties.setProperty("date format", "iso");
        properties.setProperty("errors", "full");
        Connection connection = driver.connect(jdbcUrl, properties);
        if (connection == null) {
            throw new SQLException("The JTOpen driver does not accept the URL " + jdbcUrl);
        }
        return connection;
    }

    @Override
    public void close() {}
}
