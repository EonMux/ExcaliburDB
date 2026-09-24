package com.eonmux.excalibur.retrieval.abstain;

import java.util.List;

import com.eonmux.excalibur.graph.subgraph.Subgraph;

/**
 * What retrieval concluded — including concluding that it should not answer.
 *
 * <p>Sealed, with three cases, because "no answer" and "several possible answers" are real outcomes
 * that a caller must handle differently, and an API that expresses them as an empty list forces
 * every caller to guess which one happened. A system that cannot say "I don't know" says something
 * else instead, and what it says is a confident wrong answer.
 *
 * <p>Java's exhaustiveness checking over a sealed hierarchy is the point: a caller that switches on
 * this and forgets a case does not compile, so adding a fourth outcome later cannot silently fall
 * through to whatever the default branch did.
 */
public sealed interface RetrievalOutcome
        permits RetrievalOutcome.Results,
                RetrievalOutcome.NoResults,
                RetrievalOutcome.Ambiguous {

    /**
     * Retrieval found an answer it is willing to stand behind.
     *
     * @param subgraph   the evidence
     * @param confidence calibrated confidence in {@code [0, 1]}; not a raw fused score, which has
     *                   no absolute meaning, but a value calibrated against outcomes that could be
     *                   checked
     */
    record Results(Subgraph subgraph, double confidence) implements RetrievalOutcome {
        public Results {
            if (subgraph == null) {
                throw new IllegalArgumentException("subgraph must not be null");
            }
            if (confidence < 0.0 || confidence > 1.0 || Double.isNaN(confidence)) {
                throw new IllegalArgumentException(
                    "confidence must be in [0, 1], got " + confidence);
            }
        }
    }

    /**
     * Retrieval declined to answer, and says why.
     *
     * <p>The reason is not decoration. "Nothing matched" and "several things matched about equally
     * well but I could not tell them apart" call for different responses from whatever is above,
     * and collapsing them into an empty result throws that away.
     */
    record NoResults(Reason reason, String detail) implements RetrievalOutcome {
        public NoResults {
            if (reason == null) {
                throw new IllegalArgumentException("reason must not be null");
            }
        }

        /** A refusal with no further detail. */
        public static NoResults of(Reason reason) {
            return new NoResults(reason, null);
        }
    }

    /**
     * Several distinct readings of the question scored about equally.
     *
     * <p>Handed back rather than resolved, because picking one is a guess, and a guess presented as
     * an answer is indistinguishable to the reader from a correct answer.
     *
     * @param candidates the competing readings, best first
     */
    record Ambiguous(List<Candidate> candidates) implements RetrievalOutcome {
        public Ambiguous {
            if (candidates == null || candidates.size() < 2) {
                throw new IllegalArgumentException(
                    "an ambiguous outcome needs at least two candidates; one candidate is not "
                        + "ambiguous and zero is NoResults");
            }
            candidates = List.copyOf(candidates);
        }
    }

    /**
     * One competing reading.
     *
     * @param entityId    the entity this reading resolves to
     * @param label       a human-readable name, so the ambiguity can be put to someone
     * @param score       its fused score
     */
    record Candidate(long entityId, String label, double score) {
    }

    /** Why retrieval declined to answer. */
    enum Reason {
        /** Nothing was retrieved at all. */
        NO_MATCH,

        /** Something was retrieved, but not well enough to stand behind. */
        BELOW_CONFIDENCE_THRESHOLD,

        /** The evidence exists but rests on extractions too unreliable to report. */
        LOW_PROVENANCE_CONFIDENCE,

        /** A mention in the question could not be resolved to any entity. */
        UNRESOLVED_REFERENCE,

        /**
         * The query's deadline passed before the graph could be walked, so there was no
         * evidence to judge. Not a statement about the graph: the same question with more time
         * may well be answered.
         */
        TIMED_OUT
    }
}
