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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestJTOpenPlugin
{
    @Test
    public void testCreateConnector()
    {
        Plugin plugin = new JTOpenPlugin();
        ConnectorFactory factory = getOnlyElement(plugin.getConnectorFactories());
        assertThat(factory.getName()).isEqualTo("jtopen");
        factory.create("test", ImmutableMap.of("connection-url", "jdbc:as400:test"), new TestingConnectorContext()).shutdown();
    }

    @Test
    public void testCreateConnectorWithAllProperties()
    {
        Plugin plugin = new JTOpenPlugin();
        ConnectorFactory factory = getOnlyElement(plugin.getConnectorFactories());
        factory.create(
                "test",
                ImmutableMap.<String, String>builder()
                        .put("connection-url", "jdbc:as400://host/schema;sort=language;sort weight=shared")
                        .put("connection-user", "user")
                        .put("connection-password", "password")
                        .put("jtopen.varchar-max-length", "16000")
                        .put("jtopen.stored-procedure-table-function-enabled", "true")
                        .put("jtopen.case-insensitive-sort-sequence", "true")
                        .put("jtopen.varchar-comparison-semantics", "PAD_SPACE")
                        .buildOrThrow(),
                new TestingConnectorContext())
                .shutdown();
    }

    @Test
    public void testSortSequenceMustMatchConfiguration()
    {
        Plugin plugin = new JTOpenPlugin();
        ConnectorFactory factory = getOnlyElement(plugin.getConnectorFactories());
        // sort=language ignores case under the default sort weight; the connector refuses to start without being told
        assertThatThrownBy(() -> factory.create("test", ImmutableMap.of("connection-url", "jdbc:as400://host;sort=language"), new TestingConnectorContext()))
                .hasMessageContaining("Set jtopen.case-insensitive-sort-sequence=true");
        assertThatThrownBy(() -> factory.create(
                "test",
                ImmutableMap.of("connection-url", "jdbc:as400://host", "jtopen.case-insensitive-sort-sequence", "true"),
                new TestingConnectorContext()))
                .hasMessageContaining("jtopen.case-insensitive-sort-sequence is true");
    }
}
