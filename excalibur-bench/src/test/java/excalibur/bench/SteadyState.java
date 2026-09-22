package excalibur.bench;

/**
 * Measures a workload once it has stopped getting faster.
 *
 * <p>A throughput gate that times one pass over a loop does not measure the code; it measures
 * where the JIT happened to be while the clock was running. The point lookup this module gates
 * ramps from about 8.1 M ops/sec to a plateau of about 10.9 on the machine these targets were
 * set on, and it takes tens of millions of iterations to get there - far beyond any warmup
 * constant the gates carried. The symptom was a set of readings ordered by how long each gate
 * measured for rather than by how fast its path is: the fifty-million-iteration paths read
 * around 10.4, the five-million one 9.7, and the ten-million one 8.5, all of the same engine.</p>
 *
 * <p>So the loop is run several times and the best round is taken. Once warm the rounds agree to
 * a fraction of a percent, so the best round is the plateau; a round that came out slower was
 * slower because something else was happening - a compilation, a collection, another process -
 * and none of those is the property being gated. Rounds rather than a larger warmup constant,
 * because a constant tuned until it was enough here would be too small on a slower machine,
 * which is the failure this replaces.</p>
 *
 * @author ExcaliburDB
 */
public final class SteadyState {

    /**
     * How many times a workload is run before the best round is taken.
     *
     * <p>Eight, because the plateau was reached by the fifth round in both directions of the
     * measurement that established it, and a gate wants headroom over the case it was tuned on
     * rather than exactly it.</p>
     */
    public static final int ROUNDS = 8;

    private SteadyState() {
        throw new AssertionError("Utility class");
    }

    /** One pass over the workload. */
    @FunctionalInterface
    public interface Round {
        /**
         * Performs a fixed number of operations.
         *
         * @return how many operations were performed, for the caller to assert on
         */
        long run();
    }

    /**
     * Runs a workload {@link #ROUNDS} times and returns the throughput of its best round.
     *
     * @param operations How many operations one round performs
     * @param round      The workload
     * @return Operations per second at steady state
     * @throws AssertionError if a round does not perform the operations it said it would
     */
    public static double bestOpsPerSec(long operations, Round round) {
        if (operations <= 0) {
            throw new IllegalArgumentException("operations must be positive, got " + operations);
        }
        double best = 0.0;
        for (int i = 0; i < ROUNDS; i++) {
            long began = System.nanoTime();
            long performed = round.run();
            long elapsed = System.nanoTime() - began;
            if (performed != operations) {
                throw new AssertionError("round " + (i + 1) + " performed " + performed
                    + " operations, not the " + operations + " it was asked for");
            }
            if (elapsed > 0) {
                best = Math.max(best, operations * 1_000_000_000.0 / elapsed);
            }
        }
        return best;
    }
}
