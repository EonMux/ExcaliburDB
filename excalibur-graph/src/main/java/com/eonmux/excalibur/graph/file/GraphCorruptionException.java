package com.eonmux.excalibur.graph.file;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A file of a built graph refused because it does not hold what the build wrote, as opposed to
 * one that could not be read at all.
 *
 * <h2>Why the distinction needs a type</h2>
 * <p>Opening a built graph fails with {@link IOException} for unrelated reasons: the operating
 * system refused a read, a file names a format version this build does not know, or a file's
 * bytes no longer match the length or the checksum recorded for them when the graph was built.
 * Only the last is a statement about the bytes, and it is the one a node can act on by itself:
 * a built graph is derived entirely from the namespace's staging logs, so a damaged one is not
 * served and is replaced by the next build, where a graph a newer build wrote, or a device that
 * refuses reads, needs an operator. The caller deciding which it is would otherwise be left
 * parsing messages, and a reworded message would silently move a graph from one policy to the
 * other.</p>
 *
 * <p>Raised on the same terms whether a graph is being opened to serve, read by an erasure
 * compaction, or inspected by {@link com.eonmux.excalibur.graph.integrity.ServedGraphIntegrity},
 * which is what lets an inspection's verdict stand for what the node would do.</p>
 *
 * @author ExcaliburDB
 */
public final class GraphCorruptionException extends IOException {

    private static final long serialVersionUID = 1L;

    /** The damaged file. */
    private final transient Path file;

    /**
     * @param file    The damaged file
     * @param message What is damaged and how, phrased for the operator who has to act on it
     * @throws NullPointerException if {@code file} is null
     */
    public GraphCorruptionException(Path file, String message) {
        super(message);
        this.file = Objects.requireNonNull(file, "file must not be null");
    }

    /**
     * For damage another library found first, such as a Lucene index of the graph whose checksum
     * footer no longer matches.
     *
     * @param file    The damaged file, or the directory of an index read as a whole
     * @param message What is damaged and how
     * @param cause   The refusal that found it
     * @throws NullPointerException if {@code file} is null
     */
    public GraphCorruptionException(Path file, String message, Throwable cause) {
        super(message, cause);
        this.file = Objects.requireNonNull(file, "file must not be null");
    }

    /**
     * The damaged file.
     *
     * @return Its path
     */
    public Path file() {
        return file;
    }
}
