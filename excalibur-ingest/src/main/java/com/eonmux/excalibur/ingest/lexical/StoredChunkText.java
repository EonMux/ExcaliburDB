package com.eonmux.excalibur.ingest.lexical;

import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.retrieval.lexical.native_.UnrecoverableTextException;
import com.eonmux.excalibur.store.CorruptSegmentEntryException;
import com.eonmux.excalibur.store.StorageEngine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;

/**
 * Reads a chunk's text back out of storage, by the identifier the lexical index knows it under.
 *
 * <h2>Why this exists</h2>
 * <p>The native lexical index stores postings and not text, which is what keeps the corpus from
 * existing twice. The price is that compaction - which rewrites a segment without the documents
 * erased from it - has to be given the surviving text from somewhere, and storage is where it
 * already is.</p>
 *
 * <h2>What null means, and what a failure is</h2>
 * <p>Null is an answer, not a failure: storage no longer holds that chunk, so the index should
 * stop carrying an entry for a document that is not there. A compaction acts on that answer
 * permanently - the rewrite it feeds drops the chunk's postings, and nothing puts them back.</p>
 *
 * <p>So a read that failed must never look like it. It throws
 * {@link UncheckedIOException} instead, which the compaction takes as "cannot say": the group of
 * segments being rewritten is abandoned and left as it is, and the other groups go ahead. One
 * unreadable chunk therefore costs one group's rewrite until the next attempt, rather than the
 * whole run - and rather than a chunk silently disappearing from lexical search because storage
 * was briefly unavailable, or was being closed underneath a rewrite at shutdown.</p>
 *
 * <h2>Damage is neither</h2>
 * <p>An entry storage holds and cannot verify - a record or slot that fails its checksum, see
 * {@link CorruptSegmentEntryException} - is not a failure that passes. Reported as one, it would
 * abandon its group on every compaction, and an erasure recorded anywhere in that group could
 * never be discharged. Reported as absence, the chunk would vanish from lexical search with
 * nobody told. So it is its own answer, {@link UnrecoverableTextException}: the rewrite drops the
 * key, logs it at error and counts it where {@code /v1/stats} and {@code /metrics} report it.
 * Everything else storage throws - an engine not yet started or already closed, an I/O error -
 * stays a transient failure.</p>
 *
 * <p>Readers that show text to a client go through {@code ChunkTextResolver}, which turns the
 * same failure into a result without text rather than a failed search.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless beyond the storage engine it reads through, and safe for concurrent use.</p>
 */
public final class StoredChunkText implements Function<String, String> {

    private final StorageEngine storage;

    private StoredChunkText(StorageEngine storage) {
        this.storage = storage;
    }

    /**
     * A text supplier reading through a storage engine.
     *
     * @param storage Where chunks live
     * @return The supplier compaction takes
     */
    public static Function<String, String> of(StorageEngine storage) {
        Objects.requireNonNull(storage, "storage must not be null");
        return new StoredChunkText(storage);
    }

    /**
     * Reads one chunk's text.
     *
     * @param chunkId The chunk's identifier
     * @return Its text, or null when storage holds no text under it
     * @throws UnrecoverableTextException if storage holds the chunk and its entry is damaged,
     *                                    so its text can never be read back
     * @throws UncheckedIOException if storage could not be read, so whether it holds the chunk
     *                              is not known
     */
    @Override
    public String apply(String chunkId) {
        if (chunkId == null) {
            return null;
        }
        byte[] bytes;
        try {
            MemorySegment value = storage.get(chunkId.getBytes(StandardCharsets.UTF_8));
            if (value == null) {
                return null;
            }
            bytes = value.toArray(ValueLayout.JAVA_BYTE);
        } catch (CorruptSegmentEntryException e) {
            // Before the general case, which would call it transient: a damaged entry fails the
            // same way on every attempt, and a rewrite that abandoned its group each time would
            // leave every erasure recorded in that group owed for good.
            throw new UnrecoverableTextException(chunkId, e);
        } catch (RuntimeException e) {
            throw new UncheckedIOException(new IOException(
                "Could not read chunk " + chunkId + " back from storage: " + e.getMessage(), e));
        }
        return ChunkPayloadCodec.textOf(bytes);
    }
}
