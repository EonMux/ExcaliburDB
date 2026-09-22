package com.eonmux.excalibur.ingest.registry;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serialises the operations that rewrite what a node holds of one document.
 *
 * <h2>What it guards, and why anything has to</h2>
 * <p>A document's presence is spread over three places that no transaction spans: its chunks in
 * storage, its entry in the {@link DocumentRegistry}, and its entry in the visibility tracker. An
 * ingestion writes all three and an erasure removes all three, and each reads the registry to
 * decide what the other two should contain. Left to race, the two orders fail in opposite and
 * equally unacceptable ways: a publication finishing after an erasure puts the registry and
 * visibility entries back for chunks that are gone, and an erasure finishing after the chunks of
 * a publication are written but before it registers them leaves those chunks in storage with no
 * entry naming them - which is content no later erasure can find, because the registry is the
 * only map from a document to its chunk ids.</p>
 *
 * <h2>Why one table for the process</h2>
 * <p>The state above is itself per process: one storage engine, one registry, one visibility
 * tracker. A lock table handed round the wiring would guard nothing that two instances of it
 * could not still corrupt, and every collaborator that took one would be able to be given the
 * wrong one. So there is exactly one, reached by {@link #shared()}.</p>
 *
 * <h2>Why striped</h2>
 * <p>A lock per document key would have to be created on demand and removed when the last holder
 * left it, and getting that wrong leaks an entry per document ingested. Stripes are allocated
 * once and never grow. Two documents that land on the same stripe serialise with each other,
 * which costs a little throughput and is never incorrect; with {@value #STRIPES} of them and one
 * ingestion thread per source, a collision is rare enough not to be worth the bookkeeping the
 * alternative needs.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use. The locks are reentrant, so a caller already holding a document's
 * lock may take it again; no call here acquires more than one at a time, so no pair of callers
 * can deadlock on them.</p>
 */
public final class DocumentLocks {

    /**
     * How many locks the table holds.
     *
     * <p>A power of two, so a key's stripe is a mask of its hash rather than a remainder.</p>
     */
    private static final int STRIPES = 1024;

    private static final DocumentLocks SHARED = new DocumentLocks();

    private final ReentrantLock[] stripes;

    private DocumentLocks() {
        this.stripes = new ReentrantLock[STRIPES];
        for (int i = 0; i < STRIPES; i++) {
            this.stripes[i] = new ReentrantLock();
        }
    }

    /**
     * The table guarding this process's documents.
     *
     * @return The one instance every ingestion and every erasure takes its locks from
     */
    public static DocumentLocks shared() {
        return SHARED;
    }

    /**
     * Runs work with the document's lock held.
     *
     * @param documentKey The document, as {@code sourceId:documentId}
     * @param work        What to do while nothing else may rewrite the document
     * @param <T>         What the work produces
     * @return What the work produced
     * @throws NullPointerException if either argument is null
     */
    public <T> T inDocument(String documentKey, Supplier<T> work) {
        Objects.requireNonNull(documentKey, "documentKey must not be null");
        Objects.requireNonNull(work, "work must not be null");

        ReentrantLock lock = lockFor(documentKey);
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs work with the document's lock held.
     *
     * @param documentKey The document, as {@code sourceId:documentId}
     * @param work        What to do while nothing else may rewrite the document
     * @throws NullPointerException if either argument is null
     */
    public void inDocument(String documentKey, Runnable work) {
        Objects.requireNonNull(work, "work must not be null");
        inDocument(documentKey, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Whether the calling thread is already inside this document's lock.
     *
     * <p>For collaborators that are correct either way but want to say which they are in; it is
     * not a way to test whether the lock is free, because the answer for another thread's lock
     * is out of date before it can be acted on.</p>
     *
     * @param documentKey The document, as {@code sourceId:documentId}
     * @return true when this thread holds the document's lock
     * @throws NullPointerException if documentKey is null
     */
    public boolean heldByCurrentThread(String documentKey) {
        Objects.requireNonNull(documentKey, "documentKey must not be null");
        return lockFor(documentKey).isHeldByCurrentThread();
    }

    /**
     * The stripe a document key falls on.
     *
     * <p>The hash is spread before it is masked because document keys share long prefixes - every
     * document of one source begins with the same source id - and {@code String.hashCode} leaves
     * that structure in the low bits, which are the ones the mask keeps.</p>
     */
    private ReentrantLock lockFor(String documentKey) {
        int spread = documentKey.hashCode() * 0x9E3779B1;
        return stripes[(spread >>> 16) & (STRIPES - 1)];
    }
}
