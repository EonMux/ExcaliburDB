package com.eonmux.excalibur.ingest.lexical;

import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.store.StorageEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <h2>What null means</h2>
 * <p>Null is an answer, not a failure: storage no longer holds that chunk, so the index should
 * stop carrying an entry for a document that is not there. A read that genuinely failed returns
 * null too, having logged why - the alternative is failing a compaction over one unreadable
 * chunk, which would leave every erased chunk in the run on disk.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless beyond the storage engine it reads through, and safe for concurrent use.</p>
 */
public final class StoredChunkText implements Function<String, String> {

    private static final Logger LOGGER = LoggerFactory.getLogger(StoredChunkText.class);

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

    @Override
    public String apply(String chunkId) {
        if (chunkId == null) {
            return null;
        }
        try {
            MemorySegment value = storage.get(chunkId.getBytes(StandardCharsets.UTF_8));
            if (value == null) {
                return null;
            }
            return ChunkPayloadCodec.textOf(value.toArray(ValueLayout.JAVA_BYTE));
        } catch (Exception e) {
            LOGGER.warn("Could not read chunk {} back for compaction: {}", chunkId, e.getMessage());
            return null;
        }
    }
}
