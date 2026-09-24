package com.eonmux.excalibur.retrieval.abstain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.subgraph.Subgraph;
import com.eonmux.excalibur.retrieval.fusion.FusedDocument;

/**
 * Fits an abstention threshold against queries whose answers could be checked.
 *
 * <p>A threshold picked by hand is a number that felt right on the examples someone happened to
 * try. This one is measured: run a set of queries whose correct answer is known, record what
 * retrieval scored and whether it was right, then choose the lowest threshold that still meets a
 * required precision. Lowest, because every point of threshold above what precision demands is
 * coverage given away for nothing — questions the system could have answered and refused to.
 *
 * <h2>Where the labels come from</h2>
 * From the provenance columns. An edge whose {@link SourceType} is {@link
 * SourceType#USER_ASSERTED} was stated through {@code POST /v1/graph/facts} by someone who knew
 * it, not guessed at by an extractor, so for the slice of queries answered entirely from such
 * edges the correct answer is already known and no human labelling is needed. That slice is what
 * {@link #isVerifiable} identifies. It is a biased sample — asserted facts are cleaner than
 * extracted ones — so a threshold fitted here is a floor, and the section below says what to do
 * about that.
 *
 * <h2>The bias, and what it means</h2>
 * Precision measured on asserted edges overstates precision on extracted ones. A threshold fitted
 * here will therefore be slightly too permissive in production. Two honest responses: fit at a
 * higher target precision than actually required, or fit separately per {@link SourceType} once
 * enough labelled extracted queries exist. What is not honest is quoting the fitted precision as
 * the system's precision.
 *
 * <h2>The second thing worth fitting</h2>
 * A threshold decides <em>which</em> queries get answered. It says nothing about whether the
 * confidence attached to an answer means anything, and the two fail independently - see
 * {@link ConfidenceOutcome}. {@link #fitTemperature} fits that second axis from the same kind of
 * checkable outcome, and {@link #expectedCalibrationError} and {@link #reliability} measure it,
 * before and after.
 *
 * <p>Worth fitting because the alternative is arithmetic. {@link ThresholdAbstentionPolicy} builds
 * its reported confidence out of a fused score and four chosen constants; the result is a number
 * in {@code [0, 1]} that looks like a probability and has never been checked against one. Nothing
 * in the running system can notice it is wrong, because noticing requires knowing whether the
 * answers were right, which is exactly what serving does not have.
 */
public final class AbstentionCalibrator {

    /**
     * Bands in a reliability diagram when a caller does not choose.
     *
     * <p>Ten is the convention, and it is a real trade-off rather than a round number: too few
     * bands hide a miscalibration that reverses direction inside one of them, too many leave each
     * band holding a handful of samples whose accuracy is mostly noise.
     */
    public static final int DEFAULT_BINS = 10;

    /** Competing readings an ambiguous outcome reports when a caller does not choose. */
    public static final int DEFAULT_AMBIGUOUS_CANDIDATES = 5;

    /**
     * Distinct values tried for the separation and provenance gates.
     *
     * <p>The score gate is swept exactly, because the walk that evaluates it is shared across every
     * combination and costs nothing extra. The other two multiply the search, so a sample offering
     * thousands of distinct values is thinned to this many evenly spaced through them. Sixty-four
     * puts the fitted gate within one sixty-fourth of the observed spread of the best available,
     * which is far finer than the sampling error on a query set small enough to have been labelled
     * by hand.
     */
    private static final int MAX_GATE_CANDIDATES = 64;

    /**
     * Lower end of the range of temperatures the fit will search.
     *
     * <p>Bounded because the likelihood has no finite optimum on some samples, and an unbounded
     * search would answer those with a number that means "this sample was perfectly separable"
     * while looking exactly like a number that means "this is the right temperature". Landing on
     * a bound is reported instead, through {@link ConfidenceCalibration#wellPosed()}.
     *
     * <p>Wide enough not to bind on a sample that does have an optimum: at 0.05 a confidence of
     * 0.6 is sharpened past 0.99, and at 20 even 0.99 is softened below 0.56.
     */
    private static final double MIN_TEMPERATURE = 0.05;

