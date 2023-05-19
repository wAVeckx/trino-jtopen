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

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;

import javax.validation.constraints.Min;

//import java.util.concurrent.TimeUnit;

public class JTOpenConfig
{
    // 32672 value comes from the official LUW document
    // 32739 values comes from the official i document
    private int varcharMaxLength = 32739;
    private boolean storedProcedureTableFunctionEnabled;

    @Min(1)
    public int getVarcharMaxLength()
    {
        return varcharMaxLength;
    }

    @Config("jtopen.varchar-max-length")
    @ConfigDescription("Max length of varchar type in CREATE TABLE statement")
    public JTOpenConfig setVarcharMaxLength(int varcharMaxLength)
    {
        this.varcharMaxLength = varcharMaxLength;
        return this;
    }

    public boolean isStoredProcedureTableFunctionEnabled()
    {
        return storedProcedureTableFunctionEnabled;
    }

    @Config("jtopen.experimental.stored-procedure-table-function-enabled")
    @ConfigDescription("Allows accessing Stored procedure as a table function")
    public JTOpenConfig setStoredProcedureTableFunctionEnabled(boolean storedProcedureTableFunctionEnabled)
    {
        this.storedProcedureTableFunctionEnabled = storedProcedureTableFunctionEnabled;
        return this;
    }
}
