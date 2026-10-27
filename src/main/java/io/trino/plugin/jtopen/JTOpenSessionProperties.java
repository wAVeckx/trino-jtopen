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
import com.google.inject.Inject;
import io.trino.plugin.base.session.SessionPropertiesProvider;
import io.trino.plugin.jtopen.JTOpenConfig.VarcharComparisonSemantics;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.session.PropertyMetadata;

import java.util.List;

import static io.trino.spi.session.PropertyMetadata.enumProperty;

public final class JTOpenSessionProperties
        implements SessionPropertiesProvider
{
    public static final String VARCHAR_COMPARISON_SEMANTICS = "varchar_comparison_semantics";

    private final List<PropertyMetadata<?>> sessionProperties;

    @Inject
    public JTOpenSessionProperties(JTOpenConfig config)
    {
        sessionProperties = ImmutableList.of(
                enumProperty(
                        VARCHAR_COMPARISON_SEMANTICS,
                        "Semantics assumed when pushing comparisons on VARCHAR columns down to Db2 for i: EXACT keeps Trino semantics, PAD_SPACE accepts that values differing only in trailing blanks are equal",
                        VarcharComparisonSemantics.class,
                        config.getVarcharComparisonSemantics(),
                        false));
    }

    @Override
    public List<PropertyMetadata<?>> getSessionProperties()
    {
        return sessionProperties;
    }

    public static VarcharComparisonSemantics getVarcharComparisonSemantics(ConnectorSession session)
    {
        return session.getProperty(VARCHAR_COMPARISON_SEMANTICS, VarcharComparisonSemantics.class);
    }
}