    /** Upper end of the searched range; see {@link #MIN_TEMPERATURE}. */
    private static final double MAX_TEMPERATURE = 20.0;

    /**
     * Newton steps before the fit gives up.
     *
     * <p>The objective is convex in the reciprocal of the temperature - it is a logistic
     * regression on one feature with no intercept - so Newton's method converges quadratically
     * and settles in under ten steps in practice. The cap exists so that a sample which somehow
     * does not converge is reported as not well posed rather than spinning.
     */
    private static final int MAX_NEWTON_STEPS = 100;

    /** Step size below which the fit is settled, and curvature below which it is flat. */
    private static final double NEWTON_TOLERANCE = 1e-10;

    private AbstentionCalibrator() {
    }

    /**
     * One query's outcome, with the ground truth.
     *
     * @param topScore       the best score retrieval produced, on the retriever's scale; zero when
     *                       nothing scored above it
     * @param separation     relative gap between the best and second candidate
     * @param meanConfidence mean extractor confidence of the evidence
     * @param correct        whether the answer retrieval would have given was right
     */
    public record LabelledOutcome(double topScore, double separation, double meanConfidence,
                                  boolean hasEvidence, boolean correct) {
        public LabelledOutcome {
            if (Double.isNaN(topScore) || topScore < 0) {
                throw new IllegalArgumentException("topScore must be non-negative, got " + topScore);
            }
            requireUnitRange("separation", separation);
            requireUnitRange("meanConfidence", meanConfidence);
        }

        private static void requireUnitRange(String name, double value) {
            if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException(name + " must be in [0, 1], got " + value);
            }
        }

