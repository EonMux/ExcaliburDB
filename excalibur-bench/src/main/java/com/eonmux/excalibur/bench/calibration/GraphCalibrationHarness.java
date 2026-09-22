package com.eonmux.excalibur.bench.calibration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.eonmux.excalibur.core.trace.QueryTrace;
import com.eonmux.excalibur.graph.subgraph.Subgraph;
import com.eonmux.excalibur.graph.subgraph.SubgraphEdge;
import com.eonmux.excalibur.planner.ConversationContext;
import com.eonmux.excalibur.retrieval.abstain.AbstentionCalibrator;
import com.eonmux.excalibur.retrieval.abstain.ConfidenceCalibration;
import com.eonmux.excalibur.retrieval.abstain.ConfidenceOutcome;
import com.eonmux.excalibur.retrieval.abstain.ThresholdAbstentionPolicy;
import com.eonmux.excalibur.retrieval.fusion.FusedDocument;
import com.eonmux.excalibur.retrieval.pipeline.EntityLinker;
import com.eonmux.excalibur.retrieval.pipeline.Evidence;
import com.eonmux.excalibur.retrieval.pipeline.GraphRagPipeline;

/**
 * Fits the graph route's abstention against questions whose answers are known.
 *
 * <h2>Why this cannot be a server route</h2>
 * Fitting needs to know whether an answer was right, and that is exactly what a node answering a
 * live question does not have. The labels have to come from somewhere outside the node, so the
 * thing that reads them is a harness and not an endpoint.
 *
 * <h2>One retrieval, two fits</h2>
 * Each question is retrieved for exactly once, through {@link GraphRagPipeline#gather}, and the
 * evidence is kept. Both fits then run against that stored evidence rather than against fresh
 * queries:
 * <ol>
 *   <li>The three gates, from what retrieval scored and whether the answer was right.</li>
 *   <li>The confidence temperature, from what the <em>fitted</em> policy reports - not the
 *       configured one. A temperature is a correction to a particular policy's numbers, and
 *       fitting it against the confidences of a policy about to be replaced would calibrate
 *       something that is not going to run.</li>
 * </ol>
 * Retrieving twice would also make the second fit disagree with the first whenever anything in
 * the retrieval chain is not deterministic, which the model-backed stages are not.
 *
 * <h2>What the configured thresholds do to this</h2>
 * Nothing. {@link GraphRagPipeline#gather} stops before abstention, so the gates a node is
 * currently running never touch the evidence collected here and the fit is not anchored to
 * whatever the last fit produced.
 *
 * <h2>The bias this cannot remove</h2>
 * A judged set is written by someone who knew what the graph contains, so it is easier than the
 * questions a deployment receives - and precision measured on it is correspondingly optimistic.
 * Fitting at a higher target precision than actually required is the usual response. What is not
 * a response is quoting the fitted precision as the system's.
 */
public final class GraphCalibrationHarness {

    private final GraphRagPipeline pipeline;
    private final EntityLinker linker;

    /**
     * @param pipeline the pipeline to fit, opened over the graph the fit is for
     * @param linker   resolves a judged answer's name to entity ids; the pipeline's own, so the
     *                 ground truth crosses the text-to-id boundary the same way a question does
     */
    public GraphCalibrationHarness(GraphRagPipeline pipeline, EntityLinker linker) {
        if (pipeline == null || linker == null) {
            throw new IllegalArgumentException("pipeline and linker are both required");
        }
        this.pipeline = pipeline;
        this.linker = linker;
    }

    /** One question's evidence, with whether the answer it would have produced was right. */
    private record Observation(List<FusedDocument> fused, Subgraph subgraph,
                               boolean correct) {
    }

