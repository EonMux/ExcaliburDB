package com.eonmux.excalibur.ingest.lexical;

import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.retrieval.lexical.native_.UnrecoverableTextException;
import com.eonmux.excalibur.store.CorruptSegmentEntryException;
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
 * document that is not there. A read that did fail says so by throwing, and compaction leaves
 * the chunk where it is.
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
    @DisplayName("a read that fails is reported as a failure, never as a chunk that is gone")
    void aFailedReadThrows() throws Exception {
        put("kb:a.txt:chunk:0", "still stored");
        // Storage that cannot be read: the state a rewrite finds it in when shutdown has closed
        // the engine, and the nearest real stand-in for a device error. Answering null here
        // would tell compaction to drop a chunk whose text is on disk.
        storage.close();

        assertThatThrownBy(() -> text.apply("kb:a.txt:chunk:0"))
            .isInstanceOf(java.io.UncheckedIOException.class)
            .hasMessageContaining("kb:a.txt:chunk:0");
    }

    @Test
    @DisplayName("a damaged entry is reported as permanently unreadable, not as a failed read")
    void aCorruptEntryIsUnrecoverable() {
        // Damage fails the same way on every attempt. Reported as a transient failure it would
        // abandon the compaction group on every attempt, and an erasure in that group would be
        // owed forever; reported as absence it would be dropped with nobody told.
        StorageEngine damaged = org.mockito.Mockito.mock(StorageEngine.class);
        CorruptSegmentEntryException corruption = CorruptSegmentEntryException.checksumMismatch(
            Path.of("segment-0007.dat"), 3, 4096L, 0x1234, 0x5678);
        org.mockito.Mockito.when(damaged.get(org.mockito.ArgumentMatchers.any()))
            .thenThrow(corruption);

        assertThatThrownBy(() -> StoredChunkText.of(damaged).apply("kb:a.txt:chunk:0"))
            .isInstanceOfSatisfying(UnrecoverableTextException.class, e -> {
                assertThat(e.key()).isEqualTo("kb:a.txt:chunk:0");
                assertThat(e.getCause()).isSameAs(corruption);
            });
    }

    @Test
    @DisplayName("an entry too short for the payload header is permanently unreadable")
    void aTruncatedEntryIsUnrecoverable() {
        // Answered with null, compaction would drop the chunk with nobody told.
        storage.put("kb:a.txt:chunk:0".getBytes(StandardCharsets.UTF_8), new byte[] {1, 0});

        assertThatThrownBy(() -> text.apply("kb:a.txt:chunk:0"))
            .isInstanceOfSatisfying(UnrecoverableTextException.class,
                e -> assertThat(e.key()).isEqualTo("kb:a.txt:chunk:0"));
    }

    @Test
    @DisplayName("an entry whose header declares more embedding than it holds is permanently "
        + "unreadable")
    void anEntryShorterThanItsDimensionIsUnrecoverable() {
        byte[] whole = ChunkPayloadCodec.encode("memory pressure", new float[] {0.1f, 0.2f});
        byte[] cut = java.util.Arrays.copyOf(whole, 6);
        storage.put("kb:a.txt:chunk:0".getBytes(StandardCharsets.UTF_8), cut);

        assertThatThrownBy(() -> text.apply("kb:a.txt:chunk:0"))
            .isInstanceOf(UnrecoverableTextException.class);
    }

    @Test
    @DisplayName("text that is not UTF-8 is permanently unreadable, not read as replacement "
        + "characters")
    void malformedUtf8IsUnrecoverable() {
        byte[] value = ChunkPayloadCodec.encodeTextOnly("ok");
        byte[] damaged = java.util.Arrays.copyOf(value, value.length + 1);
        damaged[damaged.length - 1] = (byte) 0xC3; // a lead byte with no continuation
        storage.put("kb:a.txt:chunk:0".getBytes(StandardCharsets.UTF_8), damaged);

        assertThatThrownBy(() -> text.apply("kb:a.txt:chunk:0"))
            .isInstanceOfSatisfying(UnrecoverableTextException.class,
                e -> assertThat(e.getCause())
                    .isInstanceOf(java.nio.charset.CharacterCodingException.class));
    }

    @Test
    @DisplayName("text outside the Basic Multilingual Plane reads back intact")
    void supplementaryCharactersReadBack() {
        put("kb:a.txt:chunk:0", "naïve 𝛼-decay 🚀");

        assertThat(text.apply("kb:a.txt:chunk:0")).isEqualTo("naïve 𝛼-decay 🚀");
    }

    @Test
    @DisplayName("any other failure of storage is transient: the read could not say")
    void otherFailuresAreTransient() {
        StorageEngine failing = org.mockito.Mockito.mock(StorageEngine.class);
        org.mockito.Mockito.when(failing.get(org.mockito.ArgumentMatchers.any()))
            .thenThrow(new IllegalStateException("StorageEngine not started"));

        assertThatThrownBy(() -> StoredChunkText.of(failing).apply("kb:a.txt:chunk:0"))
            .isInstanceOf(java.io.UncheckedIOException.class)
            .isNotInstanceOf(UnrecoverableTextException.class)
            .hasMessageContaining("not started");
    }

    @Test
    @DisplayName("a supplier cannot be built without a storage engine")
    void storageIsRequired() {
        assertThatThrownBy(() -> StoredChunkText.of(null))
            .isInstanceOf(NullPointerException.class);
    }
}
