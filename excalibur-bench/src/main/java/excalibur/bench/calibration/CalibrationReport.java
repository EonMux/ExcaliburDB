package excalibur.bench.calibration;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import io.excalibur.retrieval.abstain.AbstentionCalibrator;
import io.excalibur.retrieval.abstain.ConfidenceCalibration;
import io.excalibur.retrieval.abstain.ReliabilityBin;

/**
 * What a calibration run measured, and the configuration it implies.
 *
 * <h2>Two coverages, and why both are printed</h2>
 * {@link AbstentionCalibrator.Calibration#coverage()} is answered over <em>fitted</em> questions -
 * the ones that reached retrieval and had a checkable answer. It is the right denominator for
 * fitting, because a question the planner could not read is not one a threshold could have saved.
 * It is the wrong denominator for a promise. An operator asking "how many of my questions get
 * answered" means all of them, including the ones that never reached abstention, so
 * {@link #overallCoverage()} reports that too. Quoting the first as though it were the second is
 * how a system comes to advertise coverage it does not have.
 *
 * @param thresholds     the fitted score, separation and provenance gates
 * @param confidence     the fitted temperature, absent when the thresholds answered nothing and
 *                       there was therefore no reported confidence to calibrate
 * @param judged         questions read from the judgement file
 * @param unjudgeable    questions whose expected answers resolved to no entity in this graph
 * @param needsUser      questions planning handed back rather than retrieving for
 * @param unresolved     questions no mention of which resolved to an entity
 * @param targetPrecision the precision the fit was asked for
 */
public record CalibrationReport(AbstentionCalibrator.Calibration thresholds,
                                Optional<ConfidenceCalibration> confidence,
                                int judged, int unjudgeable, int needsUser, int unresolved,
                                double targetPrecision) {

    public CalibrationReport {
        if (thresholds == null || confidence == null) {
            throw new IllegalArgumentException("thresholds and confidence are required");
        }
        if (judged < 0 || unjudgeable < 0 || needsUser < 0 || unresolved < 0) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
    }

    /** Questions that reached retrieval with a checkable answer, and so were fitted against. */
    public int fitted() {
        return thresholds.sampleSize();
    }

    /** Questions excluded from the fit, for reasons no threshold could have changed. */
    public int excluded() {
        return unjudgeable + needsUser + unresolved;
    }

    /**
     * Answered questions as a fraction of every question judged, not only the fitted ones.
     *
     * <p>This is the number a deployment will see. It is never higher than the fitted coverage and
     * is usually lower, by exactly the questions that never reached abstention.
     */
    public double overallCoverage() {
        return judged == 0 ? 0.0 : thresholds.answered() / (double) judged;
    }

    /**
     * The configuration lines this fit implies, ready to paste.
     *
     * <p>A line the fit cannot stand behind is emitted commented out, with the reason. Printing it
     * live would hand an operator a number that the same run had just described as unusable, and
     * the comment is the only thing between the two.
     */
    public String properties() {
        StringBuilder out = new StringBuilder();
        if (!thresholds.metTarget()) {
            out.append("# WARNING: no threshold combination reached the target precision of ")
                .append(format(targetPrecision))
                .append(".\n# These are the most precise gates available, at ")
                .append(format(thresholds.precision()))
                .append(" - not a fit that met what was asked for.\n");
        }
        line(out, "excalibur.graph.min-score", thresholds.minScore());
        line(out, "excalibur.graph.min-separation", thresholds.minSeparation());
        line(out, "excalibur.graph.min-mean-confidence", thresholds.minMeanConfidence());

        if (confidence.isEmpty()) {
            out.append("# excalibur.graph.confidence-temperature: not fitted. These gates answer "
                + "no question\n#   in the judged set, so there was no reported confidence to "
                + "calibrate.\n");
            return out.toString();
        }
        ConfidenceCalibration fit = confidence.get();
        if (!fit.wellPosed()) {
            out.append("# excalibur.graph.confidence-temperature=")
                .append(format(fit.temperature()))
                .append("\n#   NOT WELL POSED: the fit ran to the end of the searched range "
                    + "rather than settling\n#   inside it, which means this sample does not "
                    + "determine a temperature. Judge more\n#   questions rather than deploying "
                    + "this number.\n");
            return out.toString();
        }
        line(out, "excalibur.graph.confidence-temperature", fit.temperature());
        return out.toString();
    }

    /** The whole run, as a human reads it. */
    public String summary() {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT,
            "  judged questions:      %d%n"
            + "  fitted against:        %d%n"
            + "  excluded from the fit: %d (%d unjudgeable, %d asked back, %d unresolved)%n",
            judged, fitted(), excluded(), unjudgeable, needsUser, unresolved));

        out.append(String.format(Locale.ROOT, "%n  thresholds%n"
            + "    min-score            %s%n"
            + "    min-separation       %s%n"
            + "    min-mean-confidence  %s%n"
            + "    precision            %s (target %s%s)%n"
            + "    coverage             %s of fitted, %s of all judged%n"
            + "    answered/ambiguous/refused  %d / %d / %d%n",
            format(thresholds.minScore()), format(thresholds.minSeparation()),
            format(thresholds.minMeanConfidence()), format(thresholds.precision()),
            format(targetPrecision), thresholds.metTarget() ? "" : ", NOT MET",
            format(thresholds.coverage()), format(overallCoverage()),
            thresholds.answered(), thresholds.ambiguous(), thresholds.refused()));

        if (confidence.isEmpty()) {
            out.append(String.format(Locale.ROOT,
                "%n  confidence%n    not fitted: these gates answer nothing in this set%n"));
            return out.toString();
        }
        ConfidenceCalibration fit = confidence.get();
        out.append(String.format(Locale.ROOT, "%n  confidence%n"
            + "    temperature          %s%s%n"
            + "    calibration error    %s, from %s before scaling%n",
            format(fit.temperature()), fit.wellPosed() ? "" : "  (NOT WELL POSED)",
            format(fit.expectedCalibrationError()), format(fit.uncalibratedError())));
        appendReliability(out, fit.reliability());
        return out.toString();
    }

    /**
     * The reliability diagram, empty bands omitted.
     *
     * <p>A band nothing landed in says only that the policy never made a claim in that range, and
     * printing ten rows of zeroes to say so buries the handful that carry the finding.
     */
    private static void appendReliability(StringBuilder out, List<ReliabilityBin> diagram) {
        out.append(String.format(Locale.ROOT,
            "%n    band          n   claimed   observed   gap%n"));
        for (ReliabilityBin bin : diagram) {
            if (bin.isEmpty()) {
                continue;
            }
            out.append(String.format(Locale.ROOT, "    %.1f-%.1f  %5d   %7s   %8s   %s%s%n",
                bin.lowerBound(), bin.upperBound(), bin.count(),
                format(bin.meanConfidence()), format(bin.observedAccuracy()), format(bin.gap()),
                bin.overconfident() ? "  over" : ""));
        }
    }

    private static void line(StringBuilder out, String key, double value) {
        out.append(key).append('=').append(format(value)).append('\n');
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