    /**
     * Retrieves for every judged question, then fits both axes against what came back.
     *
     * @param judged          the questions and their known answers
     * @param targetPrecision the precision the gates must reach, in {@code (0, 1]}
     * @return what was measured, and the configuration it implies
     */
    public CalibrationReport run(List<JudgedQuestion> judged, double targetPrecision) {
        if (judged == null || judged.isEmpty()) {
            throw new IllegalArgumentException(
                "calibration needs at least one judged question; nothing can be fitted to a set "
                    + "whose answers are unknown");
        }
        if (targetPrecision <= 0 || targetPrecision > 1 || Double.isNaN(targetPrecision)) {
            throw new IllegalArgumentException(
                "targetPrecision must be in (0, 1], got " + targetPrecision);
        }

        List<Observation> observations = new ArrayList<>(judged.size());
        int unjudgeable = 0;
        int needsUser = 0;
        int unresolved = 0;

        for (JudgedQuestion question : judged) {
            Set<Long> expected = resolve(question.expected());
            if (expected.isEmpty()) {
                // The judgement names something this graph does not hold. Scoring it as a wrong
                // answer would fit the gates against a gap in the corpus, which no threshold
                // closes; it is the judgement file or the build that needs fixing.
                unjudgeable++;
                continue;
            }

            Evidence evidence = pipeline.gather(
                question.question(), new ConversationContext(), null, new QueryTrace());
            switch (evidence) {
                case Evidence.NeedsUser ignored -> needsUser++;
                case Evidence.Unresolved ignored -> unresolved++;
                case Evidence.Gathered gathered -> observations.add(new Observation(
                    gathered.fused(), gathered.subgraph(), isCorrect(gathered.subgraph(),
                        expected)));
            }
        }

        if (observations.isEmpty()) {
            throw new IllegalStateException(
                "no judged question reached retrieval: " + unjudgeable + " named entities this "
                    + "graph does not hold, " + needsUser + " were handed back by planning, "
                    + unresolved + " resolved to nothing. There is nothing here to fit.");
        }

        List<AbstentionCalibrator.LabelledOutcome> samples = new ArrayList<>(observations.size());
        for (Observation observation : observations) {
            samples.add(AbstentionCalibrator.observe(
                observation.fused(), observation.subgraph(), observation.correct()));
        }
        AbstentionCalibrator.Calibration thresholds =
            AbstentionCalibrator.calibrate(samples, targetPrecision);

        return new CalibrationReport(thresholds,
            fitTemperature(observations, AbstentionCalibrator.toPolicy(thresholds)),
            judged.size(), unjudgeable, needsUser, unresolved, targetPrecision);
    }

    /**
     * Fits the temperature against what the fitted gates would report.
     *
     * <p>Empty when those gates answer nothing in this set. A refusal reports no confidence, so
     * there is genuinely nothing to calibrate, and returning a temperature of 1 fitted to an empty
     * sample would look like a measurement that found no correction needed.
     */
    private static Optional<ConfidenceCalibration> fitTemperature(List<Observation> observations,
                                                                  ThresholdAbstentionPolicy fitted) {
        List<ConfidenceOutcome> reported = new ArrayList<>();
        for (Observation observation : observations) {
            AbstentionCalibrator.observeConfidence(fitted, observation.fused(),
                observation.subgraph(), observation.correct()).ifPresent(reported::add);
        }
        return reported.isEmpty()
            ? Optional.empty()
            : Optional.of(AbstentionCalibrator.fitTemperature(reported));
    }

    /**
     * Whether the answer the pipeline would have given is one of the right ones.
     *
     * <h2>The answer is the subgraph</h2>
     * Not the top-ranked candidate. On this route the fused list ranks entities by how well their
     * text matches the <em>question</em>, so for "who did Ada Lovelace collaborate with" the best
     * candidate is Ada, every time and correctly - it is what the question is about. The answer is
     * what the traversal found and the serializer hands the model. Scoring the top candidate would
     * mark every well-formed question wrong.
     *
     * <h2>An endpoint of an edge, not merely present</h2>
     * The expected entity has to be joined to the rest by a fact. {@link Subgraph#entities()}
     * includes seeds that linked but led nowhere, and counting one of those would call a question
     * answered on the strength of having recognised a name in it.
     *
     * <p>What this measures is whether the evidence needed to answer survived into the context -
     * which is what abstention decides about, since it is deciding whether to hand that context
     * over at all. It does not measure whether a model then used it correctly, and a fit from here
     * should not be quoted as though it did.
     */
    private static boolean isCorrect(Subgraph subgraph, Set<Long> expected) {
        for (SubgraphEdge edge : subgraph.edges()) {
            if (expected.contains((long) edge.subject()) || expected.contains((long) edge.object())) {
                return true;
            }
        }
        return false;
    }

    /** Every entity any of the expected names could mean. */
    private Set<Long> resolve(List<String> names) {
        Set<Long> ids = new HashSet<>();
        for (String name : names) {
            for (long id : linker.link(name)) {
                ids.add(id);
            }
        }
        return ids;
    }
}
