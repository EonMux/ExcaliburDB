package com.eonmux.excalibur.vector.distance;

import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime detection and support for the JDK Vector API (jdk.incubator.vector).
 *
 * <p>This class provides graceful fallback when the Vector API is unavailable,
 * allowing the distance functions to work on any JVM while taking advantage
 * of SIMD acceleration when available.</p>
 *
 * <h2>Detection Strategy</h2>
 * <p>Vector API availability is detected at runtime by attempting to load
 * the required classes. If loading fails (NoClassDefFoundError, ClassNotFoundException),
 * the system falls back to scalar implementations.</p>
 *
 * <h2>Reasons Vector API May Be Unavailable</h2>
 * <ul>
 *   <li>JVM does not support the incubator module (pre-Java 16)</li>
 *   <li>JVM started without --add-modules=jdk.incubator.vector</li>
 *   <li>Platform architecture lacks SIMD support</li>
 *   <li>Security manager blocks access to incubator modules</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All state is immutable after initialization.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class VectorApiSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(VectorApiSupport.class);

    /**
     * Singleton instance.
     */
    private static final VectorApiSupport INSTANCE = new VectorApiSupport();

    /**
     * Whether the Vector API is available at runtime.
     */
    private final boolean available;

    /**
     * The preferred vector species length (number of float lanes).
     * 0 if Vector API is unavailable.
     */
    private final int preferredSpeciesLength;

    /**
     * The compiled SIMD kernels, or null if the Vector API is unavailable.
     */
    private final VectorOperations operations;

    /**
     * Private constructor - initializes detection on first access.
     */
    private VectorApiSupport() {
        VectorOperations ops = loadOperations(() -> {
            // Fails fast when the incubator module is not resolved, before anything links
            // SimdVectorOperations, the one class that references it.
            Class.forName("jdk.incubator.vector.FloatVector");
            return new SimdVectorOperations();
        });
        this.available = ops != null;
        this.preferredSpeciesLength = ops == null ? 0 : ops.speciesLength();
        this.operations = ops;
        if (ops != null) {
            LOGGER.info("Vector API detected and available. Preferred species length: {} floats",
                preferredSpeciesLength);
        }
    }

    /**
     * Loads the SIMD kernels, or reports that the scalar paths must serve instead.
     *
     * <p>Anything that stops the kernels loading leaves the scalar paths in charge. A missing
     * module is the expected case. Beyond it, the kernel class can fail its static
     * initialisation or link against an incubator API this JVM ships differently, and the
     * preferred species can be refused at runtime; failing this class over any of them would
     * take every distance function down with it, when the scalar paths compute the same
     * results. This runs once, from the singleton's construction, so each outcome is logged
     * once.</p>
     *
     * @param factory Creates the kernels
     * @return The kernels, or null when they cannot be loaded
     */
    static VectorOperations loadOperations(Callable<VectorOperations> factory) {
        try {
            return factory.call();
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            LOGGER.info("Vector API not available (module not resolved). Using scalar fallback. Reason: {}",
                e.getMessage());
            return null;
        } catch (LinkageError | Exception e) {
            LOGGER.warn("Vector API kernels failed to load. Using scalar fallback.", e);
            return null;
        }
    }

    /**
     * Returns the singleton instance.
     *
     * @return The VectorApiSupport instance
     */
    public static VectorApiSupport getInstance() {
        return INSTANCE;
    }

    /**
     * Checks if the Vector API is available at runtime.
     *
     * @return true if Vector API is available and usable
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Returns the preferred vector species length (number of float lanes).
     *
     * @return Species length, or 0 if Vector API is unavailable
     */
    public int getPreferredSpeciesLength() {
        return preferredSpeciesLength;
    }

    /**
     * Returns the vector operations wrapper, or null if unavailable.
     *
     * @return VectorOperations instance, or null
     */
    public VectorOperations getOperations() {
        return operations;
    }

    /**
     * Computes the loop bound for vectorized operations.
     *
     * @param length The array length
     * @return The largest index that can be processed in full vector chunks
     */
    public int loopBound(int length) {
        if (!available || preferredSpeciesLength == 0) {
            return 0;
        }
        return (length / preferredSpeciesLength) * preferredSpeciesLength;
    }

    /**
     * The SIMD kernels behind the distance functions' vectorized paths.
     *
     * <p>Every method sums over the whole half-open range {@code [from, to)}, including any
     * elements past the last whole vector, so a caller may pass any range.</p>
     */
    public interface VectorOperations {

        /**
         * Computes dot product using vectorized operations.
         *
         * @param v1 First vector
         * @param v2 Second vector
         * @param from Start index (inclusive)
         * @param to End index (exclusive)
         * @return Partial dot product
         */
        double dotProduct(float[] v1, float[] v2, int from, int to);

        /**
         * Computes squared L2 distance using vectorized operations.
         *
         * @param v1 First vector
         * @param v2 Second vector
         * @param from Start index (inclusive)
         * @param to End index (exclusive)
         * @return Partial squared L2 distance
         */
        double squaredL2(float[] v1, float[] v2, int from, int to);

        /**
         * Computes squared norm using vectorized operations.
         *
         * @param v The vector
         * @param from Start index (inclusive)
         * @param to End index (exclusive)
         * @return Partial squared norm
         */
        double squaredNorm(float[] v, int from, int to);

        /**
         * Returns the species length.
         *
         * @return Number of float lanes per vector
         */
        int speciesLength();
    }
}
