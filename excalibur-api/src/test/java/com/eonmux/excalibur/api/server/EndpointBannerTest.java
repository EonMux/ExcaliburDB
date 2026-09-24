package com.eonmux.excalibur.api.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the list of routes the node prints at startup is the list it actually serves.
 *
 * <p>The banner is the only place an operator is told what this node answers, and it had drifted:
 * four registered routes were missing from it, so a deployment that had erasure or a graph
 * configured was told it did not. A banner that under-reports is worse than none, because it
 * reads as an inventory.</p>
 */
@DisplayName("Startup endpoint banner")
class EndpointBannerTest {

    @Nested
    @DisplayName("On a node with nothing optional configured")
    class Minimal {

        private final List<String> lines = EndpointBanner.lines(routes());

        @Test
        @DisplayName("names every route that is always registered")
        void namesTheAlwaysOnRoutes() {
            assertThat(lines).anyMatch(line -> line.contains("GET") && line.contains("/health"));
            assertThat(lines).anyMatch(line -> line.contains("/v1/stats"));
            assertThat(lines).anyMatch(line -> line.contains("POST") && line.contains("/v1/query"));
            // The cancel route needs the query id in the path: the bare path is a 400. A
            // banner naming a path the server refuses sends an operator to the wrong URL.
            assertThat(lines).anyMatch(
                line -> line.contains("DELETE") && line.contains("/v1/query/{query_id}"));
            assertThat(lines).anyMatch(line -> line.contains("POST") && line.contains("/v1/ingest "));
            assertThat(lines).anyMatch(line -> line.contains("DELETE") && line.contains("/v1/ingest "));
            assertThat(lines).anyMatch(line -> line.contains("/v1/reindex "));
            assertThat(lines).anyMatch(line -> line.contains("/v1/ingest/status"));
            assertThat(lines).anyMatch(line -> line.contains("/v1/reindex/status"));
            assertThat(lines).anyMatch(line -> line.contains("GET") && line.contains("/v1/documents "));
            assertThat(lines).anyMatch(line -> line.contains("/v1/documents/fetch"));
            assertThat(lines).anyMatch(line -> line.contains("POST") && line.contains("/v1/compact "));
            assertThat(lines).anyMatch(line -> line.contains("/v1/compact/status"));
        }

        /**
         * The observability surface. A route an operator is never told about is a route
         * nobody configures a scraper or a probe against, so each of these has to be here
         * for the same reason the rest are.
         */
        @Test
        @DisplayName("names the routes a monitoring system and a probe are pointed at")
        void namesTheObservabilityRoutes() {
            assertThat(lines).anyMatch(line -> line.contains("GET") && line.contains("/metrics"));
            assertThat(lines).anyMatch(
                line -> line.contains("GET") && line.contains("/health/ready"));
            assertThat(lines).anyMatch(line -> line.contains("GET") && line.contains("/v1/version"));
            assertThat(lines).anyMatch(
                line -> line.contains("GET") && line.contains("/v1/query/status"));
            assertThat(lines).anyMatch(line -> line.contains("POST") && line.contains("/v1/drain"));
            assertThat(lines).anyMatch(
                line -> line.contains("DELETE") && line.contains("/v1/drain"));
        }

        /**
         * The route is registered either way and answers 503 without a pipeline, so naming it
         * would tell an operator this node indexes what it is sent when it does not.
         */
        @Test
        @DisplayName("does not name the document write route without an ingestion pipeline")
        void claimsNoDocumentWrite() {
            assertThat(lines).noneMatch(
                line -> line.contains("POST") && line.contains("/v1/documents "));
        }

        @Test
        @DisplayName("claims nothing that is not registered")
        void claimsNothingOptional() {
            assertThat(lines).noneMatch(line -> line.contains("/v1/erasure"));
            assertThat(lines).noneMatch(line -> line.contains("/v1/graph"));
        }
    }

    @Nested
    @DisplayName("On a node with the optional routes")
    class FullyConfigured {

        @Test
        @DisplayName("names the document write route once an ingestion pipeline is wired")
        void namesDocumentWrite() {
            assertThat(EndpointBanner.lines(withIngestion()))
                .anyMatch(line -> line.contains("POST") && line.contains("/v1/documents "));
        }

