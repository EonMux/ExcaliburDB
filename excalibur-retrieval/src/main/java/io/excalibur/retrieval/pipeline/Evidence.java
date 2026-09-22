package io.excalibur.retrieval.pipeline;

import java.util.List;

import io.excalibur.graph.subgraph.Subgraph;
import io.excalibur.planner.PlanningOutcome;
import io.excalibur.planner.QueryPlan;
import io.excalibur.retrieval.fusion.FusedDocument;

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
     * @param fused    the fused candidates, best first; may be empty
     * @param subgraph the evidence the traversal assembled
     * @param plan     the plan this was retrieved for
     */
    record Gathered(List<FusedDocument> fused, Subgraph subgraph, QueryPlan plan)
            implements Evidence {

        public Gathered {
            if (subgraph == null || plan == null) {
                throw new IllegalArgumentException("subgraph and plan are required");
            }
            fused = List.copyOf(fused);
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
