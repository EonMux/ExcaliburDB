package io.excalibur.retrieval.expand;

import io.excalibur.retrieval.lexical.LexicalIndex;
import io.excalibur.retrieval.rank.RankedList;
import io.excalibur.retrieval.rank.Ranker;
import io.excalibur.retrieval.rank.RetrievalQuery;
import io.excalibur.retrieval.rank.ScoredDocument;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Runs a query and its expansions against Lucene as one weighted search.
 *
 * <h2>One query, not several</h2>
 * <p>The formulations become {@code SHOULD} clauses of a single boolean query, each boosted by its
 * weight. Running them separately and fusing the result lists would work, but it asks Lucene for
 * the same postings several times and throws away the term statistics that make BM25 comparable
 * across the clauses. One query lets the engine do what it is good at, and it is the difference
 * between expansion costing a fraction of a search and costing N of them.</p>
 *
 * <h2>Degradation</h2>
 * <p>A formulation that will not parse is dropped rather than failing the search: the text came
 * from a language model, so a stray bracket or colon is an ordinary event. If every clause drops,
 * the ranker returns nothing for this signal, which fusion treats as a signal with no opinion.</p>
 */
public final class ExpandedLexicalRanker implements Ranker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExpandedLexicalRanker.class);
    private static final Set<String> DOC_ID_ONLY = Set.of(LexicalIndex.FIELD_DOC_ID);

    private final LexicalIndex index;
    private final QueryExpander expander;
    private final String name;
    private final VocabularyGate vocabulary;

    public ExpandedLexicalRanker(LexicalIndex index, QueryExpander expander) {
        this(index, expander, "lexical-expanded", VocabularyGate.permissive());
    }

    public ExpandedLexicalRanker(LexicalIndex index, QueryExpander expander, String name) {
        this(index, expander, name, VocabularyGate.permissive());
    }

    /**
     * @param vocabulary Consulted before a generated formulation is searched; terms the corpus
     *                   cannot contain are dropped rather than scored
     */
    public ExpandedLexicalRanker(LexicalIndex index, QueryExpander expander, String name,
                                 VocabularyGate vocabulary) {
        this.index = Objects.requireNonNull(index, "index must not be null");
        this.expander = Objects.requireNonNull(expander, "expander must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary must not be null");
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Whether any token of a formulation occurs in the corpus.
     *
     * <p>Any rather than all: a multi-word formulation whose head term is present is still worth
     * searching, and requiring every token would discard phrases over one incidental word.</p>
     */
    private boolean inVocabulary(String text) {
        boolean sawToken = false;
        for (String token : text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.isEmpty()) {
                continue;
            }
            sawToken = true;
            if (vocabulary.mayContain(token)) {
                return true;
            }
        }
        // A formulation with no searchable token at all is not evidence of anything.
        return !sawToken;
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

        List<SearchFormulation> formulations = expander.expand(text);
        if (formulations.isEmpty()) {
            return RankedList.empty(name);
        }

        QueryParser parser = new QueryParser(LexicalIndex.FIELD_TEXT, index.analyzer());
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        int clauses = 0;
        boolean first = true;
        for (SearchFormulation formulation : formulations) {
            // The user's own query is never gated: it is what was asked, not a guess about
            // vocabulary, and a query for words the corpus lacks must still return nothing
            // rather than silently become a different query.
            if (!first && !inVocabulary(formulation.text())) {
                LOGGER.debug("Dropping '{}': no term of it occurs in the corpus",
                    formulation.text());
                continue;
            }
            first = false;
            Query parsed;
            try {
                parsed = parser.parse(QueryParser.escape(formulation.text()));
            } catch (ParseException e) {
                LOGGER.debug("Dropping unparseable formulation '{}': {}",
                    formulation.text(), e.getMessage());
                continue;
            }
            builder.add(new BoostQuery(parsed, (float) formulation.weight()),
                BooleanClause.Occur.SHOULD);
            clauses++;
        }
        if (clauses == 0) {
            return RankedList.empty(name);
        }

        try {
            IndexSearcher searcher = index.searcher();
            if (searcher == null) {
                return RankedList.empty(name);
            }
            TopDocs top = searcher.search(builder.build(), limit);
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
            return RankedList.of(name, results, limit);
        } catch (IOException e) {
            throw new UncheckedIOException("expanded lexical search failed for " + name, e);
        }
    }
}
