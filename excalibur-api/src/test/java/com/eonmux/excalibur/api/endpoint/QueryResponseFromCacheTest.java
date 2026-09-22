package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.IndexCoverage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether an answer says it was served from the query result cache.
 *
 * <p>The field is on every answer rather than only on a hit. A caller cannot tell an
 * uncached answer from a cached one by the absence of a field, and a field that appears
 * only when something is true is a field nobody looks for - which is the same rule
 * {@code ignored_fields} already follows. It also settles the question a cached answer
 * otherwise raises silently: {@code query_time_ms} on a hit is the time this request took,
 * not the time the search took, and without {@code from_cache} beside it a monitoring
 * dashboard reads a cache hit as a search that got suddenly faster.</p>
 */
@DisplayName("from_cache on a query answer")
class QueryResponseFromCacheTest {

    /** Coverage of a search that reached everything. */
    private static final IndexCoverage FULL = IndexCoverage.builder().build();

    /**
     * Builds a response.
     *
     * @param fromCache Whether it was served from cache
     * @return The response
     */
    private static QueryResponse response(boolean fromCache) {
        return new QueryResponse("q-1", List.<QueryResultItem>of(), FULL, 4L, fromCache, false);
    }

    /** An answer carrying no ranking page and no explanation. */
    private static QueryAnswer plainAnswer() {
        return new QueryAnswer(null, new ResultPage(0, 10, 0, null),
            new ResultTotal(0, ResultTotal.EXACT), null);
    }

    @Nested
    @DisplayName("the vector path")
    class Vector {

        @Test
        @DisplayName("says so when the answer came from cache")
        void hit() {
            assertThat(QueryResponseJson.vector(response(true), List.of(), plainAnswer()))
                .contains("\"from_cache\": true");
        }

        @Test
        @DisplayName("says so when it did not, rather than omitting the field")
        void miss() {
            assertThat(QueryResponseJson.vector(response(false), List.of(), plainAnswer()))
                .contains("\"from_cache\": false");
        }
    }

    @Nested
    @DisplayName("the hybrid path")
    class Hybrid {

        @Test
        @DisplayName("reports the vector half's cache status")
        void hit() {
            assertThat(QueryResponseJson.hybrid(
                    HybridResponse.unscored(response(true), List.of(), List.of(), 10),
                    List.of(), plainAnswer()))
                .contains("\"from_cache\": true");
        }
    }

    @Nested
    @DisplayName("the lexical path")
    class Lexical {

        private LexicalResponse lexical(boolean fromCache) {
            return new LexicalResponse("q-1", List.of(), true, FULL, false, false, false, false,
                3L, fromCache);
        }

        @Test
        @DisplayName("says so when the answer came from cache")
        void hit() {
            assertThat(QueryResponseJson.lexical(lexical(true), List.of(), plainAnswer()))
                .contains("\"from_cache\": true");
        }

        @Test
        @DisplayName("says so when it did not, rather than omitting the field")
        void miss() {
            assertThat(QueryResponseJson.lexical(lexical(false), List.of(), plainAnswer()))
                .contains("\"from_cache\": false");
        }
    }
}
