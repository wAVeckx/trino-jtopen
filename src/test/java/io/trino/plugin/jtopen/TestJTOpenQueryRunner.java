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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TestJTOpenQueryRunner
{
    @Test
    public void testSensitivePropertiesAreMasked()
    {
        assertThat(JTOpenQueryRunner.masked(ImmutableMap.<String, String>builder()
                .put("connection-url", "jdbc:as400://myibmi;user=trino;password=secret;secure=true")
                .put("connection-user", "trino")
                .put("connection-password", "secret")
                .put("keystore-password", "secret")
                .put("keystore-path", "/etc/trino/ibmi.crt")
                .put("oauth2.client-secret", "secret")
                .put("access-token", "secret")
                .put("tls.private-key", "secret")
                .buildOrThrow()))
                .containsExactlyInAnyOrderEntriesOf(ImmutableMap.<String, String>builder()
                        .put("connection-url", "jdbc:as400://myibmi;user=trino;password=***;secure=true")
                        .put("connection-user", "trino")
                        .put("connection-password", "***")
                        .put("keystore-password", "***")
                        .put("keystore-path", "/etc/trino/ibmi.crt")
                        .put("oauth2.client-secret", "***")
                        .put("access-token", "***")
                        .put("tls.private-key", "***")
                        .buildOrThrow());
    }
}
