package com.eonmux.excalibur.api.endpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which request fields an answer names as accepted and not acted on.
 *
 * <p>Every route and every scoring outcome is a separate question, because the same request can
 * be answered by any of them depending on what the node holds and what failed on the way.</p>
 */
@DisplayName("IgnoredFields")
class IgnoredFieldsTest {

    private static final String EVERYTHING = "{\"query\": \"q\", \"threshold\": 0.5, "
        + "\"include_partial_segments\": false, \"enable_llm_scoring\": true, "
        + "\"llm_threshold\": 0.7, \"oversampling_factor\": 3}";

    private static QueryRequestParser.ParsedQuery parsed(boolean enableLlmScoring) {
        return new QueryRequestParser.ParsedQuery("q", 10, 0.5f, 1_000L, false,
            enableLlmScoring, 0.7f, 3, false,
            com.eonmux.excalibur.core.text.SourceScope.ALL);
    }

    @Test
    @DisplayName("a scored vector answer acted on every field it was sent")
    void scoredVectorIgnoresNothing() {
        assertThat(IgnoredFields.of(EVERYTHING, parsed(true), false, true)).isEmpty();
    }

    @Test
    @DisplayName("a vector answer with no scoring planned names the scoring fields, and not "
        + "oversampling_factor, which still widened the graph search")
    void unscoredVectorNamesScoringFields() {
        assertThat(IgnoredFields.of(EVERYTHING, parsed(true), false, false))
            .containsExactly("enable_llm_scoring", "llm_threshold");
    }

    @Test
    @DisplayName("a scored BM25 answer names only what BM25 cannot apply")
    void scoredLexicalNamesVectorFields() {
        assertThat(IgnoredFields.of(EVERYTHING, parsed(true), true, true))
            .containsExactly("threshold", "include_partial_segments");
    }

    @Test
    @DisplayName("an unscored BM25 answer names every field it did not act on, in a stable "
        + "order")
    void unscoredLexicalNamesEverything() {
        assertThat(IgnoredFields.of(EVERYTHING, parsed(true), true, false))
            .containsExactly("threshold", "include_partial_segments", "enable_llm_scoring",
                "llm_threshold", "oversampling_factor");
    }

    @Test
    @DisplayName("enable_llm_scoring sent as false asked for no scoring and got none, so it is "
        + "not named")
    void disabledScoringIsHonoured() {
        String body = "{\"query\": \"q\", \"enable_llm_scoring\": false}";

        assertThat(IgnoredFields.of(body, parsed(false), true, false)).isEmpty();
    }

    @Test
    @DisplayName("a field the caller did not send is never named")
    void onlyWhatWasSent() {
        assertThat(IgnoredFields.of("{\"query\": \"q\"}", parsed(true), true, false)).isEmpty();
    }
}
