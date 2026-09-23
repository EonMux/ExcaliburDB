package com.eonmux.excalibur.ingest.visibility;

import com.eonmux.excalibur.ingest.registry.ChecksummedLineReader;

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

import static com.eonmux.excalibur.ingest.visibility.InFlightJournalFormat.BEGAN;
import static com.eonmux.excalibur.ingest.visibility.InFlightJournalFormat.ENDED;
import static com.eonmux.excalibur.ingest.visibility.InFlightJournalFormat.FIRST_CHECKSUMMED_VERSION;
import static com.eonmux.excalibur.ingest.visibility.InFlightJournalFormat.FORMAT_VERSION;
import static com.eonmux.excalibur.ingest.visibility.InFlightJournalFormat.NO_HEADER;
import static com.eonmux.excalibur.ingest.visibility.InFlightJournalFormat.OLDEST_READABLE_VERSION;
import static com.eonmux.excalibur.ingest.visibility.InFlightJournalFormat.SEPARATOR;
import static com.eonmux.excalibur.ingest.visibility.InFlightJournalFormat.UNREADABLE_VERSION;

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
 * <h2>Checksums, and what a line that fails one means</h2>
 * <p>Every line carries the CRC32C of its own bytes; {@link InFlightJournalFormat} has the layout.
 * Where in the file a line that fails it sits decides what it is. A last line the file ends
 * inside of - no terminator after it - is an append the process did not live to finish, which is
 * the safe loss above; it is skipped, as a half-written last line always has been. A complete
 * line whose checksum does not match was damaged after it was written, and the journal refuses to
 * load: that line may be the {@code +} hiding a half-written document, and skipping it would
 * serve exactly what the journal exists to hide. A header that fails its checksum is refused for
 * the same reason a header naming no version is.</p>
 *
 * <p>A torn last line is not left in place either. The next append would be written straight
 * after it, onto the same line, and the two would read back as one damaged record - so recovery
 * rewrites the journal from what it read before anything is appended. The same rewrite brings a
 * version 1 journal, whose lines carry no checksum and are read exactly as they always were, to
 * the current version.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Every mutation holds the journal's lock for the append and its force, so two
 * documents beginning at once cannot interleave onto one line.</p>
 */
final class InFlightDocumentJournal {

    private static final Logger LOGGER = LoggerFactory.getLogger(InFlightDocumentJournal.class);

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
     * The version the lines appended to the existing file are written in.
     *
     * <p>The current one, except for a version 1 journal that recovery could not rewrite: lines
     * appended to it stay in version 1, because a file mixing the two would be read under the
     * rules of its header and have half its lines misread. The next compaction brings it
     * forward.</p>
     */
    private int appendVersion = FORMAT_VERSION;

    /**
     * Opens the journal that belongs beside a registry file.
     *
     * @param registryFile The document registry's path, which names the data directory
     * @throws NullPointerException if the path is null
     */
    InFlightDocumentJournal(Path registryFile) {
        Objects.requireNonNull(registryFile, "registryFile must not be null");
        this.file = registryFile.resolveSibling(
            registryFile.getFileName() + InFlightJournalFormat.SUFFIX);
    }

    /**
     * Reads what a previous process left marked.
     *
     * <p>Called once, at open. The keys returned are documents whose ingest never reached a
     * publish or a rollback, so their chunks may be in storage in part. A journal that ended in
     * a torn line, or is in an older version, is rewritten from what was read before this
     * returns; see the class documentation.</p>
     *
     * @return The document keys still in flight, in the order they were begun
     * @throws InFlightJournalException if the file exists and cannot be read, names a format this
     *                                  build does not know, holds a complete line that fails its
     *                                  checksum, or ended in a torn line that could not be
     *                                  rewritten
     */
    Set<String> recover() {
        synchronized (lock) {
            if (!Files.exists(file)) {
                return Set.of();
            }
            ChecksummedLineReader.Lines read;
            try {
                read = ChecksummedLineReader.readAll(file);
            } catch (IOException e) {
                throw new InFlightJournalException(file, e);
            }
            if (read.lines().isEmpty()) {
                return Set.of();
            }
            int version = replay(read);
            rewriteIfNeeded(version, !read.lastLineTerminated());
            return Set.copyOf(inFlight);
        }
    }

