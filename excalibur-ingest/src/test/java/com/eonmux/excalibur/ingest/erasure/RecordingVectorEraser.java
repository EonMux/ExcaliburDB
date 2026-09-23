package com.eonmux.excalibur.ingest.erasure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records what it is asked to retire, standing in for the segments' graphs.
 *
 * <p>Thread-safe, because ingestion and reindexing call it from their own threads.</p>
 */
public final class RecordingVectorEraser implements DocumentErasureService.VectorEraser {

    private final List<String> documents = new CopyOnWriteArrayList<>();
    private final List<String> chunks = new CopyOnWriteArrayList<>();
    private final int perDocument;

    /**
     * @param perDocument How many nodes each document retirement reports; a chunk retirement
     *                    reports one per chunk
     */
    public RecordingVectorEraser(int perDocument) {
        this.perDocument = perDocument;
    }

    @Override
    public int erase(String documentKey) {
        documents.add(documentKey);
        return perDocument;
    }

    @Override
    public int eraseChunks(Collection<String> chunkIds) {
        chunks.addAll(chunkIds);
        return chunkIds.size();
    }

    /** The documents retired, in call order. */
    public List<String> documents() {
        return new ArrayList<>(documents);
    }

    /** The chunks retired, in call order. */
    public List<String> chunks() {
        return new ArrayList<>(chunks);
    }
}
