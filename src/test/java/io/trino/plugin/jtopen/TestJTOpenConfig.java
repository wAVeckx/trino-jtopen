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
import io.airlift.configuration.ConfigurationFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.trino.plugin.jtopen.JTOpenConfig.VarcharComparisonSemantics.EXACT;
import static io.trino.plugin.jtopen.JTOpenConfig.VarcharComparisonSemantics.PAD_SPACE;
import static org.assertj.core.api.Assertions.assertThat;

public class TestJTOpenConfig
{
    @Test
    public void testDefaults()
    {
        JTOpenConfig config = new JTOpenConfig();
        assertThat(config.getVarcharMaxLength()).isEqualTo(32739);
        assertThat(config.isStoredProcedureTableFunctionEnabled()).isFalse();
        assertThat(config.isCaseInsensitiveSortSequence()).isFalse();
        assertThat(config.getVarcharComparisonSemantics()).isEqualTo(EXACT);
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("jtopen.varchar-max-length", "30000")
                .put("jtopen.stored-procedure-table-function-enabled", "true")
                .put("jtopen.case-insensitive-sort-sequence", "true")
                .put("jtopen.varchar-comparison-semantics", "PAD_SPACE")
                .buildOrThrow();

        JTOpenConfig config = new ConfigurationFactory(properties).build(JTOpenConfig.class);

        assertThat(config.getVarcharMaxLength()).isEqualTo(30000);
        assertThat(config.isStoredProcedureTableFunctionEnabled()).isTrue();
        assertThat(config.isCaseInsensitiveSortSequence()).isTrue();
        assertThat(config.getVarcharComparisonSemantics()).isEqualTo(PAD_SPACE);
    }

    @Test
    public void testLegacyPropertyMapping()
    {
        JTOpenConfig config = new ConfigurationFactory(ImmutableMap.of("jtopen.experimental.stored-procedure-table-function-enabled", "true"))
                .build(JTOpenConfig.class);
        assertThat(config.isStoredProcedureTableFunctionEnabled()).isTrue();
    }
}