        @Test
        @DisplayName("names erasure once it is wired")
        void namesErasure() {
            assertThat(EndpointBanner.lines(withErasure()))
                .anyMatch(line -> line.contains("/v1/erasure"));
        }

        @Test
        @DisplayName("names every graph route when a graph store is configured")
        void namesTheGraphRoutes() {
            List<String> lines = EndpointBanner.lines(withGraphTenanted());
            assertThat(lines).anyMatch(line -> line.contains("/v1/graph/query"));
            assertThat(lines).anyMatch(line -> line.contains("/v1/graph/build"));
            assertThat(lines).anyMatch(line -> line.contains("/v1/graph/erasure"));
            assertThat(lines).anyMatch(line -> line.contains("GET")
                && line.contains("/v1/graph/erasure "));
            assertThat(lines).anyMatch(line -> line.contains("GET")
                && line.contains("/v1/graph/namespaces "));
            assertThat(lines).anyMatch(line -> line.contains("DELETE")
                && line.contains("/v1/graph/namespaces/{namespace}"));
            assertThat(lines).anyMatch(line -> line.contains("PUT")
                && line.contains("/v1/graph/namespaces/{namespace}"));
            assertThat(lines).anyMatch(line -> line.contains("GET")
                && line.contains("/v1/graph/entities "));
            assertThat(lines).anyMatch(line -> line.contains("GET")
                && line.contains("/v1/graph/entities/{id} "));
            assertThat(lines).anyMatch(line -> line.contains("GET")
                && line.contains("/v1/graph/entities/{id}/edges"));
            assertThat(lines).anyMatch(line -> line.contains("POST")
                && line.contains("/v1/graph/facts "));
            assertThat(lines).anyMatch(line -> line.contains("DELETE")
                && line.contains("/v1/graph/facts "));
            assertThat(lines).anyMatch(line -> line.contains("DELETE")
                && line.contains("/v1/graph/facts/{fact_id}"));
        }

        @Test
        @DisplayName("names only the query route on a node wired with a bare pipeline")
        void namesOnlyGraphQueryForABarePipeline() {
            List<String> lines = EndpointBanner.lines(withGraphQueryOnly());
            assertThat(lines).anyMatch(line -> line.contains("/v1/graph/query"));
            assertThat(lines).noneMatch(line -> line.contains("/v1/graph/build"));
            assertThat(lines).noneMatch(line -> line.contains("/v1/graph/erasure"));
            assertThat(lines).noneMatch(line -> line.contains("/v1/graph/namespaces"));
        }
    }

    @Test
    @DisplayName("names the document listing once the node keeps a registry")
    void namesDocumentListing() {
        assertThat(EndpointBanner.lines(routes()))
            .as("a node that keeps no registry does not serve the route, and must not name it")
            .noneMatch(line -> line.contains("/v1/documents/list"));
        assertThat(EndpointBanner.lines(withDocumentListing()))
            .anyMatch(line -> line.contains("GET") && line.contains("/v1/documents/list"));
    }

    // Named factories rather than five anonymous booleans at every call site.

    /** A node with nothing optional wired. */
    private static EndpointBanner.Routes routes() {
        return new EndpointBanner.Routes(false, false, false, false, false);
    }

    private static EndpointBanner.Routes withErasure() {
        return new EndpointBanner.Routes(true, false, false, false, false);
    }

    private static EndpointBanner.Routes withGraphTenanted() {
        return new EndpointBanner.Routes(false, true, false, false, false);
    }

    private static EndpointBanner.Routes withGraphQueryOnly() {
        return new EndpointBanner.Routes(false, false, true, false, false);
    }

    private static EndpointBanner.Routes withIngestion() {
        return new EndpointBanner.Routes(false, false, false, true, false);
    }

    private static EndpointBanner.Routes withDocumentListing() {
        return new EndpointBanner.Routes(false, false, false, false, true);
    }

    private static EndpointBanner.Routes everything() {
        return new EndpointBanner.Routes(true, true, false, true, true);
    }

    @Test
    @DisplayName("lines up the descriptions, so the banner reads as a table")
    void isAligned() {
        List<String> lines = EndpointBanner.lines(everything());
        assertThat(lines).allMatch(line -> line.indexOf(" - ") == lines.get(0).indexOf(" - "));
    }
}
