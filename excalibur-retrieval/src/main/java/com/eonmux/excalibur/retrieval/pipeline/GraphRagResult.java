package com.eonmux.excalibur.retrieval.pipeline;

import java.util.List;
import java.util.Optional;

import com.eonmux.excalibur.core.trace.QueryTrace;
import com.eonmux.excalibur.planner.PlanningOutcome;
import com.eonmux.excalibur.planner.QueryPlan;
import com.eonmux.excalibur.retrieval.abstain.RetrievalOutcome;
import com.eonmux.excalibur.retrieval.serialize.SerializedSubgraph;

/**
 * What the pipeline produced, including when it produced nothing.
 *
 * <p>Three shapes, matching the three ways a request can legitimately end: an answer, a question
 * back, or a refusal. Collapsing them into an optional answer would leave the caller unable to tell
 * "I need to know who you mean" from "I looked and found nothing", which call for opposite
 * responses.
 *
 * <p>A question back has two sources - an unbound pronoun in planning, an ambiguous entity in
 * abstention - and {@link #inputRequest} presents both in one shape, so a caller that handles
 * one handles the other. An ambiguity is deliberately not a refusal: the evidence was there, the
 * system could not tell which of several things was meant, and a caller that renders that as "not
 * found" has thrown away the one word that would have answered it.
 *
 * <p>A query whose deadline passed is still one of the three: what it had gathered by then is
 * judged like any other evidence, and {@link #timedOut} says the answer - or the refusal - may be
 * missing what more time would have found. Only a deadline that passed before the graph was walked
 * leaves nothing to judge, and that is a refusal with the reason
 * {@link RetrievalOutcome.Reason#TIMED_OUT}.
 *
 * <p>The trace is always present. A refusal that took two seconds is as much a performance question
 * as an answer that did, and a result type that only carried timings on success would hide exactly
 * the slow paths worth looking at.
 *
 * @param results     the evidence and its confidence, when answered
 * @param context     the serialised evidence, when answered
 * @param askUser     what planning asked, when a pronoun stopped it
 * @param ambiguity   the competing readings, labelled, when abstention could not choose
 * @param declined    why retrieval refused, when it did
 * @param plan        the plan the walk used, when planning produced one
 * @param aggregation the computed aggregate, when the plan asked for one and it was answered
 * @param walk        what the walk's limits left out, when the graph was walked
 * @param timedOut    whether the query's deadline cut any stage short
 * @param trace       per-stage latency
 */
public record GraphRagResult(
    Optional<RetrievalOutcome.Results> results,
    Optional<SerializedSubgraph> context,
    Optional<PlanningOutcome.AskUser> askUser,
    Optional<RetrievalOutcome.Ambiguous> ambiguity,
    Optional<RetrievalOutcome.NoResults> declined,
    Optional<QueryPlan> plan,
    Optional<Aggregation> aggregation,
    Optional<WalkTruncation> walk,
    boolean timedOut,
    QueryTrace trace
) {

    public GraphRagResult {
        if (trace == null) {
            throw new IllegalArgumentException("trace must not be null");
        }
    }

    static GraphRagResult answered(RetrievalOutcome.Results results, SerializedSubgraph context,
                                   QueryPlan plan, Aggregation aggregation, WalkTruncation walk,
                                   boolean timedOut, QueryTrace trace) {
        return new GraphRagResult(Optional.of(results), Optional.of(context), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of(plan), Optional.ofNullable(aggregation),
            Optional.of(walk), timedOut, trace);
    }

    static GraphRagResult askUser(PlanningOutcome.AskUser ask, QueryTrace trace) {
        return new GraphRagResult(Optional.empty(), Optional.empty(), Optional.of(ask),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), false, trace);
    }

    static GraphRagResult ambiguous(RetrievalOutcome.Ambiguous ambiguity, QueryPlan plan,
                                    WalkTruncation walk, boolean timedOut, QueryTrace trace) {
        return new GraphRagResult(Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(ambiguity), Optional.empty(), Optional.of(plan), Optional.empty(),
            Optional.of(walk), timedOut, trace);
    }

    static GraphRagResult declined(RetrievalOutcome.NoResults outcome, QueryPlan plan,
                                   QueryTrace trace) {
        return declined(outcome, plan, null, false, trace);
    }

    static GraphRagResult declined(RetrievalOutcome.NoResults outcome, QueryPlan plan,
                                   WalkTruncation walk, boolean timedOut, QueryTrace trace) {
        return new GraphRagResult(Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(outcome), Optional.ofNullable(plan), Optional.empty(),
            Optional.ofNullable(walk), timedOut, trace);
    }

    static GraphRagResult timedOut(QueryPlan plan, QueryTrace trace) {
        return declined(RetrievalOutcome.NoResults.of(RetrievalOutcome.Reason.TIMED_OUT), plan,
            null, true, trace);
    }

    /** Whether there is context to give a model. */
    public boolean hasAnswer() {
        return results.isPresent();
    }

    /** Whether the pipeline needs something from the user before it can proceed. */
    public boolean needsUserInput() {
        return askUser.isPresent() || ambiguity.isPresent();
    }

    /**
     * What the user is being asked, from whichever stage asked it.
     *
     * @return the request, empty when the pipeline needs nothing from the user
     */
    public Optional<InputRequest> inputRequest() {
        if (askUser.isPresent()) {
            return Optional.of(InputRequest.of(askUser.get()));
        }
        return ambiguity.map(outcome -> InputRequest.of(outcome,
            plan.map(QueryPlan::mentions).orElse(List.of())));
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
