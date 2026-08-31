package io.excalibur.retrieval.pipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import io.excalibur.retrieval.lexical.LexicalIndex;
import io.excalibur.retrieval.lexical.LuceneRanker;
import io.excalibur.retrieval.rank.RankedList;
import io.excalibur.retrieval.rank.RetrievalQuery;
import io.excalibur.retrieval.rank.ScoredDocument;

/**
 * Links a mention to entity ids by searching an index of entity labels.
 *
 * <p>Uses the same inverted index the lexical ranker does, over a different corpus: one document
 * per entity, holding its name and aliases. That reuse is the point — entity linking is a text
 * matching problem, and BM25 over labels already handles the parts that matter, including
 * misspellings' close neighbours, word order, and the fact that "Lovelace" should find "Ada
 * Lovelace" without an exact match.
 *
 * <h2>Where it stops</h2>
 * It returns candidates ranked by string similarity and nothing more. It does not use the
 * surrounding question to disambiguate, so "Cambridge" returns every Cambridge in the index rather
 * than picking the one the sentence implies. That is deliberate at this layer: the ranking and
 * abstention stages can see the whole query and the graph around each candidate, which is what
 * disambiguation actually needs, and guessing here would deny them the chance.
 *
 * <p>A score floor keeps a mention from linking to something merely because it was the least bad
 * match in the index. Below it there are no candidates, which becomes an honest "I don't know who
 * you mean" rather than an answer about the wrong entity.
 */
public final class LexicalEntityLinker implements EntityLinker {

    /**
     * How many candidates a mention may produce.
     *
     * <p>Small. Beyond a handful, the extra candidates are not plausible readings — they are the
     * long tail of anything sharing a common word — and each one costs a traversal.
     */
    public static final int DEFAULT_MAX_CANDIDATES = 5;

    /**
     * Fraction of the best match's score a candidate must reach to be considered.
     *
     * <p>Relative rather than absolute, because BM25 scores are not comparable across queries: a
     * rare name scores far higher than a common one, and any fixed cutoff would be wrong for one
     * of them. What is comparable is how a candidate stands against the best match for the same
     * mention.
     */
    public static final double DEFAULT_RELATIVE_FLOOR = 0.5;

    private final LuceneRanker ranker;
    private final int maxCandidates;
    private final double relativeFloor;

    /** A linker over an index of entity labels. */
    public LexicalEntityLinker(LexicalIndex labelIndex) {
        this(labelIndex, DEFAULT_MAX_CANDIDATES, DEFAULT_RELATIVE_FLOOR);
    }

    public LexicalEntityLinker(LexicalIndex labelIndex, int maxCandidates, double relativeFloor) {
        if (labelIndex == null) {
            throw new IllegalArgumentException("labelIndex must not be null");
        }
        if (maxCandidates < 1) {
            throw new IllegalArgumentException(
                "maxCandidates must be at least 1, got " + maxCandidates);
        }
        if (relativeFloor < 0 || relativeFloor > 1 || Double.isNaN(relativeFloor)) {
            throw new IllegalArgumentException(
                "relativeFloor must be in [0, 1], got " + relativeFloor);
        }
        this.ranker = new LuceneRanker(labelIndex, "entity-labels");
        this.maxCandidates = maxCandidates;
        this.relativeFloor = relativeFloor;
    }

    /**
     * Indexes an entity's label so it can be linked to.
     *
     * <p>Aliases go in the same document rather than one document per alias, so an entity with
     * many names does not outrank one with a single name simply by appearing more often.
     */
    public static void index(LexicalIndex labelIndex, long entityId, String label,
                             String... aliases) throws IOException {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank for entity " + entityId);
        }
        StringBuilder text = new StringBuilder(label);
        for (String alias : aliases) {
            if (alias != null && !alias.isBlank()) {
                text.append(' ').append(alias);
            }
        }
        labelIndex.put(entityId, text.toString());
    }

    @Override
    public long[] link(String mention) {
        if (mention == null || mention.isBlank()) {
            return new long[0];
        }
        try {
            RankedList matches = ranker.rank(RetrievalQuery.ofText(mention), maxCandidates);
            if (matches.isEmpty()) {
                return new long[0];
            }
            double best = matches.documents().get(0).score();
            double floor = best * relativeFloor;

            List<Long> accepted = new ArrayList<>(matches.size());
            for (ScoredDocument candidate : matches.documents()) {
                if (candidate.score() >= floor) {
                    accepted.add(candidate.documentId());
                }
            }
            long[] result = new long[accepted.size()];
            for (int i = 0; i < accepted.size(); i++) {
                result[i] = accepted.get(i);
            }
            return result;
        } catch (UncheckedIOException e) {
            throw new UncheckedIOException("entity linking failed for mention: " + mention,
                e.getCause());
        }
    }
}
