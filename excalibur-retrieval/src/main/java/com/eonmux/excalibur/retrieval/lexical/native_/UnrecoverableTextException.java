package com.eonmux.excalibur.retrieval.lexical.native_;

import java.util.Objects;

/**
 * Raised by a compaction's text supplier when a key's text exists and can never be read back.
 *
 * <h2>Why a third answer</h2>
 * <p>A supplier has had two answers besides the text itself. Null says the text is gone, and the
 * rewrite drops the key. {@link java.io.UncheckedIOException} says the read failed and nothing
 * can be concluded, and the rewrite abandons the group it was building and leaves it as it was,
 * for the next compaction to try again. That second answer is right for a failure that passes -
 * a device error, storage closing underneath a shutdown - and wrong for one that does not. A
 * stored entry whose bytes fail their checksum fails the same way on every attempt, so a group
 * holding one is abandoned on every attempt, and an erasure recorded anywhere in that group can
 * never be discharged: the promise that erased text leaves disk within a bound becomes a promise
 * that it never does, because of a chunk that has nothing to do with it.</p>
 *
 * <p>This is the answer for that case. The rewrite drops the key - the index can no longer carry
 * postings for text that nobody can read to verify them - logs it at error, and counts it, so
 * the damage is reported where an operator looks rather than discovered as a chunk that quietly
 * stopped matching. Restoring the entry and reindexing its document puts it back.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable.</p>
 */
public final class UnrecoverableTextException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String key;

    /**
     * Records that a key's text is permanently unreadable.
     *
     * @param key   The key whose text cannot be read
     * @param cause What storage reported
     * @throws NullPointerException if key is null
     */
    public UnrecoverableTextException(String key, Throwable cause) {
        super("The stored text of " + Objects.requireNonNull(key, "key must not be null")
            + " is permanently unreadable"
            + (cause == null ? "" : ": " + cause.getMessage()), cause);
        this.key = key;
    }

    /**
     * The key whose text cannot be read.
     *
     * @return The key
     */
    public String key() {
        return key;
    }
}
