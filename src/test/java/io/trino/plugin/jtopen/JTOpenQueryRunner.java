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
import com.google.common.collect.Maps;
import io.airlift.log.Logger;
import io.airlift.log.Logging;
import io.trino.plugin.tpch.TpchPlugin;
import io.trino.testing.DistributedQueryRunner;
import io.trino.testing.QueryRunner;
import io.trino.tpch.TpchTable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static io.airlift.testing.Closeables.closeAllSuppress;
import static io.trino.plugin.tpch.TpchMetadata.TINY_SCHEMA_NAME;
import static io.trino.testing.QueryAssertions.copyTpchTables;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

public final class JTOpenQueryRunner
{
    private JTOpenQueryRunner() {}

    static {
        Logging.initialize();
    }

    private static final Logger log = Logger.get(JTOpenQueryRunner.class);

    // Properties whose values are not logged, and the password property of a JTOpen URL
    private static final Pattern SENSITIVE_PROPERTY_NAME = Pattern.compile("(?i).*(password|secret|token|credential|private-key|api-key|access-key).*");
    private static final Pattern URL_PASSWORD = Pattern.compile("(?i)(password\\s*=)[^;]*");

    public static final String CATALOG = "jtopen";

    public static Builder builder(TestingJTOpenServer server)
    {
        return new Builder(server.getSchema())
                .addConnectorProperties(Map.of(
                        "connection-url", server.getJdbcUrl(),
                        "connection-user", server.getUser(),
                        "connection-password", server.getPassword()));
    }

    public static final class Builder
            extends DistributedQueryRunner.Builder<Builder>
    {
        private final Map<String, String> connectorProperties = new HashMap<>();
        private List<TpchTable<?>> initialTables = ImmutableList.of();

        private Builder(String schema)
        {
            super(testSessionBuilder()
                    .setCatalog(CATALOG)
                    .setSchema(schema.toLowerCase(ENGLISH))
                    .build());
        }

        public Builder addConnectorProperties(Map<String, String> connectorProperties)
        {
            this.connectorProperties.putAll(requireNonNull(connectorProperties, "connectorProperties is null"));
            return this;
        }

        public Builder setInitialTables(Iterable<TpchTable<?>> initialTables)
        {
            this.initialTables = ImmutableList.copyOf(requireNonNull(initialTables, "initialTables is null"));
            return this;
        }

        @Override
        public DistributedQueryRunner build()
                throws Exception
        {
            DistributedQueryRunner queryRunner = super.build();
            try {
                queryRunner.installPlugin(new TpchPlugin());
                queryRunner.createCatalog("tpch", "tpch");

                queryRunner.installPlugin(new JTOpenPlugin());
                queryRunner.createCatalog(CATALOG, "jtopen", connectorProperties);
                log.info("%s catalog properties: %s", CATALOG, masked(connectorProperties));

                copyTpchTables(queryRunner, "tpch", TINY_SCHEMA_NAME, initialTables);

                return queryRunner;
            }
            catch (Throwable e) {
                closeAllSuppress(e, queryRunner);
                throw e;
            }
        }
    }

    static Map<String, String> masked(Map<String, String> properties)
    {
        return Maps.transformEntries(properties, (name, value) -> SENSITIVE_PROPERTY_NAME.matcher(name).matches()
                ? "***"
                : URL_PASSWORD.matcher(value).replaceAll("$1***"));
    }

    /**
     * Starts a coordinator on port 8080 with a jtopen catalog pointing at the IBM i system selected by the environment
     * variables of {@link TestingJTOpenServer}, for manual testing.
     */
    static void main()
            throws Exception
    {
        TestingJTOpenServer server = TestingJTOpenServer.fromEnvironment();
        QueryRunner queryRunner = builder(server)
                .addCoordinatorProperty("http-server.http.port", "8080")
                .setInitialTables(TpchTable.getTables())
                .build();

        log.info("======== SERVER STARTED ========");
        log.info("\n====\n%s\n====", queryRunner.getCoordinator().getBaseUrl());
    }
}
