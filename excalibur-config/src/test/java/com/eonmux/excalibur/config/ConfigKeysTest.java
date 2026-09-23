package com.eonmux.excalibur.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a mistyped property is noticed rather than ignored.
 *
 * <p>A key nothing reads is parsed, stored and never consulted, so the node starts on the
 * shipped default and nothing says the setting did not take. Every key here is one that has
 * actually been written in a deployment's properties file and silently done nothing.</p>
 */
@DisplayName("Property key recognition")
class ConfigKeysTest {

    @Test
    @DisplayName("a real key is recognised")
    void knownKeysAreKnown() {
        assertThat(ConfigKeys.isKnown("excalibur.shard.hnsw-integration-queue-depth-limit"))
            .isTrue();
        assertThat(ConfigKeys.isKnown("excalibur.query.distance-metric")).isTrue();
        assertThat(ConfigKeys.isKnown("excalibur.llm.chat-model")).isTrue();
    }

    @Test
    @DisplayName("a key in a parsed section that nothing reads is not")
    void typosAreNotKnown() {
        assertThat(ConfigKeys.isKnown("excalibur.ingest.hnsw-queue-depth-limit")).isFalse();
        assertThat(ConfigKeys.isKnown("excalibur.query.recall-target")).isFalse();
        assertThat(ConfigKeys.isKnown("excalibur.llm.model")).isFalse();
    }

    /**
     * These are read by modules this one has no dependency on, through
     * {@code getEffectiveProperties()}. A second copy of their names here would drift, so an
     * unrecognised key is only reported for a section this module actually parses.
     */
    @Test
    @DisplayName("the sections other modules own are left alone")
    void moduleOwnedSectionsAreNotJudged() {
        assertThat(ConfigKeys.isOwned("excalibur.api.auth-token")).isFalse();
        assertThat(ConfigKeys.isOwned("excalibur.shard.volumes")).isTrue();
    }

    @Test
    @DisplayName("a near miss is answered with the key it was probably meant to be")
    void suggestsTheNearestKey() {
        assertThat(ConfigKeys.nearest("excalibur.llm.model")).isEqualTo("excalibur.llm.chat-model");
        assertThat(ConfigKeys.nearest("excalibur.query.top-k-defualt"))
            .isEqualTo("excalibur.query.top-k-default");
    }

    @Test
    @DisplayName("a key unlike anything gets no invented suggestion")
    void suggestsNothingForSomethingElseEntirely() {
        assertThat(ConfigKeys.nearest("excalibur.shard.x")).isNull();
    }

    @Test
    @DisplayName("the same key suggests the same thing every time")
    void suggestionsAreStable() {
        // Set.of randomises its iteration order per JVM instance, so a tie between two equally
        // close keys used to resolve differently from one restart to the next.
        String first = ConfigKeys.nearest("excalibur.ingest.bm25");
        for (int i = 0; i < 50; i++) {
            assertThat(ConfigKeys.nearest("excalibur.ingest.bm25")).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("nothing is suggested for a value far longer than any key")
    void refusesToGuessAtSomethingThatIsNotAKey() {
        assertThat(ConfigKeys.nearest("excalibur.ingest." + "x".repeat(200))).isNull();
    }

    @Test
    @DisplayName("the crawl allow-lists are recognised, not reported as ignored")
    void theCrawlAllowListsAreKnown() {
        // Read by excalibur-api rather than by a Config section, but under a prefix this module
        // claims. Warning that one of them is ignored sends an operator to delete the line, and
        // an empty allow-list stops ingestion working.
        assertThat(ConfigKeys.isKnown("excalibur.ingest.allowed-roots")).isTrue();
        assertThat(ConfigKeys.isKnown("excalibur.ingest.allowed-hosts")).isTrue();
        assertThat(ConfigKeys.isKnown("excalibur.ingest.max-sources")).isTrue();
    }
}
