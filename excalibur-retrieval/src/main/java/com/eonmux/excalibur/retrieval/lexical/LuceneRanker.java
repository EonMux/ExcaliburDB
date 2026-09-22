package com.eonmux.excalibur.retrieval.lexical;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.Ranker;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;
import com.eonmux.excalibur.retrieval.rank.ScoredDocument;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.CollectionStatistics;
import org.apache.lucene.search.QueryVisitor;
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
 *
 * <h2>Two scales</h2>
 * Raw BM25 by default, which is what fusion and every other reader of positions wants. It has no
 * absolute meaning: a question naming a rare entity scores an order of magnitude above one naming
 * a common one, so a threshold on it is wrong for one of them whatever its value.
 *
 * <p>{@link Scale#ATTAINABLE_SHARE} divides each score by the most any document could score for
 * this query - the sum, over the query's terms the index holds, of each term's weight, which BM25
 * approaches as a term's frequency grows and never reaches. What is left is the share of the
 * matchable weight of the question the document carries, in {@code [0, 1]}, and comparable from
 * one question to the next: a document matching only the question's commonest words scores near
 * zero, one matching its rarest ones scores high, whatever the corpus. The top of the scale is a
 * limit, not a typical value: BM25 saturates with a term's frequency, so a document mentioning
 * every question word once at average length scores about {@code 1 / (1 + k1)} - under a half at
 * the default {@code k1} - and repetition, not a better match, is what carries it higher.
 *
 * <p>A term no document holds adds nothing to the bound. No document could have matched it, so
 * its absence says nothing about any of them; and in a small index most of a question's words -
 * "who", "did", the verb it is asked in - are held by nothing, so counting them at the weight of
 * the rarest possible term would push every share towards zero and a threshold on it would refuse
 * every question a small graph is asked. Dividing every score by one number leaves the order
 * exactly as BM25 had it.
 */
public final class LuceneRanker implements Ranker {

    /** What a {@link LuceneRanker}'s scores are measured in. */
    public enum Scale {
        /** Lucene's BM25 score as it stands: unbounded, comparable only within one query. */
        BM25,

        /** BM25 as a share of the most this query could score, in {@code [0, 1]}. */
        ATTAINABLE_SHARE
    }

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
    private final Scale scale;

    /** A ranker named {@code "bm25"} over {@code index}. */
    public LuceneRanker(LexicalIndex index) {
        this(index, "bm25");
    }

    /**
     * A named ranker, so several lexical signals over different indexes stay distinguishable in
     * fusion diagnostics and timings.
     */
    public LuceneRanker(LexicalIndex index, String name) {
        this(index, name, Scale.BM25);
    }

    /**
     * A named ranker reporting its scores on a chosen scale.
     *
     * @param index The index to search
     * @param name  The signal's name, for diagnostics and timings
     * @param scale What the scores are measured in
     */
    public LuceneRanker(LexicalIndex index, String name, Scale scale) {
        if (index == null) {
            throw new IllegalArgumentException("index must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (scale == null) {
            throw new IllegalArgumentException("scale must not be null");
        }
        this.index = index;
        this.name = name;
        this.scale = scale;
    }

    /** The scale this ranker's scores are reported on. */
    public Scale scale() {
        return scale;
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

        Query parsed;
        try {
            parsed = parse(text);
        } catch (IOException e) {
            throw new UncheckedIOException("lexical search failed for " + name, e);
        }
        if (parsed == null) {
            // Everything the user typed analysed away — all stop words, or only punctuation.
            // That is a query this signal cannot answer, not a failure.
            return RankedList.empty(name);
        }
        try (LexicalIndex.Lease lease = index.acquire()) {
            IndexSearcher searcher = lease.searcher();
            TopDocs top = searcher.search(parsed, limit);
            StoredFields storedFields = searcher.storedFields();

            double attainable = scale == Scale.ATTAINABLE_SHARE
                ? attainable(parsed, searcher)
                : 1.0;
            List<ScoredDocument> results = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc hit : top.scoreDocs) {
                Document document = storedFields.document(hit.doc, DOC_ID_ONLY);
                Number documentId = document.getField(LexicalIndex.FIELD_DOC_ID).numericValue();
                if (documentId == null) {
                    continue;
                }
                results.add(new ScoredDocument(documentId.longValue(),
                    scaled(hit.score, attainable)));
            }
            // Lucene already returns hits best first; RankedList.of re-sorts to apply the same
            // deterministic tie break every signal uses, so equal BM25 scores cannot order
            // differently between runs.
            return RankedList.of(name, results, limit);
        } catch (IOException e) {
            throw new UncheckedIOException("lexical search failed for " + name, e);
        }
    }

    /** A raw score on this ranker's scale. */
    private double scaled(float score, double attainable) {
        if (scale == Scale.BM25) {
            return score;
        }
        // Clamped because the bound is computed in double and Lucene scores in float, so a
        // document at the very edge of the bound can round a hair above it.
        return attainable <= 0 ? 0.0 : Math.min(1.0, score / attainable);
    }

    /**
     * The supremum of the BM25 score {@code query} can give any document.
     *
     * <p>BM25 scores a term {@code boost * idf * f / (f + k)} for a frequency {@code f} and a
     * length-dependent {@code k}, which rises towards {@code boost * idf} and never gets there; a
     * disjunction scores the sum of its matching terms. The sum of every term's
     * {@code boost * idf} is therefore the bound, and it depends only on the query and the
     * index's statistics - not on which documents happened to match, so the share it gives is
     * the same number for the same document whatever else is in the result.
     *
     * <p>idf is Lucene's own formula over the same statistics the similarity reads. A term the
     * index has never seen is left out: it is in no document, so no document's score has any of
     * its weight to lose.
     */
    private static double attainable(Query query, IndexSearcher searcher) throws IOException {
        CollectionStatistics statistics = searcher.collectionStatistics(LexicalIndex.FIELD_TEXT);
        if (statistics == null) {
            return 0.0;
        }
        long documents = statistics.docCount();
        List<Term> terms = new ArrayList<>();
        query.visit(new QueryVisitor() {
            @Override
            public void consumeTerms(Query source, Term... consumed) {
                terms.addAll(List.of(consumed));
            }

            @Override
            public QueryVisitor getSubVisitor(BooleanClause.Occur occur, Query parent) {
                // An excluded clause scores nothing, so it adds nothing to the bound.
                return occur == BooleanClause.Occur.MUST_NOT ? QueryVisitor.EMPTY_VISITOR : this;
            }
        });

        double bound = 0.0;
        for (Term term : terms) {
            long frequency = searcher.getIndexReader().docFreq(term);
            if (frequency > 0) {
                bound += Math.log(1 + (documents - frequency + 0.5D) / (frequency + 0.5D));
            }
        }
        return bound;
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
