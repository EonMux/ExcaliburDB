package com.eonmux.excalibur.retrieval.lexical.native_;

import com.eonmux.excalibur.retrieval.lexical.Bm25Parameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Indexes a corpus of any size by building bounded pieces and merging them.
 *
 * <h2>The ceiling this removes</h2>
 * <p>A single build accumulates every postings row in memory so it can emit them in term order,
 * which makes the largest indexable corpus a function of the heap. This flushes a piece whenever
 * the accumulated postings reach a budget and merges the pieces at the end, so the corpus is
 * bounded by disk instead. The result is byte-for-byte what a single build would have produced -
 * a property the merge tests assert directly, because a merge that is merely close produces an
 * index quietly missing documents.</p>
 *
 * <h2>Merging in rounds</h2>
 * <p>Pieces are merged a few at a time rather than all at once. A merge holds one term's postings
 * from every input it is reading, so merging a thousand pieces in one pass would reintroduce the
 * memory ceiling in a different place; merging in rounds keeps the working set to
 * {@link #MERGE_FAN_IN} pieces however many there are.</p>
 */
public final class SegmentedIndexBuilder implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentedIndexBuilder.class);

    /** Pieces combined in one merge pass. */
    public static final int MERGE_FAN_IN = 8;

    /**
     * Postings held before a piece is flushed.
     *
     * <p>Roughly 16 bytes a posting once the per-term maps and growth slack are counted, so eight
     * million is on the order of a gigabyte of heap - large enough that pieces stay few and small
     * enough to leave room for the rest of the process.</p>
     */
    public static final long DEFAULT_POSTINGS_BUDGET = 8_000_000L;

    private final Path workDirectory;
    private final long postingsBudget;
    private final Bm25Parameters bm25;
    private final List<Path> pieces = new ArrayList<>();

    private PostingsWriter current;
    private long postingsHeld;
    private int documents;

    public SegmentedIndexBuilder(Path workDirectory) throws IOException {
        this(workDirectory, DEFAULT_POSTINGS_BUDGET, Bm25Parameters.DEFAULT);
    }

    public SegmentedIndexBuilder(Path workDirectory, long postingsBudget) throws IOException {
        this(workDirectory, postingsBudget, Bm25Parameters.DEFAULT);
    }

    /** A builder whose pieces, merges and finished index all use one BM25 pair. */
    public SegmentedIndexBuilder(Path workDirectory, Bm25Parameters bm25) throws IOException {
        this(workDirectory, DEFAULT_POSTINGS_BUDGET, bm25);
    }

    /**
     * @param workDirectory  Where intermediate pieces are written
     * @param postingsBudget Postings held before a piece is flushed
     * @param bm25           The pair every piece and the finished index is built with
     */
    public SegmentedIndexBuilder(Path workDirectory, long postingsBudget, Bm25Parameters bm25)
            throws IOException {
        this.workDirectory = Objects.requireNonNull(workDirectory, "workDirectory");
        this.bm25 = Objects.requireNonNull(bm25, "bm25");
        if (postingsBudget < 1) {
            // Only a non-positive budget is actually invalid. A small one merely means many
            // small pieces and more merging, which is the arrangement a constrained heap
            // genuinely produces - and refusing it made the merge rounds untestable at the very
            // shape they exist to handle.
            throw new IllegalArgumentException("postingsBudget must be positive: " + postingsBudget);
        }
        this.postingsBudget = postingsBudget;
        this.current = new PostingsWriter(bm25);
        Files.createDirectories(workDirectory);
    }

    /**
     * Adds a document, flushing a piece first if the budget is reached.
     *
     * @param text The document text
     * @return The document's id in the finished index
     * @throws IOException if a flush fails
     */
    public int add(String text) throws IOException {
        int local = current.add(text);
        // Distinct terms, not tokens: a posting is one term in one document however often it
        // occurs there, which is what the writer actually holds.
        postingsHeld += Math.max(1, Tokens.of(text).stream().distinct().count());
        int global = documents;
        documents++;
        if (postingsHeld >= postingsBudget) {
            flush();
        }
        return global;
    }

    /** Documents added so far. */
    public int documentCount() {
        return documents;
    }

    /** Pieces flushed so far. */
    public int pieceCount() {
        return pieces.size();
    }

    private void flush() throws IOException {
        if (current.docCount() == 0) {
            return;
        }
        Path piece = workDirectory.resolve("piece-" + pieces.size());
        current.write(piece);
        pieces.add(piece);
        LOGGER.info("Flushed piece {} with {} documents", pieces.size() - 1, current.docCount());
        current = new PostingsWriter(bm25);
        postingsHeld = 0;
    }

    /**
     * Finishes the index.
     *
     * @param destination Where the finished index is written
     * @throws IOException if writing or merging fails
     */
    public void build(Path destination) throws IOException {
        flush();
        if (pieces.isEmpty()) {
            new PostingsWriter(bm25).write(destination);
            return;
        }
        if (pieces.size() == 1) {
            // Nothing to merge; move the single piece into place rather than rewriting it.
            moveDirectory(pieces.get(0), destination);
            pieces.clear();
            return;
        }

        List<Path> round = new ArrayList<>(pieces);
        int generation = 0;
        while (round.size() > MERGE_FAN_IN) {
            List<Path> next = new ArrayList<>();
            for (int i = 0; i < round.size(); i += MERGE_FAN_IN) {
                List<Path> group = round.subList(i, Math.min(round.size(), i + MERGE_FAN_IN));
                if (group.size() == 1) {
                    next.add(group.get(0));
                    continue;
                }
                Path merged = workDirectory.resolve("merge-" + generation + "-" + i);
                PostingsMerger.merge(group, merged, bm25);
                next.add(merged);
            }
            LOGGER.info("Merge round {}: {} pieces to {}", generation, round.size(), next.size());
            round = next;
            generation++;
        }
        PostingsMerger.merge(round, destination, bm25);
    }

    /**
     * Moves a finished piece into place as the index itself.
     *
     * <p>The piece's own files were forced where they were written, but every one of them now
     * has a new name in a directory that has never been forced, and a name that is not durable
     * is a file the index does not have. The merge paths end in a write that forces their
     * destination; this path has no write left to do, so it forces it here.</p>
     */
    private static void moveDirectory(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> files = Files.list(from)) {
            for (Path file : files.toList()) {
                Files.move(file, to.resolve(file.getFileName()),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Durability.FILE_SYSTEM.forceDirectory(to);
    }

    /** Removes the intermediate pieces. */
    @Override
    public void close() {
        try (Stream<Path> entries = Files.walk(workDirectory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOGGER.debug("Could not remove {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOGGER.debug("Could not clean {}: {}", workDirectory, e.getMessage());
        }
    }
}
