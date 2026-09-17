package excalibur.query.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The key has to name every input that can change the answer.
 *
 * <p>A key that omits one is not a smaller key, it is a wrong one: two requests that differ
 * only in the omitted field collide, and the second caller is handed an answer to the first
 * caller's question with nothing in the response saying so.</p>
 */
@DisplayName("QueryCacheKey")
class QueryCacheKeyTest {

    private static QueryCacheKey.Builder base() {
        return QueryCacheKey.builder()
            .namespace("default")
            .securityContext("token-a")
            .retrievalMode("hybrid")
            .queryText("how does compaction work")
            .topK(10)
            .offset(0)
            .threshold(0.0f)
            .includePartialSegments(true)
            .enableLlmScoring(true)
            .llmThreshold(0.3f)
            .oversamplingFactor(2)
            .includeText(false)
            .snippet(false)
            .snippetChars(320)
            .explain(false);
    }

    @Test
    @DisplayName("two identical requests produce the same key")
    void identicalRequestsShareAKey() {
        assertThat(base().build()).isEqualTo(base().build());
        assertThat(base().build()).hasSameHashCodeAs(base().build());
    }

    @Test
    @DisplayName("requests differing only in offset do not share a key")
    void offsetSeparatesKeys() {
        assertThat(base().offset(10).build()).isNotEqualTo(base().offset(0).build());
    }

    @Test
    @DisplayName("requests differing only in explain do not share a key")
    void explainSeparatesKeys() {
        assertThat(base().explain(true).build()).isNotEqualTo(base().explain(false).build());
    }

    @Test
    @DisplayName("requests differing only in snippet_chars do not share a key")
    void snippetCharsSeparatesKeys() {
        QueryCacheKey narrow = base().snippet(true).includeText(true).snippetChars(64).build();
        QueryCacheKey wide = base().snippet(true).includeText(true).snippetChars(512).build();
        assertThat(narrow).isNotEqualTo(wide);
    }

    @Test
    @DisplayName("requests differing only in namespace do not share a key")
    void namespaceSeparatesKeys() {
        assertThat(base().namespace("tenant-a").build())
            .isNotEqualTo(base().namespace("tenant-b").build());
    }

    @Test
    @DisplayName("requests differing only in security context do not share a key")
    void securityContextSeparatesKeys() {
        assertThat(base().securityContext("token-a").build())
            .isNotEqualTo(base().securityContext("token-b").build());
    }

    @Test
    @DisplayName("requests differing only in retrieval mode do not share a key")
    void retrievalModeSeparatesKeys() {
        assertThat(base().retrievalMode("vector").build())
            .isNotEqualTo(base().retrievalMode("hybrid").build());
    }

    @Test
    @DisplayName("every remaining search input separates keys")
    void everySearchInputSeparatesKeys() {
        QueryCacheKey reference = base().build();
        assertThat(base().queryText("something else").build()).isNotEqualTo(reference);
        assertThat(base().topK(11).build()).isNotEqualTo(reference);
        assertThat(base().threshold(0.5f).build()).isNotEqualTo(reference);
        assertThat(base().includePartialSegments(false).build()).isNotEqualTo(reference);
        assertThat(base().enableLlmScoring(false).build()).isNotEqualTo(reference);
        assertThat(base().llmThreshold(0.9f).build()).isNotEqualTo(reference);
        assertThat(base().oversamplingFactor(4).build()).isNotEqualTo(reference);
        assertThat(base().includeText(true).build()).isNotEqualTo(reference);
        assertThat(base().snippet(true).includeText(true).build()).isNotEqualTo(reference);
    }

    @Test
    @DisplayName("a key round-trips through toBuilder unchanged")
    void toBuilderRoundTrips() {
        QueryCacheKey key = base().build();
        assertThat(key.toBuilder().build()).isEqualTo(key);
    }

    @Test
    @DisplayName("an absent namespace or security context is normalised rather than null")
    void absentScopeIsNormalised() {
        QueryCacheKey key = base().namespace(null).securityContext(null).build();
        assertThat(key.namespace()).isEmpty();
        assertThat(key.securityContext()).isEmpty();
    }

    @Test
    @DisplayName("query text is required")
    void queryTextIsRequired() {
        assertThatThrownBy(() -> base().queryText(null).build())
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("nonsensical bounds are refused rather than cached under")
    void nonsensicalBoundsAreRefused() {
        assertThatThrownBy(() -> base().topK(0).build())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().offset(-1).build())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().oversamplingFactor(0).build())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().snippetChars(0).build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the key does not carry the query text in its toString")
    void toStringDoesNotLeakTheQuery() {
        assertThat(base().queryText("patient records for jane doe").build().toString())
            .doesNotContain("jane doe");
    }
}
