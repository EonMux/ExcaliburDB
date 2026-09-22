package com.eonmux.excalibur.ingest.lexical;

import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.store.StorageEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The way back from a chunk identifier to the text the corpus holds under it.
 *
 * <p>This is what lets the native lexical index store postings and no text at all. Compaction
 * rewrites a segment without the chunks erased from it, and the surviving chunks' text has to come
 * from somewhere: here, from the same storage engine a query would resolve a hit against, so what
 * is reindexed is what a reader would have been shown.
 *
 * <p>Null is the interesting case. It does not mean the read failed - it means storage no longer
 * holds that chunk, which tells compaction to drop the index entry rather than carry one for a
 * document that is not there.
 */
@DisplayName("Stored chunk text")
class StoredChunkTextTest {

    @TempDir
    Path tempDir;

    private StorageEngine storage;
    private Function<String, String> text;

    @BeforeEach
    void setUp() throws Exception {
        storage = new StorageEngine(Files.createDirectories(tempDir.resolve("data")));
        storage.start();
        text = StoredChunkText.of(storage);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            try {
                storage.close();
            } catch (Exception ignored) {
                // Tidy-up only.
            }
        }
    }

    private void put(String chunkId, String value) {
        storage.put(chunkId.getBytes(StandardCharsets.UTF_8),
            ChunkPayloadCodec.encodeTextOnly(value));
    }

    @Test
    @DisplayName("a stored chunk reads back as the text it was written with")
    void readsStoredText() {
        put("kb:a.txt:chunk:0", "the certificate chain could not be verified");

        assertThat(text.apply("kb:a.txt:chunk:0"))
            .isEqualTo("the certificate chain could not be verified");
    }

    @Test
    @DisplayName("a chunk storage no longer holds reads back as null")
    void absentChunksAreNull() {
        // The signal that tells compaction to drop the entry rather than rewrite it.
        assertThat(text.apply("kb:a.txt:chunk:404")).isNull();
    }

    @Test
    @DisplayName("a deleted chunk reads back as null")
    void deletedChunksAreNull() {
        put("kb:a.txt:chunk:0", "confidential");
        storage.delete("kb:a.txt:chunk:0".getBytes(StandardCharsets.UTF_8));

        assertThat(text.apply("kb:a.txt:chunk:0")).isNull();
    }

    @Test
    @DisplayName("a chunk written with an embedding still reads back its text")
    void readsTextBesideAnEmbedding() {
        // The ingestion path writes both when this node embeds; compaction wants only the text.
        storage.put("kb:a.txt:chunk:0".getBytes(StandardCharsets.UTF_8),
            ChunkPayloadCodec.encode("memory pressure on the host", new float[] {0.1f, 0.2f}));

        assertThat(text.apply("kb:a.txt:chunk:0")).isEqualTo("memory pressure on the host");
    }

    @Test
    @DisplayName("a null identifier is an answer of null, not a failure")
    void nullIdentifiersAreNull() {
        // Compaction runs over whatever the index names, and one bad name must not stop it.
        assertThat(text.apply(null)).isNull();
    }

    @Test
    @DisplayName("a supplier cannot be built without a storage engine")
    void storageIsRequired() {
        assertThatThrownBy(() -> StoredChunkText.of(null))
            .isInstanceOf(NullPointerException.class);
    }
}
