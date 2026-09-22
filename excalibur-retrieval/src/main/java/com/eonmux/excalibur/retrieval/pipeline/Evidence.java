package com.eonmux.excalibur.retrieval.pipeline;

import java.util.List;

import com.eonmux.excalibur.graph.subgraph.Subgraph;
import com.eonmux.excalibur.planner.PlanningOutcome;
import com.eonmux.excalibur.planner.QueryPlan;
import com.eonmux.excalibur.retrieval.fusion.FusedDocument;

/**
 * What a question produced, up to but not including the decision about whether to answer it.
 *
 * <p>The seam exists so that abstention can be <em>measured</em>. Fitting a threshold means asking
 * what a different threshold would have done with a query, and that question can only be answered
 * from the evidence as it stood before any threshold was applied. A harness that could only call
 * {@link GraphRagPipeline#answer} would see a refusal with no scores attached and would have to
 * reconstruct the retrieval it could not observe - which is how a calibrator comes to fit a
 * pipeline nobody runs.
 *
 * <p>Sealed with three cases because a question can fail to reach abstention in two ways that are
 * not abstention's to fix, and a fit must not treat either as a refusal it could have prevented:
 * an unbound pronoun is a question the planner could not read, and an unresolvable mention is a
 * name the graph does not hold. No threshold changes either outcome.
 */
public sealed interface Evidence {

    /**
     * Retrieval ran, and this is what it found.
     *
     * <p>Two lists, because they answer two questions. {@code fused} is what abstention judges:
     * the readings of what the question named, ranked - see
     * {@link GraphRagPipeline#gather(String, com.eonmux.excalibur.planner.ConversationContext,
     * com.eonmux.excalibur.graph.temporal.TemporalFilter, TraversalDirection,
     * com.eonmux.excalibur.core.trace.QueryTrace)}. {@code ranked} is every entity retrieval
     * ranked, which serialisation reads to order the facts by how well each end matched.
     *
     * @param fused    the readings abstention judges, best first; may be empty
     * @param ranked   every entity retrieval ranked, best first; may be empty
     * @param subgraph the evidence the traversal assembled
     * @param plan     the plan this was retrieved for
     */
    record Gathered(List<FusedDocument> fused, List<FusedDocument> ranked, Subgraph subgraph,
                    QueryPlan plan) implements Evidence {

        public Gathered {
            if (subgraph == null || plan == null) {
                throw new IllegalArgumentException("subgraph and plan are required");
            }
            fused = List.copyOf(fused);
            ranked = List.copyOf(ranked);
        }
    }

    /** Planning needs something from the user before the question can be retrieved for. */
    record NeedsUser(PlanningOutcome.AskUser ask) implements Evidence {

        public NeedsUser {
            if (ask == null) {
                throw new IllegalArgumentException("ask is required");
            }
        }
    }

    /** No mention in the question resolved to an entity this graph holds. */
    record Unresolved(QueryPlan plan) implements Evidence {

        public Unresolved {
            if (plan == null) {
                throw new IllegalArgumentException("plan is required");
            }
        }
    }
}
