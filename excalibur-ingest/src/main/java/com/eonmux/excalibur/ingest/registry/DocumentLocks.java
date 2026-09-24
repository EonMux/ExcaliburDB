package com.eonmux.excalibur.ingest.registry;

import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
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
 * <h2>Reading without waiting</h2>
 * <p>A reader that must not see a document half rewritten, and must not wait behind the rewrite
 * either, reads through {@link #readUnlessRewriting}. It takes no lock: each stripe records which
 * document holds it and counts every time a document takes it, and a read is kept only if no
 * rewrite of its document was under way when it began and none began before it finished.
 * Readers therefore never block writers, never block each other, and are not turned away by the
 * rewrite of another document sharing their stripe.</p>
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

    /**
     * How many times a read overtaken by a rewrite is tried again before it is answered as busy.
     *
     * <p>A rewrite beginning during a read is rare and brief to notice; one that begins during
     * every one of several reads in a row is a stripe under sustained rewriting, and a reader
     * that kept trying would be waiting for it by another name.</p>
     */
    private static final int READ_ATTEMPTS = 3;

    private static final DocumentLocks SHARED = new DocumentLocks();

    private final ReentrantLock[] stripes;

    /**
     * The document holding each stripe, or null when none does or when its holder took a second
     * document on the same stripe inside the first - then the stripe cannot say which one is
     * being rewritten, and a reader of either is turned away until it is released.
     */
    private final AtomicReferenceArray<String> holders;

    /** How many times each stripe has been taken for a document it was not already held for. */
    private final AtomicLongArray takes;

    private DocumentLocks() {
        this.stripes = new ReentrantLock[STRIPES];
        for (int i = 0; i < STRIPES; i++) {
            this.stripes[i] = new ReentrantLock();
        }
        this.holders = new AtomicReferenceArray<>(STRIPES);
        this.takes = new AtomicLongArray(STRIPES);
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

        int stripe = stripeOf(documentKey);
        ReentrantLock lock = stripes[stripe];
        lock.lock();
        boolean outermost = lock.getHoldCount() == 1;
        if (outermost || !documentKey.equals(holders.get(stripe))) {
            // Counted before the holder is named, and both before the work writes anything, so
            // a read that could see the work's first write sees the count move too.
            takes.incrementAndGet(stripe);
            holders.set(stripe, outermost ? documentKey : null);
        }
        try {
            return work.get();
        } finally {
            if (outermost) {
                holders.set(stripe, null);
            }
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
     * Reads a document unless it is being rewritten, without waiting for the rewrite.
     *
     * <p>For readers that must not see a document while it is being rewritten, and must not wait
     * behind the rewrite either. The read runs without the lock and its answer is kept only if no
     * other thread was rewriting the document when it began and no rewrite of it began before it
     * finished; otherwise it is tried again a few times and then answered as {@code busy}, as the
     * caller would answer for a document it cannot show. On the thread already rewriting the
     * document it runs as it is, since that thread is the rewrite.</p>
     *
     * <p>A rewrite of another document sharing the stripe does not turn a read away while it
     * continues; only its release, or another document taking the stripe, during the read makes
     * the read try again. The read may run more than once, so it must have no effect but its
     * answer.</p>
     *
     * @param documentKey The document, as {@code sourceId:documentId}
     * @param read        The read to keep only if no rewrite overlapped it
     * @param busy        What to answer when rewrites kept overlapping it
     * @param <T>         What the read produces
     * @return What the read produced, or {@code busy}
     * @throws NullPointerException if documentKey or read is null
     */
    public <T> T readUnlessRewriting(String documentKey, Supplier<T> read, T busy) {
        Objects.requireNonNull(documentKey, "documentKey must not be null");
        Objects.requireNonNull(read, "read must not be null");

        int stripe = stripeOf(documentKey);
        ReentrantLock lock = stripes[stripe];
        if (lock.isHeldByCurrentThread()) {
            return read.get();
        }
        for (int attempt = 0; attempt < READ_ATTEMPTS; attempt++) {
            long before = takes.get(stripe);
            if (lock.isLocked()) {
                String holder = holders.get(stripe);
                if (holder == null || holder.equals(documentKey)) {
                    return busy;
                }
            }
            T answer = read.get();
            // The read's loads may not be put off past the second look at the count, or a write
            // made after the count moved could be read and the move missed.
            VarHandle.acquireFence();
            if (takes.get(stripe) == before && !(lock.isLocked()
                    && documentKey.equals(holders.get(stripe)))) {
                return answer;
            }
        }
        return busy;
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
        return stripes[stripeOf(documentKey)].isHeldByCurrentThread();
    }

    /**
     * The stripe a document key falls on.
     *
     * <p>The hash is spread before it is masked because document keys share long prefixes - every
     * document of one source begins with the same source id - and {@code String.hashCode} leaves
     * that structure in the low bits, which are the ones the mask keeps.</p>
     *
     * @param documentKey The document, as {@code sourceId:documentId}
     * @return The index of its stripe
     */
    static int stripeOf(String documentKey) {
        int spread = documentKey.hashCode() * 0x9E3779B1;
        return (spread >>> 16) & (STRIPES - 1);
    }
}
