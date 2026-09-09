package io.excalibur.core.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one naming convention four components have to agree on.
 *
 * <p>These are not tests of string concatenation. Ingestion writes a chunk under a key built
 * here; the registry keys metadata by the document key built here; excalibur-shard derives a
 * document key back out of a storage key to register it against an HNSW node; excalibur-query
 * then asks the visibility tracker about that key. Every way two of those can disagree is
 * silent - the node is registered under a key nothing asks about, so the document is dropped
 * from results rather than reported as an error - which is why the round trip is pinned here
 * rather than left to each caller to get right separately.</p>
 */
@DisplayName("Chunk identifiers")
class ChunkIdentifiersTest {

    @Test
    @DisplayName("a chunk id round-trips back to the document key it was built from")
    void chunkIdRoundTrips() {
        String key = ChunkIdentifiers.documentKey("mySource", "doc1");
        String chunk = ChunkIdentifiers.chunkId("mySource", "doc1", 3);

        assertThat(key).isEqualTo("mySource:doc1");
        assertThat(chunk).isEqualTo("mySource:doc1:chunk:3");
        assertThat(ChunkIdentifiers.documentKeyOfChunk(chunk)).isEqualTo(key);
    }

    @Test
    @DisplayName("a document id containing colons round-trips")
    void colonsInTheDocumentIdSurvive() {
        // An HTTP source uses absolute URLs as document ids, so splitting on the last colon
        // instead of on the marker would cut "https://host/a" down to "https://host/a" minus
        // its own trailing segment - a key the visibility tracker was never told about.
        String documentId = "https://example.com:8443/a/b.html";
        String chunk = ChunkIdentifiers.chunkId("web", documentId, 12);

        assertThat(chunk).isEqualTo("web:" + documentId + ":chunk:12");
        assertThat(ChunkIdentifiers.documentKeyOfChunk(chunk))
            .isEqualTo(ChunkIdentifiers.documentKey("web", documentId));
    }

    @Test
    @DisplayName("a document id that itself contains the marker round-trips")
    void aMarkerInsideTheDocumentIdDoesNotWin() {
        // Splitting on the first marker rather than the last would hand back a truncated key.
        String documentId = "notes:chunk:draft";
        String chunk = ChunkIdentifiers.chunkId("src", documentId, 1);

        assertThat(ChunkIdentifiers.documentKeyOfChunk(chunk)).isEqualTo("src:notes:chunk:draft");
    }

    @Test
    @DisplayName("a key carrying no marker is already a document key and is returned unchanged")
    void aKeyWithoutAMarkerIsUnchanged() {
        // This is the case the shard worker used to get wrong on its own: it stripped from the
        // last colon, turning "mySource:doc1" into "mySource", while every other component
        // treated the same string as a complete document key. Whichever path indexed a segment
        // then decided whether the atomicity filter could find the document at all.
        assertThat(ChunkIdentifiers.documentKeyOfChunk("mySource:doc1")).isEqualTo("mySource:doc1");
        assertThat(ChunkIdentifiers.documentKeyOfChunk("bare")).isEqualTo("bare");
    }

    @Test
    @DisplayName("extracting from a document key is idempotent")
    void extractionIsIdempotent() {
        // The worker registers whatever comes back; applying the derivation twice must not
        // erode the key a second time.
        String once = ChunkIdentifiers.documentKeyOfChunk("src:doc:chunk:0");
        assertThat(ChunkIdentifiers.documentKeyOfChunk(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("a key that is only a marker is not mistaken for a suffix")
    void aLeadingMarkerIsNotStripped() {
        // lastIndexOf returns 0 here; stripping would leave the empty string as a document key.
        assertThat(ChunkIdentifiers.documentKeyOfChunk(":chunk:0")).isEqualTo(":chunk:0");
    }

    @Test
    @DisplayName("chunkId and chunkIdForDocumentKey agree")
    void bothChunkIdFormsAgree() {
        assertThat(ChunkIdentifiers.chunkId("s", "d", 7))
            .isEqualTo(ChunkIdentifiers.chunkIdForDocumentKey(
                ChunkIdentifiers.documentKey("s", "d"), 7));
    }

    @Test
    @DisplayName("chunk indices are distinct and ordered by index, not by string")
    void indicesAreDistinct() {
        String documentKey = ChunkIdentifiers.documentKey("s", "d");

        assertThat(ChunkIdentifiers.chunkIdForDocumentKey(documentKey, 0))
            .isNotEqualTo(ChunkIdentifiers.chunkIdForDocumentKey(documentKey, 1));
        assertThat(ChunkIdentifiers.chunkIdForDocumentKey(documentKey, 10))
            .endsWith(":chunk:10");
        // Both still resolve to the same owner.
        assertThat(ChunkIdentifiers.documentKeyOfChunk(
            ChunkIdentifiers.chunkIdForDocumentKey(documentKey, 10))).isEqualTo(documentKey);
    }

    @Test
    @DisplayName("an empty source or document id is still a well-formed key")
    void emptyComponentsAreAccepted() {
        assertThat(ChunkIdentifiers.documentKey("", "d")).isEqualTo(":d");
        assertThat(ChunkIdentifiers.documentKeyOfChunk(
            ChunkIdentifiers.chunkId("", "d", 0))).isEqualTo(":d");
    }

    @Test
    @DisplayName("a null identifier is refused rather than becoming the text \"null\"")
    void nullsAreRefused() {
        assertThatThrownBy(() -> ChunkIdentifiers.documentKey(null, "d"))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ChunkIdentifiers.documentKey("s", null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ChunkIdentifiers.chunkIdForDocumentKey(null, 0))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ChunkIdentifiers.documentKeyOfChunk(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a negative chunk index is refused")
    void negativeIndicesAreRefused() {
        assertThatThrownBy(() -> ChunkIdentifiers.chunkId("s", "d", -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-negative");
    }

    @Test
    @DisplayName("the class cannot be instantiated")
    void utilityClassIsNotInstantiable() throws Exception {
        var constructor = ChunkIdentifiers.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance)
            .hasRootCauseInstanceOf(AssertionError.class);
    }
}
