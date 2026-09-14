package excalibur.api.server;

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

        private final List<String> lines = EndpointBanner.lines(false, false, false, false);

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
            assertThat(EndpointBanner.lines(false, false, false, true))
                .anyMatch(line -> line.contains("POST") && line.contains("/v1/documents "));
        }

        @Test
        @DisplayName("names erasure once it is wired")
        void namesErasure() {
            assertThat(EndpointBanner.lines(true, false, false, false))
                .anyMatch(line -> line.contains("/v1/erasure"));
        }

        @Test
        @DisplayName("names all three graph routes when a graph store is configured")
        void namesTheGraphRoutes() {
            List<String> lines = EndpointBanner.lines(false, true, false, false);
            assertThat(lines).anyMatch(line -> line.contains("/v1/graph/query"));
            assertThat(lines).anyMatch(line -> line.contains("/v1/graph/build"));
            assertThat(lines).anyMatch(line -> line.contains("/v1/graph/erasure"));
        }

        @Test
        @DisplayName("names only the query route on a node wired with a bare pipeline")
        void namesOnlyGraphQueryForABarePipeline() {
            List<String> lines = EndpointBanner.lines(false, false, true, false);
            assertThat(lines).anyMatch(line -> line.contains("/v1/graph/query"));
            assertThat(lines).noneMatch(line -> line.contains("/v1/graph/build"));
            assertThat(lines).noneMatch(line -> line.contains("/v1/graph/erasure"));
        }
    }

    @Test
    @DisplayName("lines up the descriptions, so the banner reads as a table")
    void isAligned() {
        List<String> lines = EndpointBanner.lines(true, true, false, true);
        assertThat(lines).allMatch(line -> line.indexOf(" - ") == lines.get(0).indexOf(" - "));
    }
}
