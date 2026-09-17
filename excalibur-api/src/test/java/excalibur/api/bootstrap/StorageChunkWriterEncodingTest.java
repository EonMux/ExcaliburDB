package excalibur.api.bootstrap;

import io.excalibur.core.codec.ChunkPayloadCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stored form of a reindexed chunk, with and without a vector.
 *
 * <p>Ingestion on a lexical-only node already stores chunks with no embedding, and a reindex of
 * that corpus has to store them the same way. Writing them through the embedding layout instead
 * is not an option - there is no vector to put in it - and refusing to write them at all is what
 * kept a BM25-only node from ever reindexing.</p>
 */
@DisplayName("Reindex chunk encoding")
class StorageChunkWriterEncodingTest {

    @Test
    @DisplayName("a chunk with a vector keeps its vector")
    void embeddedChunkRoundTrips() {
        byte[] value = StorageChunkWriter.encode("hello", new float[] {1.0f, 2.0f});

        assertThat(ChunkPayloadCodec.dimensionOf(value)).isEqualTo(2);
        assertThat(ChunkPayloadCodec.textOf(value)).isEqualTo("hello");
    }

    @Test
    @DisplayName("a chunk with no vector is stored as text, not refused")
    void lexicalChunkIsStoredAsText() {
        byte[] value = StorageChunkWriter.encode("hello", null);

        assertThat(ChunkPayloadCodec.dimensionOf(value))
            .as("dimension 0 is the layout's own way of saying a chunk was never embedded")
            .isZero();
        assertThat(ChunkPayloadCodec.textOf(value)).isEqualTo("hello");
        assertThat(ChunkPayloadCodec.embeddingOf(value)).isNull();
    }

    @Test
    @DisplayName("an empty vector is the same absence as no vector")
    void emptyVectorIsNoVector() {
        byte[] value = StorageChunkWriter.encode("hello", new float[0]);

        assertThat(ChunkPayloadCodec.dimensionOf(value)).isZero();
        assertThat(ChunkPayloadCodec.textOf(value)).isEqualTo("hello");
    }
}