        /**
         * Whether this outcome clears a given set of thresholds, as the policy would judge it.
         *
         * <p>The order matters and mirrors {@link ThresholdAbstentionPolicy#decide} exactly,
         * because a fit that simulates the policy differently from how the policy runs is fitting
         * something else. In particular the provenance gate is skipped when there was no evidence
         * to judge: the policy only applies it to a non-empty subgraph, and a calibrator that
         * failed such a query against it would fit a threshold to a check that never happens.
         */
        Verdict verdictAt(double minScore, double minSeparation, double minMeanConfidence) {
            if (topScore <= 0 || topScore < minScore) {
                return Verdict.REFUSED;
            }
            if (separation < minSeparation) {
                return Verdict.AMBIGUOUS;
            }
            if (hasEvidence && meanConfidence < minMeanConfidence) {
                return Verdict.REFUSED;
            }
            return Verdict.ANSWERED;
        }
    }

    /** What a set of thresholds would have done with one labelled query. */
    private enum Verdict {
        ANSWERED, AMBIGUOUS, REFUSED
    }

    /**
     * A fitted set of thresholds and what they achieve on the sample they were fitted to.
     *
     * <h2>Why the three are fitted together</h2>
     * They are not independent. Raising the provenance gate removes queries that the score gate
     * would also have removed, so fitting each alone and combining the results gives a policy
     * stricter than either fit measured - the precision is higher than the target asked for and
     * the coverage paid for it was never authorised by anything. Fitted jointly, the three are
     * chosen as the combination that reaches the target precision while answering the most.
     *
     * <h2>Where the coverage went</h2>
     * {@code ambiguous} and {@code refused} split the queries that were not answered. The split
     * is worth having because the two are not equally bad: an ambiguity is handed back with its
     * competing readings and a caller can often resolve it by asking, while a refusal ends the
     * exchange. A fit that buys its precision by turning answers into ambiguities has cost less
     * than one that buys the same precision with refusals, and only this split shows which
     * happened.
     *
     * @param minScore          the fitted score threshold
     * @param minSeparation     the fitted ambiguity threshold
     * @param minMeanConfidence the fitted provenance threshold
     * @param precision         fraction of answered queries that were correct, at these thresholds
     * @param coverage          fraction of queries answered at all, at these thresholds
     * @param ambiguous         how many queries these thresholds would call ambiguous
     * @param refused           how many queries these thresholds would decline outright
     * @param sampleSize        how many labelled queries this was fitted to
     * @param metTarget         whether the requested precision was reachable at all
     */
    public record Calibration(double minScore, double minSeparation, double minMeanConfidence,
                              double precision, double coverage, int ambiguous, int refused,
                              int sampleSize, boolean metTarget) {

        /** How many of the labelled queries these thresholds would answer. */
        public int answered() {
            return sampleSize - ambiguous - refused;
        }
    }

    /**
     * Whether a query's answer can be checked without a human.
     *
     * <p>True when the evidence is non-empty and every edge was asserted through the API.
     * "Every", not "mostly": one extracted edge in an otherwise asserted subgraph is enough to make
     * the answer unverifiable, and counting it as verified would quietly put unlabelled data into
     * the ground truth.
     */
    public static boolean isVerifiable(Subgraph subgraph) {
        if (subgraph == null || subgraph.edges().isEmpty()) {
            return false;
        }
        return subgraph.edges().stream()
            .allMatch(edge -> edge.provenance().sourceType() == SourceType.USER_ASSERTED);
    }

    /**
     * Builds a labelled outcome from what retrieval produced.
     *
     * @param fused    the fused candidates, best first
     * @param subgraph the evidence
     * @param correct  whether the answer was right
     */
    public static LabelledOutcome observe(List<FusedDocument> fused, Subgraph subgraph,
                                          boolean correct) {
        boolean hasEvidence = subgraph != null && !subgraph.isEmpty();
        if (fused == null || fused.isEmpty()) {
            return new LabelledOutcome(0.0, 0.0,
                hasEvidence ? subgraph.meanConfidence() : 0.0, hasEvidence, correct);
        }
        double top = fused.get(0).score();
        double separation = fused.size() > 1
            ? ThresholdAbstentionPolicy.separation(top, fused.get(1).score())
            : 1.0;
        // A negative best - a similarity below zero - is refused by the policy exactly as a zero
        // is, so recording it as zero loses nothing the verdict reads.
        return new LabelledOutcome(Math.max(0.0, top), separation,
            hasEvidence ? subgraph.meanConfidence() : 0.0, hasEvidence, correct);
    }

    /**
     * Finds the thresholds that reach {@code targetPrecision} while answering the most queries.
     *
     * <h2>What is searched</h2>
     * All three gates {@link ThresholdAbstentionPolicy} applies, together. Every observed score is
     * tried as a score threshold - the precision/coverage curve only changes where a sample
     * crosses, so there is nothing between two observed values worth testing - against a grid of
     * observed separation and provenance values. The separation and provenance grids are thinned
     * to at most {@value #MAX_GATE_CANDIDATES} values each when a sample offers more, which bounds
     * the search at a cost worth naming: the fitted gate is then the best of the values tried
     * rather than the best that exists, and the two differ by less than the spacing between
     * neighbouring observed values.
     *
     * <h2>What is chosen</h2>
     * Among the combinations reaching the target, the one answering the most queries. Ties are
     * broken towards the lowest thresholds, in score, separation, provenance order: a gate set
     * higher than precision demanded costs nothing on this sample but will cost coverage on the
     * next one, and there is no evidence here for raising it.
     *
     * <p>If nothing reaches the target, returns the combination with the highest precision and
     * says so in {@link Calibration#metTarget}. Silently returning the best available as though it
     * met the target is how a system ends up believing it is more precise than it is.
     *
     * @param samples         labelled outcomes; at least one required
     * @param targetPrecision required precision, in {@code (0, 1]}
     * @return the fitted thresholds and what they achieve
     */
    public static Calibration calibrate(List<LabelledOutcome> samples, double targetPrecision) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException(
                "calibration needs at least one labelled outcome; a threshold cannot be fitted to "
                    + "nothing");
        }
        if (targetPrecision <= 0 || targetPrecision > 1 || Double.isNaN(targetPrecision)) {
            throw new IllegalArgumentException(
                "targetPrecision must be in (0, 1], got " + targetPrecision);
        }

        int size = samples.size();
        LabelledOutcome[] byScore = samples.toArray(new LabelledOutcome[0]);
        // Descending, so that "answered at this threshold" is always a prefix and one walk can
        // evaluate every score threshold instead of rescanning the sample for each.
        Arrays.sort(byScore, Comparator.comparingDouble(LabelledOutcome::topScore).reversed());

        double[] separations = gateCandidates(samples, LabelledOutcome::separation);
        double[] confidences = gateCandidates(samples, LabelledOutcome::meanConfidence);

        Best meetingTarget = new Best(false);
        Best mostPrecise = new Best(true);

        for (double minSeparation : separations) {
            for (double minMeanConfidence : confidences) {
                int answered = 0;
                int correct = 0;
                int ambiguous = 0;
                for (int i = 0; i < size; i++) {
                    LabelledOutcome sample = byScore[i];
                    switch (sample.verdictAt(0.0, minSeparation, minMeanConfidence)) {
                        case ANSWERED -> {
                            answered++;
                            if (sample.correct()) {
                                correct++;
                            }
                        }
                        case AMBIGUOUS -> ambiguous++;
                        default -> { }
                    }
                    // Only at the end of a run of equal scores: a threshold between two tied
                    // samples is not a threshold, it answers both or neither.
                    if (i + 1 < size && byScore[i + 1].topScore() == sample.topScore()) {
                        continue;
                    }
                    offer(meetingTarget, mostPrecise, sample.topScore(), minSeparation,
                        minMeanConfidence, answered, correct, ambiguous, size, targetPrecision);
                }
                // A zero threshold answers the same set as the lowest observed score but reports
                // itself honestly as not gating on score at all.
                offer(meetingTarget, mostPrecise, 0.0, minSeparation, minMeanConfidence,
                    answered, correct, ambiguous, size, targetPrecision);
            }
        }

        return meetingTarget.found()
            ? meetingTarget.toCalibration(size, true)
            : mostPrecise.toCalibration(size, false);
    }

    /** Scores one threshold combination against both the target and the fallback. */
    private static void offer(Best meetingTarget, Best mostPrecise, double minScore,
                              double minSeparation, double minMeanConfidence, int answered,
                              int correct, int ambiguous, int size, double targetPrecision) {
        if (answered == 0) {
            // A combination that answers nothing has undefined precision, not perfect precision.
            return;
        }
        double precision = correct / (double) answered;
        double coverage = answered / (double) size;
        int refused = size - answered - ambiguous;
        mostPrecise.offer(minScore, minSeparation, minMeanConfidence, precision, coverage,
            ambiguous, refused);
        if (precision >= targetPrecision) {
            meetingTarget.offer(minScore, minSeparation, minMeanConfidence, precision, coverage,
                ambiguous, refused);
        }
    }

    /**
     * The distinct values of one field, plus zero, thinned to a bounded grid.
     *
     * <p>Zero is always first and always kept: it is the option of not gating on this dimension at
     * all, which is the right answer whenever the dimension carries no signal, and a grid that
     * could not express it would force every fit to gate on all three.
     */
    private static double[] gateCandidates(List<LabelledOutcome> samples,
                                           ToDoubleFunction<LabelledOutcome> field) {
        double[] observed = new double[samples.size() + 1];
        observed[0] = 0.0;
        for (int i = 0; i < samples.size(); i++) {
            observed[i + 1] = field.applyAsDouble(samples.get(i));
        }
        Arrays.sort(observed);

        double[] distinct = new double[observed.length];
        int count = 0;
        for (double value : observed) {
            if (count == 0 || distinct[count - 1] != value) {
                distinct[count++] = value;
            }
        }
        if (count <= MAX_GATE_CANDIDATES) {
            return Arrays.copyOf(distinct, count);
        }

        double[] thinned = new double[MAX_GATE_CANDIDATES];
        for (int i = 0; i < MAX_GATE_CANDIDATES; i++) {
            thinned[i] = distinct[(int) ((long) i * (count - 1) / (MAX_GATE_CANDIDATES - 1))];
        }
        return thinned;
    }

    /**
     * The best combination seen so far, held as primitives.
     *
     * <p>A local accumulator that never escapes {@link #calibrate}. It exists rather than a stream
     * of {@link Calibration} values because the search evaluates on the order of a thousand
     * combinations per sample, and allocating a record for each one to discard it immediately is
     * most of the work the fit would do.
     */
    private static final class Best {

        private final boolean precisionFirst;
        private boolean found;
        private double minScore;
        private double minSeparation;
        private double minMeanConfidence;
        private double precision;
        private double coverage;
        private int ambiguous;
        private int refused;

        Best(boolean precisionFirst) {
            this.precisionFirst = precisionFirst;
        }

        boolean found() {
            return found;
        }

        void offer(double candidateScore, double candidateSeparation, double candidateConfidence,
                   double candidatePrecision, double candidateCoverage, int candidateAmbiguous,
                   int candidateRefused) {
            if (found && !improves(candidateScore, candidateSeparation, candidateConfidence,
                    candidatePrecision, candidateCoverage, candidateAmbiguous)) {
                return;
            }
            found = true;
            minScore = candidateScore;
            minSeparation = candidateSeparation;
            minMeanConfidence = candidateConfidence;
            precision = candidatePrecision;
            coverage = candidateCoverage;
            ambiguous = candidateAmbiguous;
            refused = candidateRefused;
        }

        private boolean improves(double candidateScore, double candidateSeparation,
                                 double candidateConfidence, double candidatePrecision,
                                 double candidateCoverage, int candidateAmbiguous) {
            if (precisionFirst) {
                int byPrecision = Double.compare(candidatePrecision, precision);
                if (byPrecision != 0) {
                    return byPrecision > 0;
                }
            }
            int byCoverage = Double.compare(candidateCoverage, coverage);
            if (byCoverage != 0) {
                return byCoverage > 0;
            }
            // Equal coverage means the same number of queries went unanswered, but not that they
            // went unanswered equally well. An ambiguity hands back the competing readings and a
            // caller can often resolve it; a refusal ends the exchange. So of two fits that answer
            // the same queries, the one that turns the rest into ambiguities has cost less.
            int byAmbiguity = Integer.compare(candidateAmbiguous, ambiguous);
            if (byAmbiguity != 0) {
                return byAmbiguity > 0;
            }
            // Indistinguishable in what they do, so prefer the gates that are lowest - in score,
            // then separation, then provenance. A gate raised higher than this sample gave a
            // reason to raise is coverage given away on the next corpus for nothing, and fewer
            // fitted constraints is the less overfitted of two fits that measured the same.
            int byScore = Double.compare(candidateScore, minScore);
            if (byScore != 0) {
                return byScore < 0;
            }
            int bySeparation = Double.compare(candidateSeparation, minSeparation);
            if (bySeparation != 0) {
                return bySeparation < 0;
            }
            return Double.compare(candidateConfidence, minMeanConfidence) < 0;
        }

        Calibration toCalibration(int sampleSize, boolean metTarget) {
            return new Calibration(minScore, minSeparation, minMeanConfidence, precision,
                coverage, ambiguous, refused, sampleSize, metTarget);
        }
    }

    /**
     * A policy built from a fitted set of thresholds.
     *
     * <p>Takes all three from the calibration. An earlier signature took the separation and
     * provenance gates as arguments because the fit did not produce them, which meant a policy
     * assembled here was two-thirds measured and one-third whatever the caller had to hand.
     *
     * @param calibration            the fitted thresholds
     * @param maxAmbiguousCandidates how many competing readings to report; not a fitted value,
     *                               because it changes what an ambiguous outcome says and not
     *                               which queries become one
     * @return the policy those thresholds describe
     */
    public static ThresholdAbstentionPolicy toPolicy(Calibration calibration,
                                                     int maxAmbiguousCandidates) {
        if (calibration == null) {
            throw new IllegalArgumentException("calibration must not be null");
        }
        return new ThresholdAbstentionPolicy(calibration.minScore(), calibration.minSeparation(),
            calibration.minMeanConfidence(), maxAmbiguousCandidates);
    }

    /** As {@link #toPolicy(Calibration, int)}, reporting {@value #DEFAULT_AMBIGUOUS_CANDIDATES}
     * competing readings. */
    public static ThresholdAbstentionPolicy toPolicy(Calibration calibration) {
        return toPolicy(calibration, DEFAULT_AMBIGUOUS_CANDIDATES);
    }

    /**
     * Records what a policy actually reported for one query whose answer could be checked.
     *
     * <p>Returns nothing when the policy declined or called the query ambiguous. Neither reports a
     * confidence, so neither has one to calibrate, and inventing a value for them - zero for a
     * refusal, say - would put outcomes into the fit that the policy never claimed anything about
     * and drag the temperature towards correcting a claim nobody made.
     *
     * <p>Consequence worth stating plainly: the confidence sample is the answered subset, so the
     * error measured over it is the error of the answers given. How many questions went unanswered
     * to achieve it is {@link #calibrate}'s coverage, and the two numbers have to be read together.
     *
     * @param policy   the policy whose reported confidence is being checked
     * @param fused    the fused candidates, best first; may be empty
     * @param subgraph the evidence
     * @param correct  whether the answer the policy would have given was right
     * @return the reported confidence paired with the outcome, or empty if it did not answer
     */
    public static Optional<ConfidenceOutcome> observeConfidence(AbstentionPolicy policy,
                                                                List<FusedDocument> fused,
                                                                Subgraph subgraph,
                                                                boolean correct) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        RetrievalOutcome outcome = policy.decide(fused == null ? List.of() : fused, subgraph);
        return outcome instanceof RetrievalOutcome.Results results
            ? Optional.of(new ConfidenceOutcome(results.confidence(), correct))
            : Optional.empty();
    }

    /**
     * Groups answered queries into equal-width confidence bands and reports what each achieved.
     *
     * <p>Equal-width rather than equal-count, because the question a reliability diagram answers
     * is "when this system says 0.9, how often is it right" - which is about the claim, so the
     * bands have to be fixed ranges of the claim. Equal-count bands move with the distribution and
     * cannot be compared between two runs.
     *
     * <p>Every band is returned, including empty ones, so the diagram shows which parts of the
     * scale the policy never used. An empty band reports {@link ReliabilityBin#isEmpty()}.
     *
     * @param samples answered queries with their outcomes; at least one required
     * @param bins    how many bands to divide {@code [0, 1]} into; at least one
     * @return one band per division, lowest first
     */
    public static List<ReliabilityBin> reliability(List<ConfidenceOutcome> samples, int bins) {
        requireConfidenceSamples(samples);
        requireBins(bins);

        int[] count = new int[bins];
        double[] confidenceSum = new double[bins];
        int[] correct = new int[bins];
        for (ConfidenceOutcome sample : samples) {
            int index = Math.min(bins - 1, (int) (sample.confidence() * bins));
            count[index]++;
            confidenceSum[index] += sample.confidence();
            if (sample.correct()) {
                correct[index]++;
            }
        }

        List<ReliabilityBin> diagram = new ArrayList<>(bins);
        for (int i = 0; i < bins; i++) {
            double lower = i / (double) bins;
            double upper = (i + 1) / (double) bins;
            diagram.add(count[i] == 0
                ? new ReliabilityBin(lower, upper, 0, 0.0, 0.0)
                : new ReliabilityBin(lower, upper, count[i],
                    confidenceSum[i] / count[i], correct[i] / (double) count[i]));
        }
        return List.copyOf(diagram);
    }

    /**
     * Expected calibration error: how far the stated confidences were from the observed rate.
     *
     * <p>The weighted mean over {@link #reliability} bands of the gap between what a band claimed
     * and what it achieved, each band weighted by how many answers fell in it. Zero means a
     * policy's numbers can be read as probabilities. A tenth means that, on average, an answer
     * stated at 0.9 is right about 0.8 of the time - or 1.0 of the time, since the measure is
     * absolute and does not say which direction. {@link ReliabilityBin#overconfident()} does.
     *
     * <p>It is a summary and it hides structure, which is why {@link #reliability} is public
     * beside it. Two policies with the same error can be wrong in ways that call for opposite
     * responses.
     *
     * @param samples answered queries with their outcomes; at least one required
     * @param bins    how many bands to divide {@code [0, 1]} into; at least one
     * @return the error, in {@code [0, 1]}, lower being better
     */
    public static double expectedCalibrationError(List<ConfidenceOutcome> samples, int bins) {
        requireConfidenceSamples(samples);
        return errorOf(reliability(samples, bins), samples.size());
    }

    /** As {@link #expectedCalibrationError(List, int)}, over {@link #DEFAULT_BINS} bands. */
    public static double expectedCalibrationError(List<ConfidenceOutcome> samples) {
        return expectedCalibrationError(samples, DEFAULT_BINS);
    }

    /**
     * Fits the one temperature that makes a policy's confidences most honest about these outcomes.
     *
     * <h2>What is being minimised, and why that one</h2>
     * Negative log likelihood of the observed outcomes under the scaled confidences, not the
     * calibration error itself. Log likelihood is a strictly proper scoring rule: it is minimised
     * uniquely by reporting the true probability, so optimising it cannot be gamed by a
     * degenerate answer. Expected calibration error is not - a policy that reports the base rate
     * for every query has an error near zero and has said nothing. Fitting against the improper
     * measure and reporting the proper one would be backwards, so this does it the other way
     * round: it fits the likelihood and reports the error.
     *
     * <h2>How</h2>
     * Newton's method on the reciprocal of the temperature, where the objective is a one-feature
     * logistic regression and therefore convex, with an analytic gradient and curvature. Convexity
     * is what makes this safe without a line search: there is one optimum and no local minimum to
     * settle into. The search is bounded, and a fit that reaches a bound is reported through
     * {@link ConfidenceCalibration#wellPosed()} rather than returned as though it had converged.
     *
     * @param samples answered queries with their outcomes; at least one required
     * @param bins    how many bands to measure the calibration error over; at least one
     * @return the fitted temperature and the error it achieves, against the error it started from
     */
    public static ConfidenceCalibration fitTemperature(List<ConfidenceOutcome> samples, int bins) {
        requireConfidenceSamples(samples);
        requireBins(bins);

        int size = samples.size();
        double[] logits = new double[size];
        boolean[] correct = new boolean[size];
        for (int i = 0; i < size; i++) {
            ConfidenceOutcome sample = samples.get(i);
            logits[i] = ConfidenceCalibration.logit(sample.confidence());
            correct[i] = sample.correct();
        }

        // Fitted on the reciprocal: the objective is convex in it and merely unimodal in the
        // temperature, and Newton's method is only safe on the former.
        double minWeight = 1.0 / MAX_TEMPERATURE;
        double maxWeight = 1.0 / MIN_TEMPERATURE;
        double weight = 1.0;
        boolean wellPosed = false;

        for (int step = 0; step < MAX_NEWTON_STEPS; step++) {
            double gradient = 0.0;
            double curvature = 0.0;
            for (int i = 0; i < size; i++) {
                double predicted = ConfidenceCalibration.sigmoid(weight * logits[i]);
                gradient += (predicted - (correct[i] ? 1.0 : 0.0)) * logits[i];
                curvature += predicted * (1.0 - predicted) * logits[i] * logits[i];
            }
            if (curvature <= NEWTON_TOLERANCE) {
                // A flat likelihood. Either every confidence sat at the midpoint, so there is no
                // claim to correct, or the weight has been driven far enough that every prediction
                // has saturated. Neither pins down a temperature.
                break;
            }
            // Projected back into the range rather than stopped at it. A Newton step on a convex
            // objective can overshoot a long way - the first one here routinely lands outside,
            // and on the wrong side of zero - without the optimum being outside. Reading an
            // overshoot as a boundary optimum would report almost every honest sample as badly
            // posed and hand back a temperature 27 times the right one.
            double next = Math.max(minWeight,
                Math.min(maxWeight, weight - gradient / curvature));
            boolean settled = Math.abs(next - weight) < NEWTON_TOLERANCE;
            weight = next;
            if (settled) {
                // Settling inside the range is convergence. Settling on a bound means the
                // objective was still improving in the direction the bound blocks, which is the
                // sample having no optimum rather than the fit having found one.
                wellPosed = weight > minWeight && weight < maxWeight;
                break;
            }
        }

        double temperature = 1.0 / weight;
        List<ConfidenceOutcome> scaled = new ArrayList<>(size);
        for (ConfidenceOutcome sample : samples) {
            scaled.add(new ConfidenceOutcome(
                ConfidenceCalibration.scale(sample.confidence(), temperature), sample.correct()));
        }
        List<ReliabilityBin> diagram = reliability(scaled, bins);
        return new ConfidenceCalibration(temperature, errorOf(diagram, size),
            expectedCalibrationError(samples, bins), size, wellPosed, diagram);
    }

    /** As {@link #fitTemperature(List, int)}, measured over {@link #DEFAULT_BINS} bands. */
    public static ConfidenceCalibration fitTemperature(List<ConfidenceOutcome> samples) {
        return fitTemperature(samples, DEFAULT_BINS);
    }

    /**
     * Wraps a policy so that it reports calibrated confidences.
     *
     * @param policy      the policy the calibration was fitted against
     * @param calibration the fitted temperature
     * @return the policy, reporting scaled confidences and deciding exactly as before
     */
    public static CalibratedAbstentionPolicy toPolicy(AbstentionPolicy policy,
                                                      ConfidenceCalibration calibration) {
        return new CalibratedAbstentionPolicy(policy, calibration);
    }

    /** The weighted mean gap over non-empty bands. */
    private static double errorOf(List<ReliabilityBin> diagram, int total) {
        double error = 0.0;
        for (ReliabilityBin bin : diagram) {
            if (!bin.isEmpty()) {
                error += (bin.count() / (double) total) * bin.gap();
            }
        }
        // The weights sum to at most one and each gap is at most one, so the result is in [0, 1]
        // mathematically; clamping guards the last bit of floating-point accumulation, because
        // ConfidenceCalibration rejects an error outside the range.
        return Math.max(0.0, Math.min(1.0, error));
    }

    private static void requireConfidenceSamples(List<ConfidenceOutcome> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException(
                "calibration needs at least one answered outcome; a confidence cannot be checked "
                    + "against nothing");
        }
    }

    private static void requireBins(int bins) {
        if (bins < 1) {
            throw new IllegalArgumentException("bins must be at least one, got " + bins);
        }
    }
}
