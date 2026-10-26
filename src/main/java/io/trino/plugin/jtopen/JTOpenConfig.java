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
import io.airlift.configuration.LegacyConfig;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import static io.trino.plugin.jtopen.JTOpenConfig.VarcharComparisonSemantics.EXACT;

public class JTOpenConfig
{
    /**
     * Semantics the connector may assume when it pushes a comparison on a VARCHAR column down to Db2 for i, which
     * compares character strings with PAD SPACE semantics: the shorter operand is padded with blanks, so values that
     * differ only in trailing blanks are equal.
     */
    public enum VarcharComparisonSemantics
    {
        /**
         * Trino semantics: trailing blanks are significant. Equality, IN and NULLIF on VARCHAR and NVARCHAR columns
         * are pushed down together with a character length check that makes them exact. On other character column
         * types, for which no safe parameter cast exists, equality and IN are pushed down only as a pre-filter that
         * the engine re-checks.
         */
        EXACT,
        /**
         * Db2 for i semantics: trailing blanks are insignificant. Comparisons are pushed down as they are, without
         * the length check, and values that differ only in trailing blanks compare equal.
         */
        PAD_SPACE,
    }

    // Db2 for i allows up to 32739 bytes for a VARCHAR column (32740 minus the 2-byte length prefix, less overhead)
    private int varcharMaxLength = 32739;
    private boolean storedProcedureTableFunctionEnabled;
    private boolean caseInsensitiveSortSequence;
    private VarcharComparisonSemantics varcharComparisonSemantics = EXACT;

    @Min(1)
    public int getVarcharMaxLength()
    {
        return varcharMaxLength;
    }

    @Config("jtopen.varchar-max-length")
    @ConfigDescription("Max length of VARCHAR columns created by Trino; longer varchar types are created as CLOB")
    public JTOpenConfig setVarcharMaxLength(int varcharMaxLength)
    {
        this.varcharMaxLength = varcharMaxLength;
        return this;
    }

    public boolean isStoredProcedureTableFunctionEnabled()
    {
        return storedProcedureTableFunctionEnabled;
    }

    @Config("jtopen.stored-procedure-table-function-enabled")
    @LegacyConfig("jtopen.experimental.stored-procedure-table-function-enabled")
    @ConfigDescription("Allows accessing stored procedures as a table function")
    public JTOpenConfig setStoredProcedureTableFunctionEnabled(boolean storedProcedureTableFunctionEnabled)
    {
        this.storedProcedureTableFunctionEnabled = storedProcedureTableFunctionEnabled;
        return this;
    }

    public boolean isCaseInsensitiveSortSequence()
    {
        return caseInsensitiveSortSequence;
    }

    @Config("jtopen.case-insensitive-sort-sequence")
    @ConfigDescription("Whether the JDBC sort properties of the connection URL select a shared-weight sort sequence, under which Db2 for i compares character data without regard to case")
    public JTOpenConfig setCaseInsensitiveSortSequence(boolean caseInsensitiveSortSequence)
    {
        this.caseInsensitiveSortSequence = caseInsensitiveSortSequence;
        return this;
    }

    @NotNull
    public VarcharComparisonSemantics getVarcharComparisonSemantics()
    {
        return varcharComparisonSemantics;
    }

    @Config("jtopen.varchar-comparison-semantics")
    @ConfigDescription("Semantics assumed when pushing comparisons on VARCHAR columns down to Db2 for i: EXACT keeps Trino semantics, PAD_SPACE accepts that values differing only in trailing blanks are equal")
    public JTOpenConfig setVarcharComparisonSemantics(VarcharComparisonSemantics varcharComparisonSemantics)
    {
        this.varcharComparisonSemantics = varcharComparisonSemantics;
        return this;
    }
}
