package excalibur.api.endpoint;

import excalibur.api.retrieval.HybridFusion;
import excalibur.api.retrieval.LexicalQueryService;
import excalibur.query.IndexCoverage;
import excalibur.query.QueryResponse;
import excalibur.query.QueryResultItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

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

    private static final Function<String, String> CORPUS =
        Map.of("a", "alpha", "b", "beta")::get;

    private static final IndexCoverage COVERAGE = IndexCoverage.builder()
        .totalSegments(1).activeSegments(1).estimatedCorpusCoverage(1.0).build();

    /** An answer that carries the text of every result, read from a corpus. */
    private static QueryAnswer withText(Function<String, String> corpus) {
        QuerySearchOptions options = new QuerySearchOptions(0, false, false, 320);
        return new QueryAnswer(
            ResultPassages.forRequest(corpus, 4096, true, options, List.of()),
            ResultPage.of(0, 10, 1), ResultTotal.of(1, 10, true), null);
    }

    /** An answer from a request that asked for no text. */
    private static QueryAnswer withoutText() {
        return new QueryAnswer(null, ResultPage.of(0, 10, 1), ResultTotal.of(1, 10, true), null);
    }

    private static QueryResponse response() {
        return new QueryResponse(
            List.of(new QueryResultItem("a", 0.9f, null, 3L, 7L)), COVERAGE, 1L, false, false);
    }

    @Test
    @DisplayName("the vector renderer carries text when a resolver is supplied")
    void vectorCarriesText() {
        QueryResponse response = response();
        assertThat(QueryResponseJson.vector(response, response.results(), withText(CORPUS)))
            .contains("\"text\": \"alpha\"")
            .contains("\"text_truncated\": false");
    }

    @Test
    @DisplayName("the fused renderer carries text, after the signals that produced the result")
    void hybridCarriesText() {
        String json = QueryResponseJson.hybrid(response(),
            List.of(new HybridFusion.Result("b", 0.03, 3L, List.of("vector", "lexical"))),
            withText(CORPUS));

        assertThat(json).contains("\"text\": \"beta\"");
        assertThat(json.indexOf("\"signals\""))
            .as("signals stay where they were; text is appended after them")
            .isLessThan(json.indexOf("\"text\""));
    }

    @Test
    @DisplayName("the lexical renderer carries text")
    void lexicalCarriesText() {
        assertThat(QueryResponseJson.lexical("q", List.of(new LexicalQueryService.Hit("a", 1.5)),
                COVERAGE, List.of(), false, false, 1L, withText(CORPUS)))
            .contains("\"text\": \"alpha\"");
    }

    @Test
    @DisplayName("a result the corpus no longer holds carries null text on every renderer")
    void missingTextIsNullEverywhere() {
        QueryResponse missing = new QueryResponse(
            List.of(new QueryResultItem("gone", 0.9f, null, 3L, 7L)), COVERAGE, 1L, false, false);

        assertThat(QueryResponseJson.vector(missing, missing.results(), withText(CORPUS)))
            .contains("\"text\": null");
        assertThat(QueryResponseJson.hybrid(missing,
            List.of(new HybridFusion.Result("gone", 0.03, null, List.of("lexical"))),
            withText(CORPUS)))
            .contains("\"text\": null");
        assertThat(QueryResponseJson.lexical("q", List.of(new LexicalQueryService.Hit("gone", 1.5)),
            COVERAGE, List.of(), false, false, 1L, withText(CORPUS))).contains("\"text\": null");
    }

    @Test
    @DisplayName("no renderer emits a text field when none was asked for")
    void noTextWhenNoResolver() {
        QueryResponse response = response();
        assertThat(QueryResponseJson.vector(response, response.results(), withoutText()))
            .doesNotContain("\"text\"");
        assertThat(QueryResponseJson.hybrid(response,
            List.of(new HybridFusion.Result("a", 0.03, 3L, List.of("vector"))), withoutText()))
            .doesNotContain("\"text\"");
        assertThat(QueryResponseJson.lexical("q", List.of(new LexicalQueryService.Hit("a", 1.5)),
            COVERAGE, List.of(), false, false, 1L, withoutText())).doesNotContain("\"text\"");
    }

    /**
     * Text is rendered into a JSON string literal, so a passage containing a quote or a newline
     * has to be escaped there as everywhere else - an unescaped one makes the whole response
     * unparseable, which turns a good answer into a client-side parse failure.
     */
    @Test
    @DisplayName("text carrying quotes and newlines is escaped")
    void textIsEscaped() {
        Function<String, String> awkward = Map.of("a", "he said \"hi\"\nthen left")::get;
        QueryResponse response = response();

        assertThat(QueryResponseJson.vector(response, response.results(), withText(awkward)))
            .contains("\\\"hi\\\"")
            .contains("\\n");
    }

    /**
     * The page and the total are not opt-in on any path. A client that has to test for them
     * before it can say "showing 1-10 of 240" cannot use them for arithmetic at all.
     */
    @Test
    @DisplayName("every renderer carries the page and the total")
    void everyRendererCarriesPageAndTotal() {
        QueryResponse response = response();

        assertThat(QueryResponseJson.vector(response, response.results(), withoutText()))
            .contains("\"page\": {").contains("\"result_total\": {");
        assertThat(QueryResponseJson.hybrid(response,
            List.of(new HybridFusion.Result("a", 0.03, 3L, List.of("vector"))), withoutText()))
            .contains("\"page\": {").contains("\"result_total\": {");
        assertThat(QueryResponseJson.lexical("q", List.of(new LexicalQueryService.Hit("a", 1.5)),
            COVERAGE, List.of(), false, false, 1L, withoutText()))
            .contains("\"page\": {").contains("\"result_total\": {");
    }
}
