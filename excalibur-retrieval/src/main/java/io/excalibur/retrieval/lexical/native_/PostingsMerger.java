package io.excalibur.retrieval.lexical.native_;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Merges several built indexes into one.
 *
 * <h2>Why a merge and not a bigger build</h2>
 * <p>A single-pass build holds every term's postings in memory until it can write them in term
 * order, so the corpus it can index is bounded by the heap rather than by the disk. That is fine
 * for a segment and useless for a corpus: 165 GiB of text does not fit anywhere. Building in
 * bounded pieces and merging them makes the ceiling the disk's, which is the only ceiling that
 * scales.</p>
 *
 * <h2>Why doc ids need only an offset</h2>
 * <p>Each piece numbers its documents from zero. Concatenating the pieces in build order and
 * adding the running document count to each piece's ids produces globally ascending ids without
 * renumbering anything - and, because the pieces are visited in that same order, every merged
 * postings row comes out ascending for free. A merge that had to sort would be a different and
 * much more expensive operation.</p>
 *
 * <h2>Memory</h2>
 * <p>The merge holds one term's postings at a time and streams each straight to disk, so its cost
 * is the longest postings row plus four bytes for every document's length. An earlier version
 * accumulated into the ordinary writer, which held every row until the end: it merged correctly
 * and then ran out of heap on a 793 MiB corpus, having successfully flushed 38 pieces first. A
 * merge that reintroduces the ceiling it exists to remove is worse than no merge, because it
 * fails later and looks like it worked.</p>
 */
public final class PostingsMerger {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostingsMerger.class);

    private PostingsMerger() {
        throw new AssertionError("Utility class");
    }

    /**
     * Merges indexes into a new one.
     *
     * @param sources     Index directories, in the order their documents were added
     * @param destination Where to write the merged index
     * @throws IOException if any index cannot be read or the result cannot be written
     */
    public static void merge(List<Path> sources, Path destination) throws IOException {
        Objects.requireNonNull(sources, "sources must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("nothing to merge");
        }

        List<PostingsIndex> open = new ArrayList<>(sources.size());
        try {
            for (Path source : sources) {
                open.add(PostingsIndex.open(source));
            }

            int[] docOffset = new int[open.size()];
            int running = 0;
            for (int i = 0; i < open.size(); i++) {
                docOffset[i] = running;
                running += open.get(i).docCount();
            }

            // Document lengths, concatenated in piece order. Four bytes a document is the
            // one thing a merge must hold for the whole corpus, because a block's impact bound
            // cannot be computed without the length of every document in it.
            int[] lengths = new int[running];
            int at = 0;
            for (PostingsIndex index : open) {
                for (int length : index.documentLengths()) {
                    lengths[at++] = length;
                }
            }

            int[] cursor = new int[open.size()];
            String[] head = new String[open.size()];
            for (int i = 0; i < open.size(); i++) {
                head[i] = open.get(i).termCount() > 0 ? open.get(i).termAt(0) : null;
            }

            long mergedTerms = 0;
            int[] docs = new int[1024];
            int[] freqs = new int[1024];
            try (StreamingIndexWriter writer = new StreamingIndexWriter(destination, lengths)) {
                while (true) {
                    String smallest = null;
                    for (String candidate : head) {
                        if (candidate != null
                                && (smallest == null || candidate.compareTo(smallest) < 0)) {
                            smallest = candidate;
                        }
                    }
                    if (smallest == null) {
                        break;
                    }
                    // One term's postings, gathered from every piece that has it in piece order
                    // so the merged row is ascending without a sort, then written straight out.
                    int size = 0;
                    for (int i = 0; i < open.size(); i++) {
                        if (!smallest.equals(head[i])) {
                            continue;
                        }
                        PostingsIndex.Row row = open.get(i).row(cursor[i]);
                        if (size + row.docs().length > docs.length) {
                            int grown = Math.max(docs.length * 2, size + row.docs().length);
                            docs = java.util.Arrays.copyOf(docs, grown);
                            freqs = java.util.Arrays.copyOf(freqs, grown);
                        }
                        for (int d = 0; d < row.docs().length; d++) {
                            docs[size] = row.docs()[d] + docOffset[i];
                            freqs[size] = row.freqs()[d];
                            size++;
                        }
                        cursor[i]++;
                        head[i] = cursor[i] < open.get(i).termCount()
                            ? open.get(i).termAt(cursor[i]) : null;
                    }
                    writer.addTerm(smallest, docs, freqs, size);
                    mergedTerms++;
                }
                writer.finish();
            }
            LOGGER.info("Merged {} indexes into {} terms and {} documents",
                open.size(), mergedTerms, running);
        } finally {
            for (PostingsIndex index : open) {
                index.close();
            }
        }
    }
}
