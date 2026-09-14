package excalibur.api.endpoint;

import excalibur.api.retrieval.ChunkTextResolver;
import excalibur.api.retrieval.HybridFusion;
import excalibur.api.retrieval.LexicalQueryService;
import excalibur.query.IndexCoverage;
import excalibur.query.QueryResponse;
import excalibur.query.QueryResultItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Text on a result, across all three renderers.
 *
 * <p>The three retrieval paths render separately, so text present on one of them is not evidence
 * about the other two - and a caller that asked for text and got it from the vector path but not
 * from the fused one has a field whose presence depends on which signals happened to answer.</p>
 */
@DisplayName("QueryResponseJson result text")
class QueryResponseJsonTextTest {

    private static final ChunkTextResolver TEXT = ChunkTextResolver.over(
        Map.of("a", "alpha", "b", "beta")::get, 4096);

    private static final IndexCoverage COVERAGE = IndexCoverage.builder()
        .totalSegments(1).activeSegments(1).estimatedCorpusCoverage(1.0).build();

    private static QueryResponse response() {
        return new QueryResponse(
            List.of(new QueryResultItem("a", 0.9f, null, 3L, 7L)), COVERAGE, 1L, false, false);
    }

    @Test
    @DisplayName("the vector renderer carries text when a resolver is supplied")
    void vectorCarriesText() {
        assertThat(QueryResponseJson.vector(response(), TEXT))
            .contains("\"text\": \"alpha\"")
            .contains("\"text_truncated\": false");
    }

    @Test
    @DisplayName("the fused renderer carries text, after the signals that produced the result")
    void hybridCarriesText() {
        String json = QueryResponseJson.hybrid(response(),
            List.of(new HybridFusion.Result("b", 0.03, 3L, List.of("vector", "lexical"))), TEXT);

        assertThat(json).contains("\"text\": \"beta\"");
        assertThat(json.indexOf("\"signals\""))
            .as("signals stay where they were; text is appended after them")
            .isLessThan(json.indexOf("\"text\""));
    }

    @Test
    @DisplayName("the lexical renderer carries text")
    void lexicalCarriesText() {
        assertThat(QueryResponseJson.lexical("q", List.of(new LexicalQueryService.Hit("a", 1.5)),
                COVERAGE, "{}", List.of(), false, false, 1L, TEXT))
            .contains("\"text\": \"alpha\"");
    }

    @Test
    @DisplayName("a result the corpus no longer holds carries null text on every renderer")
    void missingTextIsNullEverywhere() {
        QueryResponse missing = new QueryResponse(
            List.of(new QueryResultItem("gone", 0.9f, null, 3L, 7L)), COVERAGE, 1L, false, false);

        assertThat(QueryResponseJson.vector(missing, TEXT)).contains("\"text\": null");
        assertThat(QueryResponseJson.hybrid(missing,
            List.of(new HybridFusion.Result("gone", 0.03, null, List.of("lexical"))), TEXT))
            .contains("\"text\": null");
        assertThat(QueryResponseJson.lexical("q", List.of(new LexicalQueryService.Hit("gone", 1.5)),
            COVERAGE, "{}", List.of(), false, false, 1L, TEXT)).contains("\"text\": null");
    }

    @Test
    @DisplayName("no renderer emits a text field when none was asked for")
    void noTextWhenNoResolver() {
        assertThat(QueryResponseJson.vector(response())).doesNotContain("\"text\"");
        assertThat(QueryResponseJson.hybrid(response(),
            List.of(new HybridFusion.Result("a", 0.03, 3L, List.of("vector"))), null))
            .doesNotContain("\"text\"");
        assertThat(QueryResponseJson.lexical("q", List.of(new LexicalQueryService.Hit("a", 1.5)),
            COVERAGE, "{}", List.of(), false, false, 1L, null)).doesNotContain("\"text\"");
    }

    /**
     * Text is rendered into a JSON string literal, so a passage containing a quote or a newline
     * has to be escaped there as everywhere else - an unescaped one makes the whole response
     * unparseable, which turns a good answer into a client-side parse failure.
     */
    @Test
    @DisplayName("text carrying quotes and newlines is escaped")
    void textIsEscaped() {
        ChunkTextResolver awkward = ChunkTextResolver.over(
            Map.of("a", "he said \"hi\"\nthen left")::get, 4096);

        assertThat(QueryResponseJson.vector(response(), awkward))
            .contains("\\\"hi\\\"")
            .contains("\\n");
    }
}
