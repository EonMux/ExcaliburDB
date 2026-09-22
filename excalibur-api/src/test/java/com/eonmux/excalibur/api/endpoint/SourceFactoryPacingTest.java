package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.config.IngestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How hard a registered crawl is allowed to work the host it walks.
 *
 * <p>What is tested here is not that {@code crawl_delay_ms} is honoured but that it can only
 * ever be honoured upwards: a registration may ask for a gentler crawl than the node requires,
 * and asking for a quicker one gets the node's rate instead.</p>
 *
 * <p>Against the decision rather than against a built {@code HttpSource}, because building one
 * through the factory resolves the host's addresses to keep the crawler off internal networks,
 * and a test that needs working DNS to assert an arithmetic rule fails for reasons that have
 * nothing to do with the rule.</p>
 */
@DisplayName("Crawl pacing, as a registration asks for it")
class SourceFactoryPacingTest {

    private static final long NODE_FLOOR_MS = 250;

    @Nested
    @DisplayName("A node that paces its crawls")
    class WithAFloor {

        @Test
        @DisplayName("a registration that asks for nothing is paced at the node's rate")
        void silenceTakesTheNodeRate() {
            assertThat(SourceFactory.pacedDelayMillis(NODE_FLOOR_MS, null))
                .isEqualTo(NODE_FLOOR_MS);
        }

        @Test
        @DisplayName("a registration may ask to be gentler than the node requires")
        void aLongerDelayIsHonoured() {
            assertThat(SourceFactory.pacedDelayMillis(NODE_FLOOR_MS, 2000L)).isEqualTo(2000L);
        }

        @Test
        @DisplayName("a registration that asks to be quicker gets the node's rate")
        void aShorterDelayIsRaisedToTheFloor() {
            assertThat(SourceFactory.pacedDelayMillis(NODE_FLOOR_MS, 10L))
                .isEqualTo(NODE_FLOOR_MS);
        }

        @Test
        @DisplayName("zero does not turn a node's pacing off")
        void zeroDoesNotDefeatTheFloor() {
            // The one value a caller would reach for to crawl flat out.
            assertThat(SourceFactory.pacedDelayMillis(NODE_FLOOR_MS, 0L))
                .isEqualTo(NODE_FLOOR_MS);
        }
    }

    @Nested
    @DisplayName("A node that paces nothing")
    class WithNoFloor {

        @Test
        @DisplayName("still honours a registration that asks to be paced")
        void aRequestIsStillHonoured() {
            assertThat(SourceFactory.pacedDelayMillis(0, 500L)).isEqualTo(500L);
        }

        @Test
        @DisplayName("leaves a silent registration unpaced")
        void silenceStaysUnpaced() {
            assertThat(SourceFactory.pacedDelayMillis(0, null)).isZero();
        }
    }

    @Test
    @DisplayName("the default floor is the one the template and the README document")
    void theDefaultIsTheDocumentedOne() {
        assertThat(IngestConfig.DEFAULT_CRAWL_DELAY_MS).isEqualTo(200L);
    }
}
