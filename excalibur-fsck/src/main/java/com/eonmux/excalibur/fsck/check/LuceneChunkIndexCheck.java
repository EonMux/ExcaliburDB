package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndexIntegrity;
import com.eonmux.excalibur.retrieval.lexical.native_.DocumentErasureLog;

import org.apache.lucene.index.IndexWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Checks the Lucene chunk index, {@code lucene-chunks}: the lexical index a node configured with
 * the Lucene engine keeps in place of the native store.
 *
 * <p>Two things live in the directory. The index itself is judged whole by
 * {@link LexicalIndexIntegrity#verify}, which opens it from its commit point without a writer and
 * checks the footer of every file every segment is made of - the rule the node's own read-only
 * opens follow, and the one place that decides which of Lucene's refusals are damage. One verdict
 * speaks for every file there but the erasure log, because a file the commit does not name is one
 * Lucene never reads.</p>
 *
 * <p>The erasure log is the native store's format, written by the same class, and is verified
 * line by line by {@link DocumentErasureLog#inspect}, the parser the index opens it with. Its
 * damage is put to an operator rather than moved aside: every promise in it was forced to disk
 * before its caller was told the erasure was accepted, and a log that is not there owes
 * nothing.</p>
 *
 * <p>So is damage to the index. The node refuses to open a commit that fails its checksum, and a
 * reindex rebuilds the index from the chunk text in storage, but the index is not moved aside
 * automatically: it may still hold text an accepted erasure has not yet reclaimed, and a quarantine
 * would keep that text past the point the erasure promised it gone.</p>
 */
public final class LuceneChunkIndexCheck {

    private LuceneChunkIndexCheck() {
        throw new AssertionError("Utility class");
    }

    /**
     * Checks the index under a data directory, if one was ever committed there.
     *
     * <p>A node commits the index as it opens it, so a directory with anything in it but Lucene's
     * write lock and the erasure log has had an index written to it, and one with no commit point
     * is damaged. A directory holding only those two is one a node created and never committed:
     * the next open creates the index, and there is nothing yet to judge.</p>
     *
     * @param dataDirectory The data directory, or a snapshot's payload root
     * @return The verdict on the index, or empty when the directory holds none
     * @throws NullPointerException if dataDirectory is null
     */
    public static Optional<LuceneChunkInspection> inspect(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Path directory = dataDirectory.resolve(FileClassifier.LUCENE_CHUNK_DIRECTORY);
        if (!Files.isDirectory(directory) || !holdsIndexFiles(directory)) {
            return Optional.empty();
        }
        IntegrityReport report = LexicalIndexIntegrity.verify(directory);
        FileCheck check = IntegrityReports.from(report, directory,
            FileClassifier.LUCENE_CHUNK_DIRECTORY, null, -1);
        return Optional.of(new LuceneChunkInspection(new FileCheck(check.path(),
            check.relative(), check.format(), check.version(), check.status(), check.detail(),
            check.sizeBytes(), true)));
    }

    /**
     * Verifies the index's erasure log, every line against its own checksum.
     *
     * @param file     The log
     * @param relative Its path relative to the data directory
     * @return The verdict, with damage marked for an operator
     */
    public static FileCheck erasureLog(Path file, String relative) {
        IntegrityReport report = DocumentErasureLog.inspect(file);
        FileCheck check = IntegrityReports.from(report, file, relative, null, sizeOf(file));
        return new FileCheck(check.path(), check.relative(), check.format(), check.version(),
            check.status(), check.detail(), check.sizeBytes(), true);
    }

    /**
     * Reports whether a directory holds anything a commit could have written.
     *
     * @param directory The index directory
     * @return true if any regular file under it is neither the write lock nor the erasure log;
     *         true as well if it cannot be walked, so that the index check reports what it can
     */
    private static boolean holdsIndexFiles(Path directory) {
        try (var walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                .map(file -> directory.relativize(file).toString())
                .anyMatch(name -> !name.equals(IndexWriter.WRITE_LOCK_NAME)
                    && !name.equals(DocumentErasureLog.FILE));
        } catch (IOException | java.io.UncheckedIOException e) {
            return true;
        }
    }

    /**
     * Reads a file's size, answering -1 rather than failing the survey.
     *
     * @param file The file
     * @return Its size in bytes, or -1
     */
    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * What checking the Lucene chunk index found.
     *
     * @param index The verdict on the index, reported under the directory's own path
     */
    public record LuceneChunkInspection(FileCheck index) {

        /**
         * Canonical constructor.
         *
         * @throws NullPointerException if index is null
         */
        public LuceneChunkInspection {
            Objects.requireNonNull(index, "index");
        }

        /**
         * Reports whether a file is accounted for by the verdict on the index.
         *
         * <p>Every file under the directory is, but the erasure log, which is verified on its
         * own: the index is read whole from its commit point, and a file the commit does not
         * name is one Lucene never reads.</p>
         *
         * @param relative A file's path relative to the data directory, "/"-separated
         * @return true if the index's verdict speaks for it
         */
        public boolean covers(String relative) {
            return relative.startsWith(FileClassifier.LUCENE_CHUNK_DIRECTORY + "/")
                && FileClassifier.classify(relative) != FileKind.LUCENE_CHUNK_ERASURE_LOG;
        }
    }
}
