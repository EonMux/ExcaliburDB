package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import excalibur.query.*;
import excalibur.query.executor.CancellationToken;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * What QueryEndpoint returns when the query does not succeed.
 *
 * <p>Covers the two ways a query ends badly and the contract for each. A timeout still
 * answers with what the index holds rather than an empty result that reads as "nothing
 * matched", charges the pipeline the time that is left rather than the time the client
 * asked for, and waits the pipeline's deadline out so a partial answer has a chance to
 * arrive. A failure answers 500, a withdrawn query answers 200 marked cancelled rather
 * than as an empty match, and neither leaks internal failure detail to the client.</p>
 */
@DisplayName("QueryEndpoint failures")
class QueryEndpointFailureTest extends QueryEndpointTestBase {

    @Nested
    @DisplayName("Timeout handling")
    class TimeoutHandling {

        @Test
        @DisplayName("returns timeout response when query times out")
        void returnsTimeoutResponseWhenQueryTimesOut() throws Exception {
            String json = "{\"query\": \"test\", \"timeout_ms\": 100}";
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);

            // Create a future that never completes
            CompletableFuture<QueryResponse> neverCompletingFuture = new CompletableFuture<>();
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(neverCompletingFuture);

            endpoint.handle(exchange);

            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"timed_out\": true");
        }

        @Test
        @DisplayName("a timeout response says what the index holds rather than implying it is empty")
        void aTimeoutResponseStillSaysWhatTheIndexHolds() throws Exception {
            // A response reporting no segments at all, because the query never got to look, is
            // indistinguishable from one served by an empty index.
            when(queryPipeline.segmentCensus()).thenReturn(
                IndexCoverage.builder().totalSegments(7).activeSegments(7));
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"test\", \"timeout_ms\": 100}", responseBody);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(new CompletableFuture<>());

            endpoint.handle(exchange);

            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"timed_out\": true");
            assertThat(response).contains("\"total_segments\": 7");
            assertThat(response)
                .as("nothing was read, whatever the index holds")
                .contains("\"estimated_corpus_coverage\": 0.0000");
        }

        @Test
        @DisplayName("the pipeline is charged the time that is left, not the time the client asked for")
        void thePipelineIsChargedWhatIsLeft() throws Exception {
            // Embedding is paid for out of the request's budget. Handing the pipeline the
            // client's full timeout afterwards gives it a deadline this handler has already
            // decided not to wait for.
            when(embeddingClient.embed(anyString(), anyString())).thenAnswer(invocation -> {
                Thread.sleep(300);
                return new float[]{0.1f, 0.2f, 0.3f};
            });
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"test\", \"timeout_ms\": 1000}", new ByteArrayOutputStream());
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(CompletableFuture.completedFuture(createMockResponse()));

            endpoint.handle(exchange);

            ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
            verify(queryPipeline).execute(request.capture(), any(CancellationToken.class));
            assertThat(request.getValue().timeoutMs())
                .as("the embedding call has already spent part of the budget")
                .isLessThan(1000)
                .isGreaterThan(0);
        }

        @Test
        @DisplayName("the deadline the pipeline was given is waited out, so its partial answer arrives")
        void theDeadlineGivenIsTheDeadlineWaited() throws Exception {
            // The pipeline answers a timeout with the results it already has. Abandoning it
            // before its own deadline replaces that answer with an empty one.
            when(embeddingClient.embed(anyString(), anyString())).thenAnswer(invocation -> {
                Thread.sleep(300);
                return new float[]{0.1f, 0.2f, 0.3f};
            });
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"test\", \"timeout_ms\": 1000}", new ByteArrayOutputStream());
            AtomicLong dispatchedAt = new AtomicLong();
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenAnswer(invocation -> {
                    dispatchedAt.set(System.nanoTime());
                    return new CompletableFuture<QueryResponse>();
                });

            endpoint.handle(exchange);
            long waitedMs = (System.nanoTime() - dispatchedAt.get()) / 1_000_000;

            ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
            verify(queryPipeline).execute(request.capture(), any(CancellationToken.class));
            assertThat(waitedMs)
                .as("giving the pipeline a deadline and then not waiting for it discards its answer")
                .isGreaterThan(request.getValue().timeoutMs());
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("returns 500 when the pipeline fails")
        void returns500ForInternalErrors() throws Exception {
            String json = "{\"query\": \"test\", \"top_k\": 10}";
            HttpExchange exchange = createMockExchange("POST", json);

            CompletableFuture<QueryResponse> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new ExecutionException(new RuntimeException("Internal error")));
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(failedFuture);

            endpoint.handle(exchange);

            // A failed query must not be reported as 200 with an empty result list:
            // that is indistinguishable from "nothing matched", so a caller retries
            // nothing and silently loses results.
            verify(exchange).sendResponseHeaders(eq(500), anyLong());
        }

        @Test
        @DisplayName("answers a withdrawn query 200 with cancelled, not an empty match")
        void reportsACancelledQuery() throws Exception {
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody(
                "POST", "{\"query\": \"test\", \"top_k\": 10}", responseBody);

            CompletableFuture<QueryResponse> withdrawn = new CompletableFuture<>();
            withdrawn.cancel(true);
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(withdrawn);

            endpoint.handle(exchange);

            // 200 with nothing found and 200 with the request withdrawn are different answers.
            // Without `cancelled` the caller reads an empty result list as a corpus that holds
            // nothing matching, and stops looking.
            verify(exchange).sendResponseHeaders(eq(200), anyLong());
            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"cancelled\": true");
            assertThat(response).contains("\"timed_out\": false");
            assertThat(response)
                .as("a query that was withdrawn covered none of the corpus and says so")
                .contains("\"degraded\": true");
        }

        @Test
        @DisplayName("does not leak internal failure detail to the client")
        void doesNotLeakFailureDetail() throws Exception {
            String json = "{\"query\": \"test\", \"top_k\": 10}";
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = createMockExchangeWithBody("POST", json, responseBody);

            CompletableFuture<QueryResponse> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new ExecutionException(
                new IllegalStateException("/var/lib/excalibur/data/segment-7 is corrupt")));
            when(queryPipeline.execute(any(QueryRequest.class), any(CancellationToken.class)))
                .thenReturn(failedFuture);

            endpoint.handle(exchange);

            String response = responseBody.toString(StandardCharsets.UTF_8);
            assertThat(response).doesNotContain("/var/lib/excalibur");
            assertThat(response).contains("Query execution failed");
        }
    }
}
