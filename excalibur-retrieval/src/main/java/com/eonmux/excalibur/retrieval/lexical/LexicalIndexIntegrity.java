package com.eonmux.excalibur.retrieval.lexical;

import com.eonmux.excalibur.core.integrity.IntegrityReport;

import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The rules for checking a {@link LexicalIndex} opened to serve what something else wrote, shared
 * by {@link LexicalIndex#openReadOnly} and by any tool that surveys one.
 *
 * <h2>What Lucene already carries</h2>
 * <p>Every file Lucene writes ends in a footer holding the CRC32 of everything before it. Opening
 * a reader checks the footers of the small files it reads whole - the commit point, each segment's
 * info, field infos and deletions - but not of the postings, stored fields and doc values it
 * reads lazily, because checking those means reading them. A flipped bit there is an answer with
 * a wrong document behind it, found only if a merge ever happens to reread the bytes, and a
 * published graph's indexes are never merged. So an index opened read-only additionally checks
 * every segment's footers before it serves, which is one sequential pass over its files: the
 * policy the lexical segments, the HNSW graphs and the graph's own files follow.</p>
 *
 * <h2>Damage against unreadable</h2>
 * <p>{@link #isDamage} is the one place that decides which of Lucene's refusals say the bytes are
 * not the ones written - a checksum mismatch, a file the commit names that is not there, a file
 * that ends early, or no commit at all - as against a format this build does not read or a device
 * that refused a read. A caller replacing a damaged index and an inspection reporting one then
 * agree by construction.</p>
 *
 * <h2>Read-only</h2>
 * <p>{@link #verify} reads through {@link NIOFSDirectory}, which maps nothing, and opens no
 * writer, so it takes no lock and changes nothing: it can be pointed at a live data directory or
 * a snapshot's payload.</p>
 */
public final class LexicalIndexIntegrity {

    /** Name an index goes by in a report. */
    public static final String FORMAT = "lucene index";

    private LexicalIndexIntegrity() {
        throw new AssertionError("Utility class");
    }

    /**
     * Checks the footer of every file of every segment a reader holds.
     *
     * @param reader The reader, as opened
     * @throws CorruptIndexException if a file does not match its footer
     * @throws IOException if a file cannot be read
     */
    static void requireIntact(DirectoryReader reader) throws IOException {
        for (LeafReaderContext leaf : reader.leaves()) {
            leaf.reader().checkIntegrity();
        }
    }

    /**
     * Whether a refusal from Lucene says the index's bytes are not the ones written.
     *
     * @param failure What opening or checking the index threw
     * @return true for damage: a checksum mismatch, a missing or short file, or no commit at all
     */
    public static boolean isDamage(IOException failure) {
        return failure instanceof CorruptIndexException
            || failure instanceof IndexNotFoundException
            || failure instanceof NoSuchFileException
            || failure instanceof FileNotFoundException
            || failure instanceof EOFException;
    }

    /**
     * Reaches a verdict on an index, refusing nothing.
     *
     * <p>One verdict covers every file under the directory: a Lucene index is read as a whole
     * from its commit point, and a file the commit does not name is one it never reads.</p>
     *
     * @param indexDirectory The directory holding the index
     * @return The report, on the directory
     * @throws NullPointerException if {@code indexDirectory} is null
     */
    public static IntegrityReport verify(Path indexDirectory) {
        Objects.requireNonNull(indexDirectory, "indexDirectory must not be null");
        if (!Files.isDirectory(indexDirectory)) {
            return IntegrityReport.missing(indexDirectory, FORMAT);
        }
        try (Directory directory = new NIOFSDirectory(indexDirectory);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            requireIntact(reader);
            return IntegrityReport.intact(indexDirectory, FORMAT, reader.leaves().size()
                + " segment(s) holding " + reader.maxDoc() + " document(s): every file matched "
                + "the checksum in its footer");
        } catch (IndexFormatTooNewException | IndexFormatTooOldException e) {
            return IntegrityReport.unreadable(indexDirectory, FORMAT, "written in a Lucene "
                + "format this build does not read, and the node refuses to open it: "
                + e.getMessage());
        } catch (IndexNotFoundException e) {
            return IntegrityReport.corrupt(indexDirectory, FORMAT, "no commit point: the "
                + "directory holds no index the node could open. " + e.getMessage());
        } catch (IOException e) {
            if (isDamage(e)) {
                return IntegrityReport.corrupt(indexDirectory, FORMAT, "the index is not the "
                    + "one written, and the node refuses to serve it: " + e);
            }
            return IntegrityReport.unreadable(indexDirectory, FORMAT, e.toString());
        }
    }
}
