package io.excalibur.retrieval.lexical;

import java.io.IOException;
import java.nio.file.Path;
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
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

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
 * different budgets and both have to be sized. {@link #ramBufferSizeMB} caps what indexing holds
 * before flushing; searching is served through the page cache like the mapped columns are. Leaving
 * the default and also sizing the heap for vectors is how a process ends up swapping.
 *
 * <h2>Visibility</h2>
 * A reader sees the index as of the last {@link #refresh()}. Documents added since are not
 * searchable, which is the same near-real-time contract the rest of the engine has, and is why
 * refresh is explicit rather than implied by every write.
 */
public final class LexicalIndex implements AutoCloseable {

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

    /** The only stored field {@link #textOf(long)} needs. */
    private static final Set<String> TEXT_ONLY = Set.of(FIELD_TEXT);

    private final Directory directory;
    private final Analyzer analyzer;
    private final IndexWriter writer;
    private final AtomicReference<DirectoryReader> reader = new AtomicReference<>();
    private final AtomicReference<IndexSearcher> searcher = new AtomicReference<>();

    private LexicalIndex(Directory directory, Analyzer analyzer, IndexWriter writer) {
        this.directory = directory;
        this.analyzer = analyzer;
        this.writer = writer;
    }

    /**
     * Opens, or creates, an index under {@code path}.
     *
     * @param ramBufferSizeMB how much heap indexing may hold before flushing to disk. Sized
     *                        deliberately, because this heap is not available to anything else in
     *                        the process.
     */
    public static LexicalIndex open(Path path, double ramBufferSizeMB) throws IOException {
        if (ramBufferSizeMB <= 0) {
            throw new IllegalArgumentException(
                "ramBufferSizeMB must be positive, got " + ramBufferSizeMB);
        }
        Directory directory = FSDirectory.open(path);
        Analyzer analyzer = new StandardAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        config.setRAMBufferSizeMB(ramBufferSizeMB);
        config.setSimilarity(new BM25Similarity());

        IndexWriter writer = new IndexWriter(directory, config);
        LexicalIndex index = new LexicalIndex(directory, analyzer, writer);
        try {
            index.refresh();
        } catch (IOException e) {
            index.close();
            throw e;
        }
        return index;
    }

    /**
     * Adds or replaces the text of one document.
     *
     * <p>Replaces by {@link #FIELD_DOC_ID} rather than appending, so re-ingesting a document does
     * not leave the previous version in the index competing with it.
     */
    public void put(long documentId, String text) throws IOException {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null for document " + documentId);
        }
        Document document = new Document();
        // Stored as well as indexed. Without the stored copy nothing downstream can read a
        // document's text back out, and rerank has nothing to send the model.
        document.add(new TextField(FIELD_TEXT, text, Field.Store.YES));
        // Indexed, un-analysed, so the Term below actually matches an existing version.
        document.add(new StringField(FIELD_DOC_ID, Long.toString(documentId), Field.Store.NO));
        document.add(new StoredField(FIELD_DOC_ID, documentId));
        document.add(new NumericDocValuesField(FIELD_DOC_ID, documentId));
        writer.updateDocument(new Term(FIELD_DOC_ID, Long.toString(documentId)), document);
    }

    /** Removes a document. */
    public void delete(long documentId) throws IOException {
        writer.deleteDocuments(new Term(FIELD_DOC_ID, Long.toString(documentId)));
    }

    /** Flushes pending writes to disk without making them searchable. */
    public void commit() throws IOException {
        writer.commit();
    }

    /**
     * Makes everything written so far searchable.
     *
     * <p>Commits first: a reader opened on uncommitted state would see documents that a crash would
     * lose, so a search could return a result that no longer exists after a restart.
     */
    public void refresh() throws IOException {
        writer.commit();
        DirectoryReader current = reader.get();
        DirectoryReader refreshed = current == null
            ? DirectoryReader.open(directory)
            : DirectoryReader.openIfChanged(current);

        if (refreshed == null) {
            return;
        }
        IndexSearcher newSearcher = new IndexSearcher(refreshed);
        newSearcher.setSimilarity(new BM25Similarity());
        reader.set(refreshed);
        searcher.set(newSearcher);
        if (current != null) {
            current.close();
        }
    }

    /**
     * A searcher over the index as of the last refresh.
     *
     * @throws IllegalStateException if the index has never been refreshed
     */
    public IndexSearcher searcher() {
        IndexSearcher current = searcher.get();
        if (current == null) {
            throw new IllegalStateException(
                "lexical index has no searcher; call refresh() before searching");
        }
        return current;
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
        IndexSearcher current = searcher();
        TopDocs hits = current.search(
            new TermQuery(new Term(FIELD_DOC_ID, Long.toString(documentId))), 1);
        if (hits.scoreDocs.length == 0) {
            return null;
        }
        // Only the text is loaded. The id is what the caller already had, and fetching it again
        // would decompress a second stored field per lookup for nothing.
        Document stored = current.storedFields().document(hits.scoreDocs[0].doc, TEXT_ONLY);
        return stored.get(FIELD_TEXT);
    }

    /** The analyzer, so a query is analysed exactly as the documents were. */
    public Analyzer analyzer() {
        return analyzer;
    }

    /** How many documents are searchable as of the last refresh. */
    public int documentCount() {
        DirectoryReader current = reader.get();
        return current == null ? 0 : current.numDocs();
    }

    @Override
    public void close() {
        closeQuietly("reader", reader.getAndSet(null));
        searcher.set(null);
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
