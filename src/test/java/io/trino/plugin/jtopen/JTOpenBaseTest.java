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

import java.sql.SQLException;

import static io.trino.plugin.jtopen.JTOpenTestDriver.onJTOpen;
import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class JTOpenBaseTest implements ITestConfigurationConstants {
    
    /**
     * Create a JTOpen test table from the tpch table tpch.tiny.nation
     * @return the full name of the created JTOpen table
     */
    public String createTinyNationTable() throws SQLException {
        // The name of the test schema: jtopen.trinojttst
        String schemaName = format("jtopen.%s", JTOPEN_TEST_SCHEMA);
        onJTOpen().createSchema(schemaName);

        // The fully qualified name of the test table: jtopen.trinojttst.nation
        String tableName = format("%s.%s", schemaName, JTOPEN_TEST_TABLE_NATION);
        // Create a JTOpen test table from the existing TPCH table: tpch.tiny.nation.
        int result = onJTOpen().createTableFrom(tableName, SAMPLE_TPCH_TABLE);
        // Verify row count
        assertEquals(result, 25);
        return tableName;
    }

    /**
     * Perform test cleanup, which drops the test table at the end of the test and also closes the JDBC connection.
     * @param tableName the test table to clean up
     */
    public void cleanup(String tableName) {
        try {
            if (tableName != null) {
                onJTOpen().dropTable(tableName);
            }
            
            onJTOpen().close();
        } catch (Throwable ex) {
            throw new RuntimeException(ex);
        }
    }
}
