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

import com.google.inject.Binder;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.ibm.as400.access.AS400JDBCDriver;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.opentelemetry.api.OpenTelemetry;
import io.trino.plugin.jdbc.BaseJdbcConfig;
import io.trino.plugin.jdbc.ConnectionFactory;
import io.trino.plugin.jdbc.DecimalModule;
import io.trino.plugin.jdbc.DriverConnectionFactory;
import io.trino.plugin.jdbc.ForBaseJdbc;
import io.trino.plugin.jdbc.JdbcClient;
import io.trino.plugin.jdbc.JdbcJoinPushdownSupportModule;
import io.trino.plugin.jdbc.JdbcStatisticsConfig;
import io.trino.plugin.jdbc.QueryBuilder;
import io.trino.plugin.jdbc.credential.CredentialProvider;
import io.trino.plugin.jdbc.ptf.Procedure;
import io.trino.plugin.jdbc.ptf.Query;
import io.trino.spi.function.table.ConnectorTableFunction;

import java.util.Properties;

import static com.google.inject.multibindings.Multibinder.newSetBinder;
import static com.google.inject.multibindings.OptionalBinder.newOptionalBinder;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.trino.plugin.jdbc.DecimalModule.MappingToNumber.ON_BY_DEFAULT;
import static io.trino.plugin.jdbc.JdbcModule.bindSessionPropertiesProvider;

public class JTOpenClientModule
        extends AbstractConfigurationAwareModule
{
    @Override
    public void setup(Binder binder)
    {
        binder.bind(JdbcClient.class).annotatedWith(ForBaseJdbc.class).to(JTOpenClient.class).in(Scopes.SINGLETON);
        configBinder(binder).bindConfig(JTOpenConfig.class);
        configBinder(binder).bindConfig(JdbcStatisticsConfig.class);
        bindSessionPropertiesProvider(binder, JTOpenSessionProperties.class);
        newOptionalBinder(binder, QueryBuilder.class).setBinding().to(JTOpenQueryBuilder.class).in(Scopes.SINGLETON);
        install(DecimalModule.withNumberMapping(ON_BY_DEFAULT));
        install(new JdbcJoinPushdownSupportModule());
        newSetBinder(binder, ConnectorTableFunction.class).addBinding().toProvider(Query.class).in(Scopes.SINGLETON);

        JTOpenConfig config = buildConfigObject(JTOpenConfig.class);
        if (config.isStoredProcedureTableFunctionEnabled()) {
            newSetBinder(binder, ConnectorTableFunction.class).addBinding().toProvider(Procedure.class).in(Scopes.SINGLETON);
        }

        // Fail at startup rather than return wrong results when the sort properties of the URL and the connector disagree
        JTOpenSortSequence.verify(buildConfigObject(BaseJdbcConfig.class).getConnectionUrl(), config.isCaseInsensitiveSortSequence());
    }

    @Provides
    @Singleton
    @ForBaseJdbc
    public static ConnectionFactory getConnectionFactory(BaseJdbcConfig config, CredentialProvider credentialProvider, OpenTelemetry openTelemetry)
    {
        return DriverConnectionFactory.builder(new AS400JDBCDriver(), config.getConnectionUrl(), credentialProvider)
                .setConnectionProperties(connectionProperties())
                .setOpenTelemetry(openTelemetry)
                .build();
    }

    /**
     * Driver defaults tuned for Trino, which reads whole result sets and writes in batches. A property given in the
     * connection URL takes precedence over the value set here.
     */
    static Properties connectionProperties()
    {
        Properties properties = new Properties();
        // Largest block of rows fetched per round trip
        properties.setProperty("block size", "512");
        // *ALLIO: optimize for retrieving the whole result set; the driver default is *FIRSTIO for dynamic SQL
        properties.setProperty("query optimize goal", "2");
        // Blocked inserts for batched writes
        properties.setProperty("use block update", "true");
        // Job date formats such as *MDY cannot represent years outside 1940-2039
        properties.setProperty("date format", "iso");
        // Second-level message text in SQLExceptions
        properties.setProperty("errors", "full");
        // REMARKS in metadata carries the SQL comment set with COMMENT ON, rather than the object text
        properties.setProperty("remarks", "sql");
        return properties;
    }
}
