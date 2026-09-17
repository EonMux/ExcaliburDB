package excalibur.ingest.visibility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The documents an ingest had started and not finished, on disk.
 *
 * <h2>What it is for</h2>
 * <p>A document's chunks are written one at a time and published as a unit, and the window
 * between the two is what {@link DocumentVisibilityTracker} hides documents through. Held only in
 * memory, that mark dies with the process while the chunks it covers stay in storage: the
 * restarted node has never heard of the document, passes it through, and serves exactly the half
 * document the mark exists to hide. This file is the mark, kept where a restart can find it.</p>
 *
 * <h2>Why a log rather than a rewritten file</h2>
 * <p>What has to be true is an ordering: the mark is durable before the first chunk is written,
 * and outlives the process until the document is either published or rolled back. An append of
 * one short line, forced, is the cheapest thing that gives that ordering, and it costs one fsync
 * at the start of a document and one at its end - next to an embedding round trip and a chunk
 * write per chunk, that is not the expensive part of ingesting a document. Rewriting a whole file
 * per mark would cost the same fsync and more besides.</p>
 *
 * <p>A log of {@code +} and {@code -} lines needs no record count, which the registry beside it
 * does need. Truncation can only take the tail here, and both ways of losing the tail are safe:
 * a lost {@code +} was never forced, so the chunks it covers were never written either, and a
 * lost {@code -} leaves a finished document marked, which hides it until the next crawl rewrites
 * it rather than showing half of one. The file is compacted once it grows past
 * {@value #COMPACTION_THRESHOLD} records, and deleted outright whenever nothing is in flight,
 * so steady-state ingestion does not accumulate a log at all.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Every mutation holds the journal's lock for the append and its force, so two
 * documents beginning at once cannot interleave onto one line.</p>
 */
final class InFlightDocumentJournal {

    private static final Logger LOGGER = LoggerFactory.getLogger(InFlightDocumentJournal.class);

    /** Appended to the registry's own name, so the two sit together in the data directory. */
    static final String SUFFIX = ".in-flight";

    /** Opens the first line, which names the format the rest of the file is in. */
    private static final String HEADER_MARKER = "\\#in-flight";

    /** The format this build writes. */
    static final int FORMAT_VERSION = 1;

    /** Opens the line that marks a document as being ingested. */
    private static final char BEGAN = '+';

    /** Opens the line that releases one. */
    private static final char ENDED = '-';

    /** Separates a line's kind from the document key it names. */
    private static final char FIELD_SEPARATOR = '|';

    /** Introduces an escape sequence in a document key. */
    private static final char ESCAPE = '\\';

    /**
     * How many records the log may hold before it is rewritten from the live set.
     *
     * <p>Two records per document, so this is a few hundred documents' worth: large enough that
     * compaction is rare next to the appends, small enough that the file a restart reads is
     * always short.</p>
     */
    private static final int COMPACTION_THRESHOLD = 1024;

    /** Suffix of the sibling a compaction is assembled in before it is published. */
    private static final String REPLACEMENT_SUFFIX = ".tmp";

    private final Path file;
    private final Object lock = new Object();
    private final Set<String> inFlight = new LinkedHashSet<>();
    private int records;

    /**
     * Opens the journal that belongs beside a registry file.
     *
     * @param registryFile The document registry's path, which names the data directory
     * @throws NullPointerException if the path is null
     */
    InFlightDocumentJournal(Path registryFile) {
        Objects.requireNonNull(registryFile, "registryFile must not be null");
        this.file = registryFile.resolveSibling(registryFile.getFileName() + SUFFIX);
    }

    /**
     * Reads what a previous process left marked.
     *
     * <p>Called once, at open. The keys returned are documents whose ingest never reached a
     * publish or a rollback, so their chunks may be in storage in part.</p>
     *
     * @return The document keys still in flight, in the order they were begun
     * @throws InFlightJournalException if the file exists and cannot be read, or names a format
     *                                  this build does not know
     */
    Set<String> recover() {
        synchronized (lock) {
            if (!Files.exists(file)) {
                return Set.of();
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new InFlightJournalException(file, e);
            }
            replay(lines);
            return Set.copyOf(inFlight);
        }
    }

    /**
     * Marks a document as being ingested, and does not return until the mark is durable.
     *
     * @param documentKey The key the document's chunks are written under
     * @throws InFlightJournalException if the mark cannot be made durable, because the caller is
     *                                  about to write chunks it would then be unable to hide
     */
    void begin(String documentKey) {
        synchronized (lock) {
            if (!inFlight.add(documentKey)) {
                return;
            }
            try {
                append(BEGAN, documentKey);
            } catch (IOException e) {
                inFlight.remove(documentKey);
                throw new InFlightJournalException(file, e);
            }
        }
    }

    /**
     * Releases a document, because it was published or rolled back.
     *
     * <p>A failure here is logged rather than thrown. The caller is finishing a document that is
     * whole, or cleaning up after one that is gone, and failing either would undo work that has
     * already succeeded; the cost of the lost record is that the document is hidden after a
     * restart until it is crawled again, which is the safe direction to fail in.</p>
     *
     * @param documentKey The key the document's chunks were written under
     */
    void end(String documentKey) {
        synchronized (lock) {
            if (!inFlight.remove(documentKey)) {
                return;
            }
            try {
                append(ENDED, documentKey);
            } catch (IOException e) {
                LOGGER.warn("Could not record that document {} finished ingesting; a restart "
                    + "before its next crawl will hide it: {}", documentKey, e.getMessage());
                return;
            }
            try {
                compactIfLong();
            } catch (IOException e) {
                // The release is recorded, so nothing is hidden by this; the log simply stays
                // longer than it needs to be and the next release tries again.
                LOGGER.warn("Could not compact the in-flight document journal at {}: {}",
                    file, e.getMessage());
            }
        }
    }

    /**
     * Forgets every mark, for a tracker that is being reset.
     */
    void clear() {
        synchronized (lock) {
            inFlight.clear();
            try {
                compact();
            } catch (IOException e) {
                LOGGER.warn("Could not clear the in-flight document journal at {}; the documents "
                    + "it names will be hidden after a restart: {}", file, e.getMessage());
            }
        }
    }

    /**
     * Appends one record and forces it, creating the file if this is the first mark.
     */
    private void append(char kind, String documentKey) throws IOException {
        boolean created = !Files.exists(file);
        StringBuilder line = new StringBuilder();
        if (created) {
            // The first mark can precede the first registry save, so the directory the two share
            // may not exist yet.
            Path directory = file.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            line.append(HEADER_MARKER).append(FIELD_SEPARATOR).append(FORMAT_VERSION).append('\n');
            records = 0;
        }
        line.append(kind).append(FIELD_SEPARATOR).append(escape(documentKey)).append('\n');

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(line.toString().getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
        records++;

        if (created) {
            forceDirectory();
        }
    }

    /**
     * Rewrites the log from the live set once it has grown past the threshold.
     */
    private void compactIfLong() throws IOException {
        if (records >= COMPACTION_THRESHOLD) {
            compact();
        }
    }

    /**
     * Replaces the log with one record per document still in flight.
     *
     * <p>An empty set deletes the file instead of writing an empty one, because the absence of
     * the file and a file that marks nothing say the same thing, and the steady state of a node
     * that is ingesting normally is that nothing is in flight.</p>
     */
    private void compact() throws IOException {
        if (inFlight.isEmpty()) {
            if (Files.deleteIfExists(file)) {
                forceDirectory();
            }
            records = 0;
            return;
        }

        Path replacement = file.resolveSibling(file.getFileName() + REPLACEMENT_SUFFIX);
        StringBuilder rewritten = new StringBuilder()
            .append(HEADER_MARKER).append(FIELD_SEPARATOR).append(FORMAT_VERSION).append('\n');
        for (String documentKey : inFlight) {
            rewritten.append(BEGAN).append(FIELD_SEPARATOR).append(escape(documentKey)).append('\n');
        }

        try {
            Files.writeString(replacement, rewritten.toString(), StandardCharsets.UTF_8);
            force(replacement);
            publish(replacement);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(replacement);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
        forceDirectory();
        records = inFlight.size();
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void publish(Path replacement) throws IOException {
        try {
            Files.move(replacement, file,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Said out loud rather than silently accepted: on such a filesystem a crash during
            // the compaction can leave the log short, which releases marks that are still live.
            LOGGER.warn("This filesystem cannot rename atomically; the in-flight document journal "
                + "is compacted with a plain replace");
            Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Forces the directory entry a create, delete or rename has just changed.
     *
     * <p>Best effort: a platform that will not open a directory channel is not a reason to fail
     * an ingest whose mark is otherwise on disk.</p>
     */
    private void forceDirectory() {
        Path directory = file.getParent();
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            LOGGER.warn("Could not force the directory entry for the in-flight document journal "
                + "at {}: {}", directory, e.getMessage());
        }
    }

    /**
     * Rebuilds the live set from the log.
     *
     * <p>A line this build cannot read is skipped rather than failing the load: the last line of
     * a log the power cut through can be half written, and the alternative to skipping it is
     * refusing to start over a record that names no document.</p>
     */
    private void replay(List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }

        String header = lines.get(0);
        String prefix = HEADER_MARKER + FIELD_SEPARATOR;
        if (!header.startsWith(prefix)) {
            throw new InFlightJournalException(file,
                "its first line names no format version: " + header);
        }
        int version;
        try {
            version = Integer.parseInt(header.substring(prefix.length()));
        } catch (NumberFormatException e) {
            throw new InFlightJournalException(file,
                "its first line names no format version this build can read: " + header);
        }
        if (version != FORMAT_VERSION) {
            throw new InFlightJournalException(file, "it declares format version " + version
                + " and this build reads version " + FORMAT_VERSION);
        }

        records = replayRecords(lines);
    }

    /**
     * Applies every record after the header, returning how many were readable.
     */
    private int replayRecords(List<String> lines) {
        int applied = 0;
        for (String line : lines.subList(1, lines.size())) {
            if (line.length() < 2 || line.charAt(1) != FIELD_SEPARATOR) {
                LOGGER.warn("Skipping an unreadable record in the in-flight document journal at "
                    + "{}: {}", file, line);
                continue;
            }
            String documentKey = unescape(line.substring(2));
            if (line.charAt(0) == BEGAN) {
                inFlight.add(documentKey);
            } else if (line.charAt(0) == ENDED) {
                inFlight.remove(documentKey);
            } else {
                LOGGER.warn("Skipping an unreadable record in the in-flight document journal at "
                    + "{}: {}", file, line);
                continue;
            }
            applied++;
        }
        return applied;
    }

    /**
     * Escapes the characters that carry structure in a record.
     *
     * <p>Document keys are built from source-supplied ids - filesystem paths and absolute URLs -
     * so a line terminator inside one is not a theoretical concern: written raw it would forge a
     * second record.</p>
     */
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case ESCAPE -> escaped.append(ESCAPE).append(ESCAPE);
                case '\n' -> escaped.append(ESCAPE).append('n');
                case '\r' -> escaped.append(ESCAPE).append('r');
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * Reverses {@link #escape(String)}.
     */
    private static String unescape(String value) {
        if (value.indexOf(ESCAPE) < 0) {
            return value;
        }
        StringBuilder plain = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != ESCAPE || i + 1 >= value.length()) {
                plain.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case ESCAPE -> plain.append(ESCAPE);
                case 'n' -> plain.append('\n');
                case 'r' -> plain.append('\r');
                default -> plain.append(ESCAPE).append(next);
            }
        }
        return plain.toString();
    }

    @Override
    public String toString() {
        return "InFlightDocumentJournal[" + file + ", inFlight=" + inFlight.size() + "]";
    }

    /**
     * The path this journal is kept at.
     *
     * @return The journal file, which need not exist
     */
    Path path() {
        return file;
    }

}
