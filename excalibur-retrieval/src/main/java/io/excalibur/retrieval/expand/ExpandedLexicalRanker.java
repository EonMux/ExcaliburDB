package io.excalibur.retrieval.expand;

import io.excalibur.retrieval.lexical.LexicalIndex;
import io.excalibur.retrieval.rank.RankedList;
import io.excalibur.retrieval.rank.Ranker;
import io.excalibur.retrieval.rank.RetrievalQuery;
import io.excalibur.retrieval.rank.ScoredDocument;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
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
 * <h2>Weights</h2>
 * <p>Boosted clauses of one boolean query sum where they overlap, so a term two formulations
 * share is scored twice - which is right, agreement is stronger evidence than a single
 * suggestion, and wrong the moment the sum passes the weight of a word the user actually typed.
 * {@link ExpansionWeights} is what bounds it, over the analyzer's own tokens because those are
 * the terms Lucene will score, and over the formulations that survive the vocabulary gate so a
 * dropped clause does not scale down the ones that were searched.</p>
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
     * The terms this index would look a formulation's text up by.
     *
     * <p>The index's own analyzer, because that is what decides which terms the dictionary
     * holds and what a clause will score against. Splitting the text some other way asks about
     * strings no index has - a run of CJK the analyzer indexed as bigrams, a contraction it
     * kept whole - and every answer comes back "absent".</p>
     *
     * @param text One formulation's text
     * @return Its terms, in order and including repeats, empty if the text cannot be analysed
     */
    private List<String> tokensOf(String text) {
        List<String> analysed = new ArrayList<>();
        try (TokenStream tokens = index.analyzer().tokenStream(LexicalIndex.FIELD_TEXT, text)) {
            CharTermAttribute token = tokens.addAttribute(CharTermAttribute.class);
            tokens.reset();
            while (tokens.incrementToken()) {
                analysed.add(token.toString());
            }
            tokens.end();
        } catch (IOException e) {
            // Discarded rather than returned half-read: a partial term list is a gate answer
            // about a formulation nobody analysed, and an analyzer that cannot be read must
            // not be the reason a query returns nothing. An empty list passes the gate.
            LOGGER.debug("Could not analyse '{}': {}", text, e.getMessage());
            analysed.clear();
        }
        return analysed;
    }

    /**
     * Whether any token of a formulation occurs in the corpus.
     *
     * <p>Any rather than all: a multi-word formulation whose head term is present is still worth
     * searching, and requiring every token would discard phrases over one incidental word.</p>
     *
     * <p>A formulation that produced no tokens - because it has none, or because the analyzer
     * could not be read - passes: a gate that cannot read must not be the reason a query
     * returns nothing.</p>
     */
    private boolean inVocabulary(String text) {
        List<String> tokens = tokensOf(text);
        for (String token : tokens) {
            if (vocabulary.mayContain(token)) {
                return true;
            }
        }
        return tokens.isEmpty();
    }

    /**
     * The formulations worth searching: the user's own, and the guesses the corpus can match.
     *
     * <p>The user's own query is never gated and stays first: it is what was asked, not a guess
     * about vocabulary, so a query for words the corpus lacks must still return nothing rather
     * than silently become a different query - and the weight it carries is what every
     * expansion is measured against.</p>
     *
     * @param formulations What the expander produced, the original first
     * @return The survivors, the original still first
     */
    private List<SearchFormulation> inCorpus(List<SearchFormulation> formulations) {
        List<SearchFormulation> kept = new ArrayList<>(formulations.size());
        kept.add(formulations.get(0));
        for (int i = 1; i < formulations.size(); i++) {
            SearchFormulation formulation = formulations.get(i);
            if (!inVocabulary(formulation.text())) {
                LOGGER.debug("Dropping '{}': no term of it occurs in the corpus",
                    formulation.text());
                continue;
            }
            kept.add(formulation);
        }
        return kept;
    }

    /**
     * Adds one boosted {@code SHOULD} clause per formulation.
     *
     * @param formulations The formulations to search, weights already bounded
     * @param builder      The query being assembled
     * @return How many clauses were added
     */
    private int boost(List<SearchFormulation> formulations, BooleanQuery.Builder builder) {
        QueryParser parser = new QueryParser(LexicalIndex.FIELD_TEXT, index.analyzer());
        int clauses = 0;
        for (SearchFormulation formulation : formulations) {
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
        return clauses;
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

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        int clauses = boost(ExpansionWeights.bounded(inCorpus(formulations), this::tokensOf),
            builder);
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
