package com.eonmux.excalibur.retrieval.lexical;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import com.eonmux.excalibur.core.memory.MappedFootprint;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.MMapDirectory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An embedded Lucene inverted index over document text, scored with BM25.
 *
 * <p>Lucene rather than a hand-rolled BM25. The formula is a few lines; everything around it is not
 * — tokenisation and analysis, length normalisation, term statistics that stay correct as segments
 * merge, a postings format that does not read the whole index to answer a term query, and the
 * concurrency to do all of that while documents are being added. A reimplementation would be a
 * worse BM25 that also has to be maintained.
 *
 * <h2>Memory</h2>
 * Lucene works on the heap while the vector index deliberately does not, so the two compete for
 * different budgets and both have to be sized. {@code ramBufferSizeMB} caps what indexing holds
 * before flushing; searching is served through the page cache like the mapped columns are. Leaving
 * the default and also sizing the heap for vectors is how a process ends up swapping.
 *
 * <h2>Visibility</h2>
 * A reader sees the index as of the last {@link #refresh()}. Documents added since are not
 * searchable, which is the same near-real-time contract the rest of the engine has, and is why
 * refresh is explicit rather than implied by every write.
 */
public final class LexicalIndex implements AutoCloseable, MappedFootprint {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexicalIndex.class);

    /**
     * Field holding the analysed text that BM25 scores against, stored so it can be read back.
     *
     * <p>Stored because rerank has to show a model the text it is judging, and an analysed field
     * cannot supply it: postings hold terms, not the string they came from, so a text-only index
     * can be searched and never quoted. The alternative is a second copy of the corpus outside the
     * index, which drifts from it - a document replaced here but not there is then served under
     * another document's text.
     *
     * <p>It costs one compressed copy of the corpus on disk. That is the price of being able to
     * rerank at all, and it is paid on disk rather than on the heap that indexing and the vector
     * store are already competing for.
     *
     * <p>Read through {@link #textOf(long)} rather than by fetching stored fields directly, so
     * that callers do not depend on which fields a hit happens to carry.
     */
    public static final String FIELD_TEXT = "text";

    /**
     * Field holding the engine's own document id.
     *
     * <p>Written three ways, each for a different job. Indexed as a {@link StringField} so that
     * {@code updateDocument} and {@code deleteDocuments} can match a {@link Term} against it —
     * stored and doc-values fields are not searchable, so a term built from them matches nothing
     * and a replace silently becomes an append. Stored so a hit can be mapped back to the engine's
     * id. And kept as doc values so a scorer can read it without a stored-field fetch per hit.
     *
     * <p>Lucene's internal doc ids are not stable across merges and must never leak past this
     * class.
     */
    public static final String FIELD_DOC_ID = "doc_id";

    /**
     * Field holding the caller's own identifier for the document.
     *
     * <p>The engine keys documents by a long, which is what the whole ranking and fusion stack
     * is built on. A corpus chunk's real name is a string ({@code source:document:chunk:n}), and
     * the long is derived from it by hashing, which is not invertible. Storing the original here
     * is what lets a hit be reported as the identifier a caller can actually look something up
     * by, and what lets a hash collision be seen rather than silently serve one chunk's text
     * under another chunk's name.</p>
     */
    public static final String FIELD_EXTERNAL_KEY = "external_key";

    /** The only stored field {@link #textOf(long)} needs. */
    private static final Set<String> TEXT_ONLY = Set.of(FIELD_TEXT);

    /** The only stored field {@link #externalKeyOf(long)} needs. */
    private static final Set<String> KEY_ONLY = Set.of(FIELD_EXTERNAL_KEY);

    private final Directory directory;
    private final Analyzer analyzer;
    private final IndexWriter writer;
    private final Bm25Parameters bm25;
    /**
     * The reader searches go through and the searcher over it, published together.
     *
     * <p>One reference rather than two so that a version read and a search run against it can
     * never straddle a refresh: a caller reading the version from one snapshot and searching
     * another later sees at least that version, never an older one under a newer label.</p>
     */
    private final AtomicReference<Snapshot> published = new AtomicReference<>();

    /**
     * A published reader, its searcher, and its version.
     *
     * <p>The version is read once, at publication: Lucene refuses to report it from a reader a
     * refresh has since given up, and a caller asking which version is served should not fail
     * because a refresh happened to finish at that moment.</p>
     */
    private record Snapshot(DirectoryReader reader, IndexSearcher searcher, long version) { }

    private LexicalIndex(Directory directory, Analyzer analyzer, IndexWriter writer,
                         Bm25Parameters bm25) {
        this.directory = directory;
        this.analyzer = analyzer;
        this.writer = writer;
        this.bm25 = bm25;
    }

    /**
     * Lucene's BM25 at this pair.
     *
     * <p>Built fresh for the writer and for each searcher rather than shared, because a
     * similarity is attached to the object that uses it and Lucene makes no promise about
     * reusing one across both.
     */
    private static BM25Similarity similarityOf(Bm25Parameters bm25) {
        return new BM25Similarity((float) bm25.k1(), (float) bm25.b());
    }

    /** Opens, or creates, an index under {@code path}, scored with the default BM25 pair. */
    public static LexicalIndex open(Path path, double ramBufferSizeMB) throws IOException {
        return open(path, ramBufferSizeMB, Bm25Parameters.DEFAULT);
    }

    /**
     * Opens, or creates, an index under {@code path}.
     *
     * <p>Unlike the engine's own postings index, the pair is purely a scoring choice here: what
     * Lucene writes at index time is the field length, which {@code k1} and {@code b} do not
     * enter into. Changing them therefore takes effect on the next query rather than on the next
     * reingest.
     *
     * @param ramBufferSizeMB how much heap indexing may hold before flushing to disk. Sized
     *                        deliberately, because this heap is not available to anything else in
     *                        the process.
     * @param bm25            what BM25 scores with, from {@code excalibur.ingest.bm25-k1} and
     *                        {@code excalibur.ingest.bm25-b}
     */
    public static LexicalIndex open(Path path, double ramBufferSizeMB, Bm25Parameters bm25)
            throws IOException {
        if (ramBufferSizeMB <= 0) {
            throw new IllegalArgumentException(
                "ramBufferSizeMB must be positive, got " + ramBufferSizeMB);
        }
        Objects.requireNonNull(bm25, "bm25 must not be null");
        Directory directory = FSDirectory.open(path);
        Analyzer analyzer = new StandardAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        config.setRAMBufferSizeMB(ramBufferSizeMB);
        config.setSimilarity(similarityOf(bm25));
        config.setMergePolicy(erasureCapableMergePolicy());

        IndexWriter writer = new IndexWriter(directory, config);
        LexicalIndex index = new LexicalIndex(directory, analyzer, writer, bm25);
        try {
            index.refresh();
        } catch (IOException e) {
            index.close();
            throw e;
        }
        return index;
    }

    /**
     * Opens an existing index for searching only, scored with the default BM25 pair.
     *
     * <p>For an index something else wrote and nothing here will change, such as a published
     * graph's. No writer is opened, so no write lock is taken: a replacement can be opened at the
     * same path while this one is still being read, and a reader draining against a directory
     * that was renamed away keeps the files it already holds.</p>
     *
     * <p>An absent index is an error rather than an empty one. Opening a published artifact that
     * is not there means the artifact is damaged, and creating an empty index in its place would
     * serve every query from nothing without saying so.</p>
     *
     * @param path Where the index is
     * @return The index, searchable at once
     * @throws org.apache.lucene.index.IndexNotFoundException if there is no index at {@code path}
     * @throws IOException if the index cannot be read
     */
    public static LexicalIndex openReadOnly(Path path) throws IOException {
        Directory directory = FSDirectory.open(path);
        LexicalIndex index =
            new LexicalIndex(directory, new StandardAnalyzer(), null, Bm25Parameters.DEFAULT);
        try {
            index.refresh();
        } catch (IOException | RuntimeException e) {
            index.close();
            throw e;
        }
        return index;
    }

    /**
     * The default merge policy, with the one setting that makes {@link #forceMergeDeletes()} mean
     * what it says.
     *
     * <p>{@code TieredMergePolicy} refuses to rewrite a segment for deletions below
     * {@code forceMergeDeletesPctAllowed}, which defaults to a tenth. That is a sound default for
     * a caller reclaiming space - rewriting a whole segment to remove one document out of a
     * thousand is a poor trade - and the wrong answer for a caller who has to be able to say the
     * text is gone. One erased chunk in a large index is under the threshold from the moment it
     * is erased, so a force-merge would report success having rewritten nothing, and the chunk
     * would sit in its segment indefinitely.
     *
     * <p>Only the forced path is changed. Ordinary merges keep their default schedule, so a
     * deletion still costs nothing until something asks for it to be applied.
     */
    private static MergePolicy erasureCapableMergePolicy() {
        TieredMergePolicy policy = new TieredMergePolicy();
        policy.setForceMergeDeletesPctAllowed(0.0);
        return policy;
    }

    /**
     * Adds or replaces the text of one document.
     *
     * <p>Replaces by {@link #FIELD_DOC_ID} rather than appending, so re-ingesting a document does
     * not leave the previous version in the index competing with it.
     */
    public void put(long documentId, String text) throws IOException {
        put(documentId, text, null);
    }

    /**
     * Adds or replaces a document, recording the caller's own identifier for it.
     *
     * @param documentId  The engine's id, which ranking and fusion use
     * @param text        The text to index and store
     * @param externalKey The caller's identifier, or null when the engine id is the only one
     * @throws IOException if the write fails
     */
    public void put(long documentId, String text, String externalKey) throws IOException {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null for document " + documentId);
        }
        Document document = new Document();
        if (externalKey != null) {
            document.add(new StoredField(FIELD_EXTERNAL_KEY, externalKey));
        }
        // Stored as well as indexed. Without the stored copy nothing downstream can read a
        // document's text back out, and rerank has nothing to send the model.
        document.add(new TextField(FIELD_TEXT, text, Field.Store.YES));
        // Indexed, un-analysed, so the Term below actually matches an existing version.
        document.add(new StringField(FIELD_DOC_ID, Long.toString(documentId), Field.Store.NO));
        document.add(new StoredField(FIELD_DOC_ID, documentId));
        document.add(new NumericDocValuesField(FIELD_DOC_ID, documentId));
        writable().updateDocument(new Term(FIELD_DOC_ID, Long.toString(documentId)), document);
    }

    /**
     * Returns the caller's identifier for a document, or null when it stored none.
     *
     * @param documentId The engine's id
     * @return The external key, or null
     * @throws IOException if the read fails
     */
    public String externalKeyOf(long documentId) throws IOException {
        try (Lease lease = acquire()) {
            IndexSearcher current = lease.searcher();
            TopDocs hits = current.search(
                new org.apache.lucene.search.TermQuery(
                    new Term(FIELD_DOC_ID, Long.toString(documentId))), 1);
            if (hits.scoreDocs.length == 0) {
                return null;
            }
            Document stored = current.storedFields().document(hits.scoreDocs[0].doc, KEY_ONLY);
            return stored == null ? null : stored.get(FIELD_EXTERNAL_KEY);
        }
    }

    /** Removes a document. */
    public void delete(long documentId) throws IOException {
        writable().deleteDocuments(new Term(FIELD_DOC_ID, Long.toString(documentId)));
    }

    /** Flushes pending writes to disk without making them searchable. */
    public void commit() throws IOException {
        writable().commit();
    }

    /**
     * Rewrites every segment holding a deleted document, so the deleted text leaves disk.
     *
     * <p>Lucene's ordinary merges reclaim deletions on their own schedule, which is the right
     * default and the wrong answer for a caller who has to be able to say the data is gone. This
     * is that caller's escape hatch, and it is expensive by nature: a segment is rewritten whole
     * to remove one document from it.</p>
     *
     * @return How many documents were deleted before the merge, which is what it reclaimed
     */
    public int forceMergeDeletes() throws IOException {
        Snapshot current = published.get();
        int pending = current == null ? 0 : current.reader().numDeletedDocs();
        IndexWriter open = writable();
        open.forceMergeDeletes(true);
        open.commit();
        return pending;
    }

    /**
     * Makes everything written so far searchable.
     *
     * <p>Commits first: a reader opened on uncommitted state would see documents that a crash would
     * lose, so a search could return a result that no longer exists after a restart. A read-only
     * index has nothing to commit and picks up whatever was last committed.
     */
    public void refresh() throws IOException {
        if (writer != null) {
            writer.commit();
        }
        Snapshot current = published.get();
        DirectoryReader refreshed = current == null
            ? DirectoryReader.open(directory)
            : DirectoryReader.openIfChanged(current.reader());

        if (refreshed == null) {
            return;
        }
        IndexSearcher newSearcher = new IndexSearcher(refreshed);
        newSearcher.setSimilarity(similarityOf(bm25));
        published.set(new Snapshot(refreshed, newSearcher, refreshed.getVersion()));
        if (current != null) {
            // Gives up the index's own reference. The reader closes once the searches leasing it
            // have finished, not under them.
            current.reader().decRef();
        }
    }

    /**
     * Holds the index as of the last refresh open for the length of one search.
     *
     * <p>A {@link #refresh()} replaces the reader and gives up the index's own reference to the
     * old one; a search that took a lease keeps it open until the lease is closed, so a refresh
     * never closes files under a search that is reading them.</p>
     *
     * @return The lease; close it when the search is done
     * @throws IllegalStateException if the index has never been refreshed, or is closed
     */
    public Lease acquire() {
        return tryAcquire().orElseThrow(() -> new IllegalStateException(
            "lexical index has no searcher: it is closed, or has never been refreshed"));
    }

    /**
     * As {@link #acquire()}, for a caller with its own answer to an index that cannot be searched.
     *
     * @return The lease, or empty when the index has never been refreshed or is closed
     */
    public java.util.Optional<Lease> tryAcquire() {
        while (true) {
            Snapshot current = published.get();
            if (current == null) {
                return java.util.Optional.empty();
            }
            if (current.reader().tryIncRef()) {
                return java.util.Optional.of(new Lease(current));
            }
            // A refresh gave the reader up between the read and the increment; its replacement
            // is already published.
        }
    }

    /**
     * One search's hold on a published reader.
     *
     * <p>Closing it twice releases it once.</p>
     */
    public static final class Lease implements AutoCloseable {

        private final Snapshot snapshot;
        private final java.util.concurrent.atomic.AtomicBoolean released =
            new java.util.concurrent.atomic.AtomicBoolean();

        private Lease(Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        /**
         * The searcher to run the search with.
         *
         * @return The searcher over the leased reader
         */
        public IndexSearcher searcher() {
            return snapshot.searcher();
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                try {
                    snapshot.reader().decRef();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException("could not release a lexical reader", e);
                }
            }
        }
    }

    /**
     * The stored text of one document.
     *
     * <p>Sees the index as of the last {@link #refresh()}, like every other read here, so a
     * document written since is not visible yet.
     *
     * <p>Null for a document the index does not hold, and also for one written before
     * {@link #FIELD_TEXT} was stored: an older index kept no copy of the text, and returning
     * something reconstructed from its terms would hand a reranker a sentence the corpus never
     * contained. Re-indexing populates it.
     *
     * @param documentId the engine's document id, as passed to {@link #put}
     * @return the text, or null if this index has none for that document
     * @throws IOException if the index cannot be read
     * @throws IllegalStateException if the index has never been refreshed
     */
    public String textOf(long documentId) throws IOException {
        try (Lease lease = acquire()) {
            IndexSearcher current = lease.searcher();
            TopDocs hits = current.search(
                new TermQuery(new Term(FIELD_DOC_ID, Long.toString(documentId))), 1);
            if (hits.scoreDocs.length == 0) {
                return null;
            }
            // Only the text is loaded. The id is what the caller already had, and fetching it
            // again would decompress a second stored field per lookup for nothing.
            Document stored = current.storedFields().document(hits.scoreDocs[0].doc, TEXT_ONLY);
            return stored.get(FIELD_TEXT);
        }
    }

    /**
     * Whether this index was opened with {@link #openReadOnly}.
     *
     * @return true when nothing can be written through it
     */
    public boolean isReadOnly() {
        return writer == null;
    }

    private IndexWriter writable() {
        if (writer == null) {
            throw new IllegalStateException("lexical index is open read-only: " + directory);
        }
        return writer;
    }

    /** The analyzer, so a query is analysed exactly as the documents were. */
    public Analyzer analyzer() {
        return analyzer;
    }

    /**
     * Which published state of the index a search started now would read.
     *
     * <p>The version of the reader searches go through, which changes exactly when a
     * {@link #refresh()} publishes something - an addition, a deletion or a merge - and not
     * when a write is merely buffered or committed behind the reader's back. That is the
     * boundary a search sees, so an answer computed against one version is the answer to the
     * same question for as long as the version stands.</p>
     *
     * @return The reader's version, or empty when the index has never been refreshed and so
     *         has no reader
     */
    public java.util.OptionalLong readerVersion() {
        Snapshot current = published.get();
        return current == null ? java.util.OptionalLong.empty()
            : java.util.OptionalLong.of(current.version());
    }

    /** How many documents are searchable as of the last refresh. */
    public int documentCount() {
        Snapshot current = published.get();
        return current == null ? 0 : current.reader().numDocs();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Measured from the index rather than fixed, because this is the footprint that moves. A
     * {@link MMapDirectory} maps the files it reads, so the count follows the segment count: it
     * rises as documents are added and falls when a merge replaces many segments with one. A
     * constant here would be wrong on every index except the one it was measured on, and wrong
     * downwards on a large one, which is the direction that walks the process into
     * {@code vm.max_map_count}.
     *
     * <p>Large files count more than once. {@code MMapDirectory} splits anything beyond its chunk
     * size across several mappings, so the arithmetic below asks the directory what that size is
     * rather than assuming a version's default.
     *
     * <p>Zero where the directory is not memory-mapped — a 32-bit JVM gets an
     * {@code NIOFSDirectory} from {@code FSDirectory.open} instead, which maps nothing and pays in
     * descriptors, counted by {@link #descriptors()}.
     *
     * @return The mappings the open index currently holds, or zero if the count cannot be read
     */
    @Override
    public long mappings() {
        if (!(directory instanceof MMapDirectory mmap)) {
            return 0L;
        }
        long chunk = mmap.getMaxChunkSize();
        long total = 0;
        try {
            for (String file : mmap.listAll()) {
                long length;
                try {
                    length = mmap.fileLength(file);
                } catch (IOException missing) {
                    // A merge can retire a file between listing it and measuring it. Skipping it
                    // is right: it is not mapped any more either.
                    continue;
                }
                // At least one even for an empty file, and ceiling division for the rest.
                total += Math.max(1L, (length + chunk - 1) / chunk);
            }
        } catch (IOException e) {
            LOGGER.warn("could not measure the mappings held by {}: {}",
                mmap.getDirectory(), e.toString());
            return 0L;
        }
        return total;
    }

    /**
     * {@inheritDoc}
     *
     * <p>One for the writer's lock, which is held open for as long as this index is, and none on
     * a read-only index, which takes no lock. A mapped directory holds no others: it maps each
     * file and closes the channel. A directory that is not mapped reads through open files
     * instead, so there its files are descriptors rather than mappings and are counted here.
     */
    @Override
    public long descriptors() {
        long lock = writer == null ? 0L : 1L;
        if (directory instanceof MMapDirectory) {
            return lock;
        }
        try {
            return lock + directory.listAll().length;
        } catch (IOException e) {
            LOGGER.warn("could not measure the descriptors held by {}: {}", directory, e.toString());
            return lock;
        }
    }

    @Override
    public void close() {
        Snapshot last = published.getAndSet(null);
        closeQuietly("reader", last == null ? null : last.reader());
        closeQuietly("writer", writer);
        closeQuietly("analyzer", analyzer);
        closeQuietly("directory", directory);
    }

    private void closeQuietly(String what, AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            // Every component is closed even if an earlier one failed, because leaving the writer's
            // lock file behind makes the index unopenable next time.
            LOGGER.warn("failed to close lexical index {}", what, e);
        }
    }
}
