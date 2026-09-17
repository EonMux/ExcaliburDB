package io.excalibur.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The query result cache's two settings.
 *
 * <p>A cache is a bet that the same question arrives twice before the corpus moves, and
 * whether that holds is a property of a deployment's traffic. So the interesting behaviour
 * here is what happens when nobody says anything - the cache must be off - and what happens
 * when the size is impossible, which is refused rather than corrected: this figure is the
 * node's memory ceiling for cached answers, and a deployment silently given a different one
 * has sized its heap against a number that is not in force.</p>
 */
@DisplayName("Query cache configuration")
class ConfigQueryCacheTest {

    /**
     * Builds a query section over the given properties.
     *
     * @param entries Alternating key and value
     * @return The parsed section
     */
    private static QueryConfig query(String... entries) {
        Map<String, String> props = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            props.put(entries[i], entries[i + 1]);
        }
        return new QueryConfig(props);
    }

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("the cache is off unless a deployment asks for it")
        void offByDefault() {
            assertThat(query().isCacheEnabled()).isFalse();
        }

        @Test
        @DisplayName("the size has a default, so turning the cache on needs one key")
        void sizeHasDefault() {
            assertThat(query("excalibur.query.cache.enabled", "true").getCacheMaxEntries())
                .isEqualTo(QueryConfig.DEFAULT_CACHE_MAX_ENTRIES);
        }
    }

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        @DisplayName("both keys are read")
        void bothRead() {
            QueryConfig q = query(
                "excalibur.query.cache.enabled", "true",
                "excalibur.query.cache.max-entries", "64");
            assertThat(q.isCacheEnabled()).isTrue();
            assertThat(q.getCacheMaxEntries()).isEqualTo(64);
        }

        @Test
        @DisplayName("the size may be left on while the cache is off, and is still validated")
        void validatedWhileDisabled() {
            assertThatThrownBy(() -> query("excalibur.query.cache.max-entries", "0"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("bounds")
    class Bounds {

        @Test
        @DisplayName("a size below one is refused rather than corrected")
        void belowMinimum() {
            assertThatThrownBy(() -> query("excalibur.query.cache.max-entries", "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excalibur.query.cache.max-entries")
                .hasMessageContaining("0");
        }

        @Test
        @DisplayName("a size past the ceiling is refused, naming the ceiling")
        void aboveMaximum() {
            assertThatThrownBy(() -> query("excalibur.query.cache.max-entries", "1000001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(QueryConfig.MAX_CACHE_MAX_ENTRIES));
        }

        @Test
        @DisplayName("the bounds themselves are accepted")
        void boundsAccepted() {
            assertThat(query("excalibur.query.cache.max-entries",
                String.valueOf(QueryConfig.MIN_CACHE_MAX_ENTRIES)).getCacheMaxEntries())
                .isEqualTo(QueryConfig.MIN_CACHE_MAX_ENTRIES);
            assertThat(query("excalibur.query.cache.max-entries",
                String.valueOf(QueryConfig.MAX_CACHE_MAX_ENTRIES)).getCacheMaxEntries())
                .isEqualTo(QueryConfig.MAX_CACHE_MAX_ENTRIES);
        }
    }

    @Nested
    @DisplayName("reporting")
    class Reporting {

        @Test
        @DisplayName("both settings appear in toString, so a startup log shows them")
        void inToString() {
            String rendered = query("excalibur.query.cache.enabled", "true",
                "excalibur.query.cache.max-entries", "77").toString();
            assertThat(rendered).contains("cacheEnabled=true").contains("cacheMaxEntries=77");
        }
    }
}
