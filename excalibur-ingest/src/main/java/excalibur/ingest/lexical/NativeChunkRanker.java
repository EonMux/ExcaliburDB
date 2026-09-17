package excalibur.ingest.lexical;

import io.excalibur.retrieval.expand.ExpansionWeights;
import io.excalibur.retrieval.expand.QueryExpander;
import io.excalibur.retrieval.expand.SearchFormulation;
import io.excalibur.retrieval.lexical.native_.LexicalStore;
import io.excalibur.retrieval.lexical.native_.Tokens;
import io.excalibur.retrieval.rank.RankedList;
import io.excalibur.retrieval.rank.Ranker;
import io.excalibur.retrieval.rank.RetrievalQuery;
import io.excalibur.retrieval.rank.ScoredDocument;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * BM25 over the engine's own index, with the semantic work done on the query.
 *
 * <h2>Weights</h2>
 * <p>An expander returns formulations with weights; this flattens them into terms with weights and
 * hands the store one weighted query. A term appearing in two formulations has its weights summed,
 * which is what a boolean query of boosted clauses does with a repeated term - the alternative,
 * taking the larger, would make a term that several independent formulations agreed on count no
 * more than one that only the strongest suggested.</p>
 *
 * <p>Summing is bounded by {@link ExpansionWeights}, because a sum has no ceiling of its own: two
 * formulations at 0.9 sharing a term arrive at the store carrying 1.8 against the 1.0 of a word
 * the user typed, and the guess then wins the ranking. The bound is applied to the formulations
 * that survive gating rather than to every one the model produced, so a dropped formulation does
 * not quietly scale down the ones that were searched.</p>
 *
 * <h2>Gating</h2>
 * <p>A generated formulation whose terms the corpus does not contain is dropped, checked against
 * the same dictionaries the search reads rather than against an approximate filter. The user's own
 * query is never gated: it is what was asked, and a query for words the corpus lacks must return
 * nothing rather than quietly become a different query.</p>
 */
public final class NativeChunkRanker implements Ranker {

    private static final Logger LOGGER = LoggerFactory.getLogger(NativeChunkRanker.class);

    private static final String NAME = "native-bm25";

    private final NativeChunkIndex index;
    private final QueryExpander expander;

    NativeChunkRanker(NativeChunkIndex index, QueryExpander expander) {
        this.index = Objects.requireNonNull(index, "index must not be null");
        this.expander = expander == null ? QueryExpander.identity() : expander;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RankedList rank(RetrievalQuery query, int limit) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (limit <= 0) {
            return RankedList.empty(NAME);
        }
        String text = query.textIfPresent().orElseThrow(() -> new IllegalArgumentException(
            NAME + " needs query text, but this query carries none"));

        List<SearchFormulation> formulations = expander.expand(text);
        if (formulations.isEmpty()) {
            return RankedList.empty(NAME);
        }

        try {
            Map<String, Double> weighted = flatten(formulations);
            if (weighted.isEmpty()) {
                return RankedList.empty(NAME);
            }
            String[] terms = new String[weighted.size()];
            double[] weights = new double[weighted.size()];
            int at = 0;
            for (Map.Entry<String, Double> term : weighted.entrySet()) {
                terms[at] = term.getKey();
                weights[at] = term.getValue();
                at++;
            }

            List<LexicalStore.Hit> hits = index.search(terms, weights, limit);
            List<ScoredDocument> results = new ArrayList<>(hits.size());
            for (LexicalStore.Hit hit : hits) {
                results.add(new ScoredDocument(index.remember(hit.key()), hit.score()));
            }
            return RankedList.of(NAME, results, limit);
        } catch (IOException e) {
            throw new UncheckedIOException("native lexical search failed for " + NAME, e);
        }
    }

    /** Turns formulations into one weighted term set, dropping generated ones the corpus lacks. */
    private Map<String, Double> flatten(List<SearchFormulation> formulations) throws IOException {
        List<SearchFormulation> searchable =
            ExpansionWeights.bounded(corpusKnows(formulations), Tokens::of);
        Map<String, Double> weighted = new LinkedHashMap<>();
        for (SearchFormulation formulation : searchable) {
            for (String token : Tokens.of(formulation.text())) {
                weighted.merge(token, formulation.weight(), Double::sum);
            }
        }
        return weighted;
    }

    /**
     * The formulations worth searching: the user's own, and the guesses the corpus can match.
     *
     * <p>The user's query is never gated and stays first, both because it is what was asked -
     * a query for words the corpus lacks must return nothing rather than quietly become a
     * different query - and because the weight it carries is what every expansion is measured
     * against.</p>
     *
     * @param formulations What the expander produced, the original first
     * @return The survivors, the original still first
     * @throws IOException if the term dictionary cannot be read
     */
    private List<SearchFormulation> corpusKnows(List<SearchFormulation> formulations)
            throws IOException {
        List<SearchFormulation> kept = new ArrayList<>(formulations.size());
        kept.add(formulations.get(0));
        for (int i = 1; i < formulations.size(); i++) {
            SearchFormulation formulation = formulations.get(i);
            List<String> tokens = Tokens.of(formulation.text());
            if (tokens.isEmpty() || !anyKnown(tokens)) {
                LOGGER.debug("Dropping '{}': no term of it occurs in the corpus",
                    formulation.text());
                continue;
            }
            kept.add(formulation);
        }
        return kept;
    }

    private boolean anyKnown(List<String> tokens) throws IOException {
        for (String token : tokens) {
            if (index.knowsTerm(token)) {
                return true;
            }
        }
        return false;
    }
}
