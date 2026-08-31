package io.excalibur.retrieval.lexical;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.excalibur.retrieval.rank.RankedList;
import io.excalibur.retrieval.rank.Ranker;
import io.excalibur.retrieval.rank.RetrievalQuery;
import io.excalibur.retrieval.rank.ScoredDocument;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;

/**
 * The lexical signal: BM25 over a {@link LexicalIndex}.
 *
 * <p>Complements dense retrieval rather than competing with it. An embedding generalises — it finds
 * documents about the same idea in different words — and that same property makes it lose exact
 * identifiers, rare proper nouns and error codes, which are precisely the terms a user types when
 * they know what they want. BM25 keeps them. Fusing the two recovers both behaviours, which is why
 * neither alone is enough.
 */
public final class LuceneRanker implements Ranker {

    /**
     * The one stored field a hit is read for.
     *
     * <p>Named explicitly because {@link LexicalIndex#FIELD_TEXT} is stored too, for rerank. An
     * unrestricted stored-field fetch would decompress every hit's whole text just to read its id,
     * so ranking would pay for the corpus on every query while using none of it.
     */
    private static final Set<String> DOC_ID_ONLY = Set.of(LexicalIndex.FIELD_DOC_ID);

    private final String name;
    private final LexicalIndex index;

    /** A ranker named {@code "bm25"} over {@code index}. */
    public LuceneRanker(LexicalIndex index) {
        this(index, "bm25");
    }

    /**
     * A named ranker, so several lexical signals over different indexes stay distinguishable in
     * fusion diagnostics and timings.
     */
    public LuceneRanker(LexicalIndex index, String name) {
        if (index == null) {
            throw new IllegalArgumentException("index must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.index = index;
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RankedList rank(RetrievalQuery query, int limit) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (limit <= 0) {
            return RankedList.empty(name);
        }
        String text = query.textIfPresent().orElseThrow(() -> new IllegalArgumentException(
            name + " needs query text, but this query carries none"));

        try {
            Query parsed = parse(text);
            if (parsed == null) {
                // Everything the user typed analysed away — all stop words, or only punctuation.
                // That is a query this signal cannot answer, not a failure.
                return RankedList.empty(name);
            }
            IndexSearcher searcher = index.searcher();
            TopDocs top = searcher.search(parsed, limit);
            StoredFields storedFields = searcher.storedFields();

            List<ScoredDocument> results = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc hit : top.scoreDocs) {
                Document document = storedFields.document(hit.doc, DOC_ID_ONLY);
                Number documentId = document.getField(LexicalIndex.FIELD_DOC_ID).numericValue();
                if (documentId == null) {
                    continue;
                }
                results.add(new ScoredDocument(documentId.longValue(), hit.score));
            }
            // Lucene already returns hits best first; RankedList.of re-sorts to apply the same
            // deterministic tie break every signal uses, so equal BM25 scores cannot order
            // differently between runs.
            return RankedList.of(name, results, limit);
        } catch (IOException e) {
            throw new UncheckedIOException("lexical search failed for " + name, e);
        }
    }

    /**
     * Parses user text as a bag of terms.
     *
     * <p>The input is escaped first. A user typing {@code doc_id:5}, {@code *:*} or an unbalanced
     * bracket would otherwise be reinterpreted as query syntax — reaching fields they were not
     * meant to search, matching everything, or failing outright. Escaping makes every character
     * a term character, which is what a search box means.
     *
     * @return the parsed query, or null if nothing survived analysis
     */
    private Query parse(String text) throws IOException {
        QueryParser parser = new QueryParser(LexicalIndex.FIELD_TEXT, index.analyzer());
        parser.setDefaultOperator(QueryParser.Operator.OR);
        try {
            String escaped = QueryParser.escape(text);
            if (escaped.isBlank()) {
                return null;
            }
            return parser.parse(escaped);
        } catch (ParseException e) {
            // Unreachable for escaped input, but a parse failure must not take down a query that
            // other signals can still answer.
            throw new IOException("could not parse escaped query text", e);
        }
    }
}