    /**
     * Rewrites a recovered journal that ended in a torn line or is in an older version.
     *
     * @param version The version the file was read in
     * @param torn    Whether its last line had no terminator
     * @throws InFlightJournalException if a torn journal cannot be rewritten, since appending to
     *                                  it would weld the next record onto the torn one
     */
    private void rewriteIfNeeded(int version, boolean torn) {
        if (!torn && version == FORMAT_VERSION) {
            return;
        }
        try {
            compact();
            if (version < FORMAT_VERSION) {
                LOGGER.info("In-flight document journal at {} rewritten from format version {} "
                    + "to {}", file, version, FORMAT_VERSION);
            }
        } catch (IOException e) {
            if (torn) {
                throw new InFlightJournalException(file, e);
            }
            appendVersion = version;
            LOGGER.warn("Could not rewrite the in-flight document journal at {} in format version "
                + "{}; it stays in version {} until it is next compacted: {}",
                file, FORMAT_VERSION, version, e.getMessage());
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
        // An empty file is what a crash can leave between creating the journal and writing its
        // first line, and a record appended to it would be a journal with no header.
        boolean created = !Files.exists(file) || Files.size(file) == 0;
        StringBuilder line = new StringBuilder();
        if (created) {
            // The first mark can precede the first registry save, so the directory the two share
            // may not exist yet.
            Path directory = file.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            line.append(InFlightJournalFormat.header()).append('\n');
            records = 0;
            appendVersion = FORMAT_VERSION;
        }
        line.append(record(kind, documentKey, appendVersion)).append('\n');

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
     * Formats one record in a given version.
     *
     * @param kind        {@link InFlightJournalFormat#BEGAN} or {@link InFlightJournalFormat#ENDED}
     * @param documentKey The document
     * @param version     The version the file it is appended to is in
     * @return The line, without a terminator
     */
    private static String record(char kind, String documentKey, int version) {
        String content = String.valueOf(kind) + SEPARATOR + escape(documentKey);
        return version >= FIRST_CHECKSUMMED_VERSION
            ? InFlightJournalFormat.seal(content) : content;
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
     * Replaces the log with one record per document still in flight, in the current version.
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
            appendVersion = FORMAT_VERSION;
            return;
        }

        Path replacement = file.resolveSibling(file.getFileName() + REPLACEMENT_SUFFIX);
        StringBuilder rewritten = new StringBuilder()
            .append(InFlightJournalFormat.header()).append('\n');
        for (String documentKey : inFlight) {
            rewritten.append(record(BEGAN, documentKey, FORMAT_VERSION)).append('\n');
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
        appendVersion = FORMAT_VERSION;
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
     * @param read Every line of the log, and whether its last one was terminated
     * @return The version the log was written in
     * @throws InFlightJournalException if the header is not one this build reads, or a complete
     *                                  line fails its checksum
     */
    private int replay(ChecksummedLineReader.Lines read) {
        List<String> lines = read.lines();
        String header = lines.get(0);
        if (lines.size() == 1 && !read.lastLineTerminated()) {
            // The first append writes the header and the first record together, so a header the
            // file ends inside of is that append torn: its record was never forced, and nothing
            // it would have marked was written.
            skip(header);
            return FORMAT_VERSION;
        }
        int version = InFlightJournalFormat.declaredVersion(header);
        if (version == NO_HEADER) {
            throw new InFlightJournalException(file,
                "its first line names no format version: " + header);
        }
        if (version == UNREADABLE_VERSION) {
            throw new InFlightJournalException(file,
                "its first line names no format version this build can read: " + header);
        }
        if (version < OLDEST_READABLE_VERSION || version > FORMAT_VERSION) {
            throw new InFlightJournalException(file, "it declares format version " + version
                + " and this build reads versions " + OLDEST_READABLE_VERSION + " to "
                + FORMAT_VERSION);
        }
        if (!InFlightJournalFormat.headerIntact(header, version)) {
            throw new InFlightJournalException(file, version < FIRST_CHECKSUMMED_VERSION
                ? "its first line names no format version this build can read: " + header
                : "its first line does not match its checksum, so it was altered after it was "
                    + "written: " + header);
        }

        records = replayRecords(lines, version, read.lastLineTerminated());
        return version;
    }

    /**
     * Applies every record after the header, returning how many were applied.
     *
     * <p>In version 1 a line this build cannot read is skipped wherever it is, as that version
     * has always been read. From version 2 only a torn last line is skipped; a complete line
     * that fails its checksum is refused, for the reason the class documentation gives.</p>
     */
    private int replayRecords(List<String> lines, int version, boolean lastTerminated) {
        boolean sealed = version >= FIRST_CHECKSUMMED_VERSION;
        int applied = 0;
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            boolean torn = index == lines.size() - 1 && !lastTerminated;
            String content = sealed ? InFlightJournalFormat.unseal(line) : line;
            if (content == null) {
                if (!torn) {
                    throw new InFlightJournalException(file, "line " + (index + 1)
                        + " does not match its checksum, so it was altered after it was written: "
                        + line);
                }
                skip(line);
                continue;
            }
            if (!apply(content)) {
                if (sealed && !torn) {
                    throw new InFlightJournalException(file, "line " + (index + 1)
                        + " matches its checksum and is no record this build writes: " + line);
                }
                skip(line);
                continue;
            }
            applied++;
        }
        return applied;
    }

    /**
     * Applies one record's content to the live set.
     *
     * @param content The record, without a seal
     * @return false if it is not a record
     */
    private boolean apply(String content) {
        if (content.length() < 2 || content.charAt(1) != SEPARATOR) {
            return false;
        }
        String documentKey = unescape(content.substring(2));
        if (content.charAt(0) == BEGAN) {
            inFlight.add(documentKey);
            return true;
        }
        if (content.charAt(0) == ENDED) {
            inFlight.remove(documentKey);
            return true;
        }
        return false;
    }

    private void skip(String line) {
        LOGGER.warn("Skipping an unreadable record in the in-flight document journal at {}: {}",
            file, line);
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
