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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

/**
 * The sort sequence selected by the JDBC properties of a connection URL, which decides how Db2 for i compares
 * character data. {@code sort=hex}, the driver default, compares binary values and is case-sensitive.
 * {@code sort=language} and {@code sort=table} compare by the weights of a sort sequence table, which ignore case
 * under the default {@code sort weight=shared}. {@code sort=job} takes the sort sequence of the user profile, which
 * cannot be known from the URL.
 */
final class JTOpenSortSequence
{
    private JTOpenSortSequence() {}

    /**
     * Fails when {@code jtopen.case-insensitive-sort-sequence} contradicts the sort properties of the connection URL:
     * the connector would either return wrong results or keep comparisons in the engine for no reason.
     */
    static void verify(String connectionUrl, boolean caseInsensitiveSortSequence)
    {
        Optional<Boolean> caseInsensitive = isCaseInsensitive(connectionUrl);
        if (caseInsensitive.isEmpty() || caseInsensitive.get() == caseInsensitiveSortSequence) {
            return;
        }
        if (caseInsensitiveSortSequence) {
            throw new IllegalArgumentException("jtopen.case-insensitive-sort-sequence is true, but the connection URL selects a case-sensitive sort sequence. " +
                    "Set the property to false, or select a shared-weight sort sequence with sort=language or sort=table in the connection URL");
        }
        throw new IllegalArgumentException("The connection URL selects a shared-weight sort sequence, under which Db2 for i compares character data without regard to case. " +
                "Set jtopen.case-insensitive-sort-sequence=true, or use sort weight=unique or the default sort=hex in the connection URL");
    }

    /**
     * Whether the sort sequence selected by the connection URL ignores case. Empty for {@code sort=job}, which depends
     * on the user profile, and for values the driver would reject.
     */
    static Optional<Boolean> isCaseInsensitive(String connectionUrl)
    {
        Map<String, String> properties = connectionUrlProperties(connectionUrl);
        String sort = properties.getOrDefault("sort", "hex");
        String weight = properties.getOrDefault("sort weight", "shared");
        return switch (sort) {
            case "hex" -> Optional.of(false);
            case "language", "table" -> Optional.of(weight.equals("shared"));
            default -> Optional.empty();
        };
    }

    /**
     * The JDBC properties of a JTOpen URL, {@code jdbc:as400://host[:port][/schema][;name=value...]}, with names and
     * values trimmed and lowercased so that they can be compared without regard to case. The last value of a property
     * given more than once wins.
     */
    static Map<String, String> connectionUrlProperties(String connectionUrl)
    {
        requireNonNull(connectionUrl, "connectionUrl is null");
        int start = connectionUrl.indexOf(';');
        if (start < 0) {
            return ImmutableMap.of();
        }
        Map<String, String> properties = new HashMap<>();
        for (String property : connectionUrl.substring(start + 1).split(";")) {
            int separator = property.indexOf('=');
            if (separator < 0) {
                continue;
            }
            properties.put(
                    property.substring(0, separator).trim().toLowerCase(ENGLISH),
                    property.substring(separator + 1).trim().toLowerCase(ENGLISH));
        }
        return ImmutableMap.copyOf(properties);
    }
}
