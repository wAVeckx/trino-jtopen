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

import org.junit.jupiter.api.Test;

import static io.trino.plugin.jtopen.JTOpenSortSequence.connectionUrlProperties;
import static io.trino.plugin.jtopen.JTOpenSortSequence.isCaseInsensitive;
import static io.trino.plugin.jtopen.JTOpenSortSequence.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

public class TestJTOpenSortSequence
{
    @Test
    public void testConnectionUrlProperties()
    {
        assertThat(connectionUrlProperties("jdbc:as400://host")).isEmpty();
        assertThat(connectionUrlProperties("jdbc:as400://host:8471/schema")).isEmpty();
        assertThat(connectionUrlProperties("jdbc:as400://host/schema;sort=language;sort weight=unique;libraries=a,b"))
                .containsOnly(entry("sort", "language"), entry("sort weight", "unique"), entry("libraries", "a,b"));
        // names and values are matched without regard to case or surrounding blanks; a property without a value is ignored
        assertThat(connectionUrlProperties("jdbc:as400://host; Sort = Language ;SORT WEIGHT=Shared;prompt"))
                .containsOnly(entry("sort", "language"), entry("sort weight", "shared"));
        // the last value wins
        assertThat(connectionUrlProperties("jdbc:as400://host;sort=language;sort=hex"))
                .containsOnly(entry("sort", "hex"));
    }

    @Test
    public void testIsCaseInsensitive()
    {
        assertThat(isCaseInsensitive("jdbc:as400://host")).contains(false);
        assertThat(isCaseInsensitive("jdbc:as400://host;sort=hex")).contains(false);
        assertThat(isCaseInsensitive("jdbc:as400://host;sort=language")).contains(true);
        assertThat(isCaseInsensitive("jdbc:as400://host;sort=language;sort language=deu;sort weight=shared")).contains(true);
        assertThat(isCaseInsensitive("jdbc:as400://host;sort=language;sort weight=unique")).contains(false);
        assertThat(isCaseInsensitive("jdbc:as400://host;sort=table;sort table=QSYS/QLA10025S")).contains(true);
        assertThat(isCaseInsensitive("jdbc:as400://host;sort=table;sort table=QSYS/QLA10025S;sort weight=unique")).contains(false);
        // the sort sequence of the user profile is not known from the URL
        assertThat(isCaseInsensitive("jdbc:as400://host;sort=job")).isEmpty();
        assertThat(isCaseInsensitive("jdbc:as400://host;sort=nonsense")).isEmpty();
    }

    @Test
    public void testVerify()
    {
        assertThatCode(() -> verify("jdbc:as400://host", false)).doesNotThrowAnyException();
        assertThatCode(() -> verify("jdbc:as400://host;sort=language", true)).doesNotThrowAnyException();
        assertThatCode(() -> verify("jdbc:as400://host;sort=language;sort weight=unique", false)).doesNotThrowAnyException();
        assertThatCode(() -> verify("jdbc:as400://host;sort=job", false)).doesNotThrowAnyException();
        assertThatCode(() -> verify("jdbc:as400://host;sort=job", true)).doesNotThrowAnyException();

        assertThatThrownBy(() -> verify("jdbc:as400://host;sort=language", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Set jtopen.case-insensitive-sort-sequence=true");
        assertThatThrownBy(() -> verify("jdbc:as400://host", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jtopen.case-insensitive-sort-sequence is true");
        assertThatThrownBy(() -> verify("jdbc:as400://host;sort=language;sort weight=unique", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("case-sensitive sort sequence");
    }
}
