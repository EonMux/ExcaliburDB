package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * That a node which cannot serve says so with a status code a probe can act on.
 *
 * <p>{@code /health} answers 200 unconditionally - on its degraded path too - and derives
 * {@code ready} from coverage alone, which counts indexing, warming and compacting segments as
 * queryable and therefore reads 1.0000 the moment the catalog is recovered. The project's own
 * troubleshooting table records the consequence: queries return nothing on a fresh node while
 * coverage reads 1.0000 and {@code /health} reports ready and healthy. The documentation's
 * answer was to tell operators to read the field rather than the status code, which no
 * standard Kubernetes {@code httpGet} probe and no cloud load balancer health check can do.</p>
 */
@DisplayName("Readiness")
class ReadinessEndpointTest {

    private ServiceReadiness readiness;
    private final AtomicReference<Double> coverage = new AtomicReference<>(1.0);
    private final AtomicBoolean indexerRunning = new AtomicBoolean(true);
    private final AtomicReference<Double> freeDisk = new AtomicReference<>(1.0);

    @BeforeEach
    void setUp() {
        readiness = new ServiceReadiness();
    }

    private ReadinessEndpoint endpoint() {
        return new ReadinessEndpoint(readiness, coverage::get, indexerRunning::get,
            freeDisk::get, 0.5, 0.05);
    }

    @Nested
    @DisplayName("Status code")
    class StatusCode {

        @Test
        @DisplayName("answers 200 when the node can serve")
        void readyIsTwoHundred() throws IOException {
            assertThat(status()).isEqualTo(200);
        }

        @Test
        @DisplayName("answers 503 when an operator has drained the node")
        void drainedIsServiceUnavailable() throws IOException {
            readiness.beginDrain("rolling restart");
            assertThat(status()).isEqualTo(503);
        }

        @Test
        @DisplayName("answers 503 when too little of the corpus is queryable")
        void lowCoverageIsServiceUnavailable() throws IOException {
            coverage.set(0.1);
            assertThat(status()).isEqualTo(503);
        }

        @Test
        @DisplayName("answers 503 when the worker that drains the HNSW queue has died")
        void deadIndexerIsServiceUnavailable() throws IOException {
            // Coverage stays at 1.0 and /health stays healthy through this, which is the
            // whole reason readiness cannot be derived from coverage.
            indexerRunning.set(false);
            assertThat(status()).isEqualTo(503);
        }

        @Test
        @DisplayName("answers 503 when the volumes are nearly full")
        void diskPressureIsServiceUnavailable() throws IOException {
            // Measured since the volume manager was written and reported on /v1/stats, but it
            // never reached readiness: a node that cannot allocate a replacement segment
            // cannot compact, cannot reindex and will start failing writes.
            freeDisk.set(0.01);
            assertThat(status()).isEqualTo(503);
        }
    }

    @Nested
    @DisplayName("Body")
    class Body {

        @Test
        @DisplayName("names every check that failed, so the 503 is actionable")
        void namesTheFailedChecks() throws IOException {
            readiness.beginDrain("maintenance");
            indexerRunning.set(false);

            String body = body();
            assertThat(body).contains("\"ready\": false");
            assertThat(body).contains("\"drained\"");
            assertThat(body).contains("\"indexer\"");
            assertThat(body).doesNotContain("\"disk\"");
        }

        @Test
        @DisplayName("names nothing when the node can serve")
        void namesNothingWhenReady() throws IOException {
            assertThat(body()).contains("\"ready\": true").contains("\"failed\": []");
        }

        @Test
        @DisplayName("reports the measurements each verdict was reached from")
        void reportsTheMeasurements() throws IOException {
            coverage.set(0.75);
            freeDisk.set(0.25);

            String body = body();
            assertThat(body).contains("\"coverage\": 0.7500");
            assertThat(body).contains("\"diskAvailableRatio\": 0.2500");
            assertThat(body).contains("\"indexerRunning\": true");
            assertThat(body).contains("\"draining\": false");
        }
    }

    @Test
    @DisplayName("refuses a method a probe would not use")
    void refusesOtherMethods() throws IOException {
        assertThat(statusOf("POST")).isEqualTo(405);
    }

    @Test
    @DisplayName("reports not ready rather than failing when a check cannot be read")
    void aBrokenCheckIsNotReady() throws IOException {
        ReadinessEndpoint endpoint = new ReadinessEndpoint(readiness,
            () -> {
                throw new IllegalStateException("catalog closed");
            },
            indexerRunning::get, freeDisk::get, 0.5, 0.05);

        // A node whose own state cannot be read is precisely a node that should be taken out
        // of rotation, so the failure becomes a 503 rather than a 500 a probe treats as
        // "endpoint broken, assume healthy".
        HttpExchange exchange = exchange("GET");
        endpoint.handle(exchange);
        assertThat(statusFrom(exchange)).isEqualTo(503);
    }

    @Test
    @DisplayName("refuses thresholds outside the range they are compared against")
    void refusesNonsenseThresholds() {
        assertThatThrownBy(() -> new ReadinessEndpoint(readiness, coverage::get,
            indexerRunning::get, freeDisk::get, 1.5, 0.05))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadinessEndpoint(readiness, coverage::get,
            indexerRunning::get, freeDisk::get, 0.5, -0.1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private int status() throws IOException {
        return statusOf("GET");
    }

    private int statusOf(String method) throws IOException {
        HttpExchange exchange = exchange(method);
        endpoint().handle(exchange);
        return statusFrom(exchange);
    }

    private String body() throws IOException {
        HttpExchange exchange = exchange("GET");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(out);
        endpoint().handle(exchange);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static HttpExchange exchange(String method) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create("/health/ready"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
        return exchange;
    }

    private static int statusFrom(HttpExchange exchange) throws IOException {
        var status = forClass(Integer.class);
        verify(exchange).sendResponseHeaders(status.capture(), org.mockito.ArgumentMatchers.anyLong());
        return status.getValue();
    }
}
