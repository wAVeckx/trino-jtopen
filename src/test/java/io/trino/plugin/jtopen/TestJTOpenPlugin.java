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
import io.trino.spi.Plugin;
import io.trino.spi.connector.ConnectorFactory;
import io.trino.testing.TestingConnectorContext;
import org.junit.jupiter.api.Test;

import static com.google.common.collect.Iterables.getOnlyElement;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class TestJTOpenPlugin {
    @Test
    public void testCreateConnector() {
        Plugin plugin = new JTOpenPlugin();
        ConnectorFactory factory = getOnlyElement(plugin.getConnectorFactories());
        assertEquals(factory.getName(), "jtopen");
        factory.create("test", ImmutableMap.of("connection-url", "jdbc:as400:test"), new TestingConnectorContext());
    }

    @Test
    public void testQuery() throws IOException, ClassNotFoundException {
        // Expected data
        List<String> expectedColumnNames = List.of("id", "first_name", "last_name", "email");

        // Get trino jdbc url
        Properties prop = new Properties();
        FileInputStream ip = new FileInputStream("config.properties");
        prop.load(ip);
        String url = prop.getProperty("db.url");

        // Use Trino Driver and establish connection
        Class.forName("io.trino.jdbc.TrinoDriver");

        String query = "SELECT * FROM qgpl.customers";

        try (
            Connection connection = DriverManager.getConnection(url);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(query);
            ) 
        {
            ResultSetMetaData rsMetadata = resultSet.getMetaData();

            // Verify correct column names
            List<String> actualColumnNames = new ArrayList<>();
            for (int i = 1; i <= rsMetadata.getColumnCount(); i++) {
                actualColumnNames.add(rsMetadata.getColumnName(0));
            }
            assertEquals(expectedColumnNames, actualColumnNames);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
