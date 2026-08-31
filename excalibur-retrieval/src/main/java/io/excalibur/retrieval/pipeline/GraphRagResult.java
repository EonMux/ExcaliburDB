package io.excalibur.retrieval.pipeline;

import java.util.Optional;

import io.excalibur.core.trace.QueryTrace;
import io.excalibur.planner.PlanningOutcome;
import io.excalibur.planner.QueryPlan;
import io.excalibur.retrieval.abstain.RetrievalOutcome;
import io.excalibur.retrieval.serialize.SerializedSubgraph;

/**
 * What the pipeline produced, including when it produced nothing.
 *
 * <p>Three shapes, matching the three ways a request can legitimately end: an answer, a question
 * back, or a refusal. Collapsing them into an optional answer would leave the caller unable to tell
 * "I need to know who you mean" from "I looked and found nothing", which call for opposite
 * responses.
 *
 * <p>The trace is always present. A refusal that took two seconds is as much a performance question
 * as an answer that did, and a result type that only carried timings on success would hide exactly
 * the slow paths worth looking at.
 */
public record GraphRagResult(
    Optional<RetrievalOutcome.Results> results,
    Optional<SerializedSubgraph> context,
    Optional<PlanningOutcome.AskUser> askUser,
    Optional<RetrievalOutcome> declined,
    Optional<QueryPlan> plan,
    QueryTrace trace
) {

    public GraphRagResult {
        if (trace == null) {
            throw new IllegalArgumentException("trace must not be null");
        }
    }

    static GraphRagResult answered(RetrievalOutcome.Results results, SerializedSubgraph context,
                                   QueryPlan plan, QueryTrace trace) {
        return new GraphRagResult(Optional.of(results), Optional.of(context),
            Optional.empty(), Optional.empty(), Optional.of(plan), trace);
    }

    static GraphRagResult askUser(PlanningOutcome.AskUser ask, QueryTrace trace) {
        return new GraphRagResult(Optional.empty(), Optional.empty(),
            Optional.of(ask), Optional.empty(), Optional.empty(), trace);
    }

    static GraphRagResult declined(RetrievalOutcome outcome, QueryPlan plan, QueryTrace trace) {
        return new GraphRagResult(Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(outcome), Optional.ofNullable(plan), trace);
    }

    /** Whether there is context to give a model. */
    public boolean hasAnswer() {
        return results.isPresent();
    }

    /** Whether the pipeline needs something from the user before it can proceed. */
    public boolean needsUserInput() {
        return askUser.isPresent();
    }

    /** The text to put in the prompt, empty if there is none. */
    public String contextText() {
        return context.map(SerializedSubgraph::text).orElse("");
    }

    /** Calibrated confidence in the answer, or zero if there is none. */
    public double confidence() {
        return results.map(RetrievalOutcome.Results::confidence).orElse(0.0);
    }
}
