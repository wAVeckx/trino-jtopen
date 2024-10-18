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

public interface ITestConfigurationConstants
{
    // JDBC URL for JTOPEN
    // Assuming that the trino container is running on the local machine at port 8080
    String JTOPEN_JDBC_URL = "jdbc:trino://localhost:8080/jtopen";

    // User id for trino JDBC connection. No password is required for this id.
    String TRINO_JDBC_USER = "admin";

    // The sample tiny nation table from the TPCH connector
    String SAMPLE_TPCH_TABLE = "tpch.tiny.nation";

    // The JTOPEN test schema
    String JTOPEN_TEST_SCHEMA = "TRINOJTTST";

    // The name of the JTOPEN test table that is copied from the TPCH tiny nation table
    String JTOPEN_TEST_TABLE_NATION = "NATION";
}
