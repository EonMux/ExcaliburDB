package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import excalibur.query.*;
import excalibur.query.executor.CancellationToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * What QueryEndpoint returns when the query succeeds.
 *
 * <p>Covers the successful response body: the results array, and the coverage object
 * that tells a caller how much of the index the answer actually came from rather than
 * letting a partial result read as a complete one. Also covers passage text, which the
 * endpoint withholds unless {@code include_text} asks for it — and when it is asked
 * for, returns from chunk storage, clips at the configured bound while saying that it
 * clipped, reports null for a chunk storage no longer holds, and refuses outright on a
 * node that keeps no chunk storage instead of silently returning nothing.</p>
 */
@DisplayName("QueryEndpoint successful query")
class QueryEndpointSuccessTest extends QueryEndpointTestBase {

    @Nested
    @DisplayName("Successful query")
    class SuccessfulQuery {

        @Test
        @DisplayName("returns 200 for valid POST request")
        void returns200ForValidPostRequest() throws Exception {
            String json = "{\"query\": \"test\", \"top_k\": 10}";
            HttpExchange exchange = createMockExchange("POST", json);

            QueryResponse mockResponse = createMockResponse();
            CompletableFuture<QueryResponse> future = CompletableFuture.completedFuture(mockResponse);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(future);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
        }

        @Test
        @DisplayName("response includes results array")
        void responseIncludesResultsArray() throws Exception {
            String json = "{\"query\": \"test\", \"top_k\": 10}";
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);

            QueryResponse mockResponse = createMockResponse();
            CompletableFuture<QueryResponse> future = CompletableFuture.completedFuture(mockResponse);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(future);

            endpoint.handle(exchange);

            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"results\":");
        }

        @Test
        @DisplayName("response includes coverage object")
        void responseIncludesCoverageObject() throws Exception {
            String json = "{\"query\": \"test\", \"top_k\": 10}";
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);

            QueryResponse mockResponse = createMockResponse();
            CompletableFuture<QueryResponse> future = CompletableFuture.completedFuture(mockResponse);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(future);

            endpoint.handle(exchange);

            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"coverage\":");
            assertThat(response).contains("\"total_segments\":");
            assertThat(response).contains("\"active_segments\":");
            assertThat(response).contains("\"indexing_segments\":");
            assertThat(response).contains("\"warming_segments\":");
            assertThat(response).contains("\"estimated_corpus_coverage\":");
            assertThat(response).contains("\"tail_scan_entries\":");
            assertThat(response).contains("\"degraded\":");
        }

        @Test
        @DisplayName("coverage says how much of the index the query actually reached")
        void coverageReportsWhatWasSearched() throws Exception {
            // Coverage that only censuses the catalog cannot tell a client whether an empty
            // answer means "searched everything, found nothing" or "was shown nothing". The
            // per-query counts are the only place that distinction lives, so they have to reach
            // the wire.
            String json = "{\"query\": \"test\", \"top_k\": 10}";
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);

            QueryResponse mockResponse = createMockResponse();
            CompletableFuture<QueryResponse> future = CompletableFuture.completedFuture(mockResponse);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(future);

            endpoint.handle(exchange);

            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"compacting_segments\": 0");
            assertThat(response).contains("\"tombstoned_segments\": 0");
            assertThat(response).contains("\"segments_searched\": 7");
            assertThat(response).contains("\"segments_excluded\": 1");
            assertThat(response).contains("\"segments_with_errors\": 0");
            assertThat(response).contains("\"segments_timed_out\": 0");
        }
    }

    @Nested
    @DisplayName("Result text")
    class ResultText {

        /** A corpus of one chunk, keyed the way a result names it. */
        private final java.util.function.Function<String, String> corpus =
            java.util.Map.of("docs:guide.md:chunk:0", "the passage itself")::get;

        private QueryEndpoint endpointOverCorpus(int maxChars) {
            QueryEndpoint over = new QueryEndpoint(queryPipeline, null, null, null,
                embeddingClient, excalibur.api.security.ApiSecurityConfig.builder()
                    .maxResultTextChars(maxChars).build());
            over.setChunkText(corpus);
            return over;
        }

        private QueryResponse oneHit() {
            return new QueryResponse(
                java.util.List.of(new QueryResultItem(
                    "docs:guide.md:chunk:0", 0.9f, null, 3L, 7L)),
                IndexCoverage.builder().totalSegments(1).activeSegments(1)
                    .estimatedCorpusCoverage(1.0).build(),
                12L, false, false);
        }

        private String answer(QueryEndpoint target, String requestJson) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", requestJson, body);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(oneHit()));
            target.handle(exchange);
            return body.toString(StandardCharsets.UTF_8);
        }

        /**
         * The identifier-only answer is what every existing client parses. Adding text to it
         * unasked would double the size of every response those clients already handle, for a
         * field none of them read.
         */
        @Test
        @DisplayName("omits text unless the request asks for it")
        void omitsTextByDefault() throws IOException {
            String json = answer(endpointOverCorpus(4096), "{\"query\": \"log4j\"}");

            assertThat(json).doesNotContain("\"text\"");
            assertThat(json).contains("docs:guide.md:chunk:0");
        }

        /**
         * Without this a search is only half an answer: the caller is handed identifiers it can
         * resolve only against the store the corpus was ingested from, which on a crawled corpus
         * it does not have.
         */
        @Test
        @DisplayName("returns the stored passage when the request asks for it")
        void returnsTextWhenAsked() throws IOException {
            String json = answer(endpointOverCorpus(4096),
                "{\"query\": \"log4j\", \"include_text\": true}");

            assertThat(json).contains("\"text\": \"the passage itself\"");
            assertThat(json)
                .as("a caller has to be able to tell a whole chunk from a clipped one")
                .contains("\"text_truncated\": false");
        }

        @Test
        @DisplayName("clips a passage past the bound and says that it did")
        void clipsPastTheBound() throws IOException {
            String json = answer(endpointOverCorpus(3),
                "{\"query\": \"log4j\", \"include_text\": true}");

            assertThat(json).contains("\"text\": \"the\"");
            assertThat(json).contains("\"text_truncated\": true");
        }

        /**
         * A result the index still ranks whose text storage no longer holds. Null is the honest
         * answer, and the identifier and score are still worth returning.
         */
        @Test
        @DisplayName("reports null text for a chunk storage no longer holds")
        void nullTextForAMissingChunk() throws IOException {
            QueryEndpoint over = new QueryEndpoint(queryPipeline, null, null, null,
                embeddingClient, excalibur.api.security.ApiSecurityConfig.builder().build());
            over.setChunkText(id -> null);

            String json = answer(over, "{\"query\": \"log4j\", \"include_text\": true}");

            assertThat(json).contains("\"text\": null");
        }

        /**
         * Silently ranking without the text the caller asked for would have it quote passages it
         * never received. A node with no chunk storage cannot honour the field, and says so.
         */
        @Test
        @DisplayName("refuses include_text on a node that keeps no chunk storage, and says why")
        void refusesTextWhenNoStorage() throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"log4j\", \"include_text\": true}", body);

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
            assertThat(body.toString(StandardCharsets.UTF_8)).contains("include_text");
        }
    }
}
