package com.eonmux.excalibur.retrieval.lexical;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Matches the documents whose stored caller key a predicate admits.
 *
 * <p>Meant as a {@code FILTER} clause beside a scored query, so that a filtered search ranks the
 * admitted documents exactly as an unfiltered one would and fills its page from them, rather
 * than cutting a page from every document and discarding the refused ones afterwards - which
 * comes back short whenever the best matches are the refused ones.</p>
 *
 * <h2>Cost</h2>
 * <p>The key is a stored field, not an indexed one, so there is no postings list to intersect
 * with: each candidate is judged by reading its key. It is written as a two-phase iterator whose
 * approximation is every document, which lets a conjunction lead with the scored clause and ask
 * this one only about documents that already match a query term. The match cost it declares is
 * high, so that is the order a conjunction chooses.</p>
 *
 * <p>Never cached. The predicate is the caller's, compared by identity, and what it admits can
 * change between two searches that pass the same instance; a cached doc set would answer the
 * second from the first.</p>
 */
public final class StoredKeyFilterQuery extends Query {

    /** Reading a stored field decompresses a block of them, far dearer than a postings step. */
    private static final float MATCH_COST = 1_000f;

    private static final Set<String> KEY_ONLY = Set.of(LexicalIndex.FIELD_EXTERNAL_KEY);

    private final Predicate<String> admits;

    /**
     * Creates the filter.
     *
     * @param admits Whether a document, by its stored key, matches; a document stored without a
     *               key never does
     * @throws NullPointerException if admits is null
     */
    public StoredKeyFilterQuery(Predicate<String> admits) {
        this.admits = Objects.requireNonNull(admits, "admits must not be null");
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        return new ConstantScoreWeight(this, boost) {
            @Override
            public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
                LeafReader reader = context.reader();
                StoredFields stored = reader.storedFields();
                DocIdSetIterator every = DocIdSetIterator.all(reader.maxDoc());
                TwoPhaseIterator admitted = new TwoPhaseIterator(every) {
                    @Override
                    public boolean matches() throws IOException {
                        Document document = stored.document(approximation.docID(), KEY_ONLY);
                        IndexableField key = document.getField(LexicalIndex.FIELD_EXTERNAL_KEY);
                        return key != null && admits.test(key.stringValue());
                    }

                    @Override
                    public float matchCost() {
                        return MATCH_COST;
                    }
                };
                return new DefaultScorerSupplier(
                    new ConstantScoreScorer(score(), scoreMode, admitted));
            }

            @Override
            public boolean isCacheable(LeafReaderContext context) {
                return false;
            }
        };
    }

    @Override
    public void visit(QueryVisitor visitor) {
        visitor.visitLeaf(this);
    }

    @Override
    public String toString(String field) {
        return "StoredKeyFilter(" + LexicalIndex.FIELD_EXTERNAL_KEY + ")";
    }

    @Override
    public boolean equals(Object other) {
        return sameClassAs(other) && admits == ((StoredKeyFilterQuery) other).admits;
    }

    @Override
    public int hashCode() {
        return 31 * classHash() + System.identityHashCode(admits);
    }
}
