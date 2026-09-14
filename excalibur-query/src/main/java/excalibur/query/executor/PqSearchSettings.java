package excalibur.query.executor;

/**
 * Whether a search may use a segment's product quantizer, and how widely.
 *
 * <p>Quantised search trades a little recall for a much cheaper scan of the graph: the beam
 * ranks by table lookups over compressed codes, and the candidates it proposes are then
 * measured exactly. The oversample is how much slack that beam is given - {@code oversample *
 * k} candidates are measured for {@code k} results - so it is the dial between the cost saved
 * and the recall kept.</p>
 *
 * <p>Disabled by default, because a quantiser is trained on every segment that passes through
 * warming whether or not a deployment wants its queries answered that way.</p>
 *
 * @param enabled Whether a segment holding a trained quantizer may be searched through it
 * @param oversample How many candidates the quantised beam collects per result, at least 1
 */
public record PqSearchSettings(boolean enabled, int oversample) {

    /** Full-precision search only: what every caller gets unless it asks otherwise. */
    public static final PqSearchSettings DISABLED = new PqSearchSettings(false, 1);

    public PqSearchSettings {
        if (oversample < 1) {
            throw new IllegalArgumentException("oversample must be at least 1: " + oversample);
        }
    }
}
