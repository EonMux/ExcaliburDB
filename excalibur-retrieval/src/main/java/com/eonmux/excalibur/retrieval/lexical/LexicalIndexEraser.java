package com.eonmux.excalibur.retrieval.lexical;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.erasure.ServedArtifactEraser;
import com.eonmux.excalibur.retrieval.build.GraphDirectoryBuilder;

import org.roaringbitmap.RoaringBitmap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes an erased entity from one of the Lucene indexes a graph is served beside.
 *
 * <h2>Why this lives here and not with the compaction</h2>
 * <p>A graph directory holds two Lucene indexes: {@code labels}, which entity linking searches,
 * and {@code documents}, which holds the prose each entity's snippets were copied out of and
 * which the lexical retrieval signal ranks. Both are written by this module, and
 * {@code excalibur-graph} depends on nothing that can read them - which is why it asks for a
 * {@link ServedArtifactEraser} instead of opening them itself. Without one it reports both
 * indexes as unreached and leaves the obligation outstanding, which is honest and is not an
 * erasure.
 *
 * <h2>Deleted, then merged</h2>
 * <p>Deleting a Lucene document marks it invisible; the stored text stays in the segment file
 * until a merge happens to rewrite that segment. For an erasure obligation that is a filter
 * rather than a removal, so the delete is followed by
 * {@link LexicalIndex#forceMergeDeletes()}, which rewrites every segment holding a deletion and
 * is expensive by nature. The price is what makes the difference between the data being hidden
 * and the data being gone.
 *
 * <h2>How the served copy is left alone</h2>
 * <p>The index is reproduced in the replacement directory and opened <em>there</em>; the served
 * copy is only ever read. That is the compaction's crash guarantee: the served directory stays
 * complete and readable up to one atomic swap.
 *
 * <p>Reproduced by hard link where the filesystem allows it. A committed Lucene file is never
 * written to again - new segments get new names and merges delete the files they replaced - so
 * the two directories share bytes that nothing will modify, and a merge in the replacement
 * unlinks only the replacement's name. What frees the erased bytes is the compaction deleting
 * the retired directory afterwards, which drops the last link to a segment the merge replaced.
 * A filesystem that cannot link is copied instead, which costs the bytes and changes nothing
 * else.
 *
 * @author ExcaliburDB
 */
public final class LexicalIndexEraser implements ServedArtifactEraser {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexicalIndexEraser.class);

    /**
     * Lucene's writer lock, which is the one file in the directory that is not content.
     *
     * <p>Not reproduced: it says that some writer holds this directory, and the replacement is a
     * directory no writer has ever held. Carrying it across would hand the new index a lock file
     * describing the old one's writer.
     */
    private static final String WRITE_LOCK = "write.lock";

    private final String name;

    /**
     * Whether this index holds names rather than prose.
     *
     * <p>The difference decides which scopes the index can answer, which is the whole of
     * {@link #reaches}. An index of names holds one string per entity and nothing derived from
     * any document, so a withdrawn source covers nothing in it; an index of entity documents
     * holds text copied out of many sources at once, so it does.
     */
    private final boolean namesOnly;

    private final double ramBufferMb;

    private LexicalIndexEraser(String name, boolean namesOnly, double ramBufferMb) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (ramBufferMb <= 0) {
            throw new IllegalArgumentException(
                "ramBufferMb must be positive, got " + ramBufferMb);
        }
        this.name = name;
        this.namesOnly = namesOnly;
        this.ramBufferMb = ramBufferMb;
    }

    /**
     * An eraser for the index of entity labels that entity linking searches.
     *
     * @param ramBufferMb Lucene write buffer for the rewrite, in megabytes
     * @return The eraser
     * @throws IllegalArgumentException if {@code ramBufferMb} is not positive
     */
    public static LexicalIndexEraser labels(double ramBufferMb) {
        return new LexicalIndexEraser(GraphDirectoryBuilder.LABEL_INDEX, true, ramBufferMb);
    }

    /**
     * An eraser for the index of entity documents the lexical retrieval signal ranks.
     *
     * @param ramBufferMb Lucene write buffer for the rewrite, in megabytes
     * @return The eraser
     * @throws IllegalArgumentException if {@code ramBufferMb} is not positive
     */
    public static LexicalIndexEraser documents(double ramBufferMb) {
        return new LexicalIndexEraser(GraphDirectoryBuilder.DOCUMENT_INDEX, false, ramBufferMb);
    }

    @Override
    public Set<String> names() {
        return Set.of(name);
    }

    /**
     * {@inheritDoc}
     *
     * <p>An index of labels answers every scope: an entity-scoped request has its document
     * deleted here, and no other scope covers anything in the file, because withdrawing a
     * document must not take the names of everyone it happened to mention with it. That is the
     * same rule the entity dictionary follows, and the two have to agree.
     *
     * <p>An index of entity documents answers an entity scope only. Its document for an entity
     * is prose accumulated from every source that mentioned them, so withdrawing one source
     * leaves the rest of that text in place and deleting the whole document would remove prose
     * nobody asked to erase. The only way to reach it is to rebuild the index from staging the
     * erasure has already cleaned, which needs the staging store this eraser does not have, so
     * it reports the gap instead of letting the request be marked discharged.
     */
    @Override
    public boolean reaches(ErasureScope scope) {
        return namesOnly || scope instanceof ErasureScope.Entity;
    }

    @Override
    public void erase(Path served, Path replacement, RoaringBitmap erasedEntities)
            throws IOException {
        Path from = served.resolve(name);
        if (!Files.isDirectory(from)) {
            // Nothing claimed, nothing produced. Reporting the name as rebuilt when the served
            // directory never held it would tell the carry-over to skip something real.
            return;
        }
        Path to = replacement.resolve(name);
        reproduce(from, to);
        if (erasedEntities.isEmpty()) {
            return;
        }
        deleteAndMerge(to, erasedEntities);
    }

    /**
     * Deletes the erased entities from the reproduced index and rewrites what held them.
     *
     * @param index          The index in the replacement directory
     * @param erasedEntities The ids whose documents must not survive
     * @throws IOException if the index cannot be opened, written or merged
     */
    private void deleteAndMerge(Path index, RoaringBitmap erasedEntities) throws IOException {
        int deleted = 0;
        try (LexicalIndex open = LexicalIndex.open(index, ramBufferMb)) {
            for (int id : erasedEntities) {
                open.delete(id);
                deleted++;
            }
            // Not commit(). A deleted document is invisible and still on disk, and this class
            // exists because the second half of that is the half an erasure is about.
            open.forceMergeDeletes();
        }
        LOGGER.info("deleted {} entity document(s) from the {} index at {} and rewrote every "
            + "segment that held them", deleted, name, index);
    }

    /**
     * Puts the served index into the replacement directory without opening it for writing.
     *
     * @param from The served index
     * @param to   Where the replacement's copy goes
     * @throws IOException if an entry can be neither linked nor copied
     */
    private static void reproduce(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (var entries = Files.list(from)) {
            for (Path entry : entries.toList()) {
                String entryName = entry.getFileName().toString();
                if (WRITE_LOCK.equals(entryName)) {
                    continue;
                }
                if (Files.isDirectory(entry)) {
                    // A Lucene directory is flat. Anything else under it was put there by
                    // something else, and carrying it across beats deciding it does not matter.
                    reproduce(entry, to.resolve(entryName));
                    continue;
                }
                link(entry, to.resolve(entryName));
            }
        }
    }

    private static void link(Path source, Path target) throws IOException {
        try {
            Files.createLink(target, source);
        } catch (IOException | UnsupportedOperationException e) {
            LOGGER.debug("no hard link for {} ({}); copying instead", source, e.toString());
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    @Override
    public String toString() {
        return "LexicalIndexEraser[" + name + "]";
    }
}
