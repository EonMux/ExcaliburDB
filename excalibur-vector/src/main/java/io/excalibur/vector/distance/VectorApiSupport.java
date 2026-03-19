package io.excalibur.vector.distance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
     * Method handles for Vector API operations (null if unavailable).
     */
    private final VectorOperations operations;

    /**
     * Private constructor - initializes detection on first access.
     */
    private VectorApiSupport() {
        boolean detected = false;
        int speciesLength = 0;
        VectorOperations ops = null;

        try {
            // Attempt to load the FloatVector class - this will fail if Vector API is unavailable
            Class<?> floatVectorClass = Class.forName("jdk.incubator.vector.FloatVector");
            Class<?> vectorSpeciesClass = Class.forName("jdk.incubator.vector.VectorSpecies");
            Class<?> vectorOperatorsClass = Class.forName("jdk.incubator.vector.VectorOperators");

            // Get SPECIES_PREFERRED via reflection (it's a static field, not a method)
            Field speciesField = floatVectorClass.getField("SPECIES_PREFERRED");
            Object species = speciesField.get(null);

            // Get species length
            Method lengthMethod = vectorSpeciesClass.getMethod("length");
            speciesLength = (Integer) lengthMethod.invoke(species);

            // Create reflection-based operations wrapper
            ops = new ReflectiveVectorOperations(floatVectorClass, vectorSpeciesClass, vectorOperatorsClass, species);

            detected = true;
            LOGGER.info("Vector API detected and available. Preferred species length: {} floats", speciesLength);

        } catch (NoClassDefFoundError e) {
            LOGGER.info("Vector API not available (classes not found). Using scalar fallback. Reason: {}",
                e.getMessage());
        } catch (ClassNotFoundException e) {
            LOGGER.info("Vector API not available (module not loaded). Using scalar fallback. Reason: {}",
                e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("Vector API detection failed. Using scalar fallback. Reason: {}",
                e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        this.available = detected;
        this.preferredSpeciesLength = speciesLength;
        this.operations = ops;
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
     * Interface for vector operations, allowing reflective or direct implementations.
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

    /**
     * Reflection-based implementation of VectorOperations.
     * Works without compile-time dependency on Vector API classes.
     */
    private static final class ReflectiveVectorOperations implements VectorOperations {

        private final Object species;
        private final int speciesLength;
        private final Method fromArrayMethod;
        private final Method mulMethod;
        private final Method subMethod;
        private final Method reduceLanesAddMethod;
        private final Object addOperator;

        @SuppressWarnings("unchecked")
        ReflectiveVectorOperations(Class<?> floatVectorClass, Class<?> vectorSpeciesClass,
                                   Class<?> vectorOperatorsClass, Object species) throws Exception {
            this.species = species;
            this.speciesLength = (Integer) vectorSpeciesClass.getMethod("length").invoke(species);

            // Get static methods and fields
            this.fromArrayMethod = floatVectorClass.getMethod("fromArray", vectorSpeciesClass, float[].class, int.class);
            this.mulMethod = floatVectorClass.getMethod("mul", floatVectorClass);
            this.subMethod = floatVectorClass.getMethod("sub", floatVectorClass);
            this.reduceLanesAddMethod = floatVectorClass.getMethod("reduceLanes", vectorOperatorsClass);

            // Get ADD operator
            this.addOperator = vectorOperatorsClass.getField("ADD").get(null);
        }

        @Override
        public double dotProduct(float[] v1, float[] v2, int from, int to) {
            double sum = 0.0;
            try {
                for (int i = from; i < to; i += speciesLength) {
                    Object vec1 = fromArrayMethod.invoke(null, species, v1, i);
                    Object vec2 = fromArrayMethod.invoke(null, species, v2, i);
                    Object product = mulMethod.invoke(vec1, vec2);
                    sum += (Float) reduceLanesAddMethod.invoke(product, addOperator);
                }
            } catch (Exception e) {
                // Fallback to scalar on any error
                for (int i = from; i < to; i++) {
                    sum += (double) v1[i] * (double) v2[i];
                }
            }
            return sum;
        }

        @Override
        public double squaredL2(float[] v1, float[] v2, int from, int to) {
            double sum = 0.0;
            try {
                for (int i = from; i < to; i += speciesLength) {
                    Object vec1 = fromArrayMethod.invoke(null, species, v1, i);
                    Object vec2 = fromArrayMethod.invoke(null, species, v2, i);
                    Object diff = subMethod.invoke(vec1, vec2);
                    Object squared = mulMethod.invoke(diff, diff);
                    sum += (Float) reduceLanesAddMethod.invoke(squared, addOperator);
                }
            } catch (Exception e) {
                // Fallback to scalar on any error
                for (int i = from; i < to; i++) {
                    double d = (double) v1[i] - (double) v2[i];
                    sum += d * d;
                }
            }
            return sum;
        }

        @Override
        public double squaredNorm(float[] v, int from, int to) {
            double sum = 0.0;
            try {
                for (int i = from; i < to; i += speciesLength) {
                    Object vec = fromArrayMethod.invoke(null, species, v, i);
                    Object squared = mulMethod.invoke(vec, vec);
                    sum += (Float) reduceLanesAddMethod.invoke(squared, addOperator);
                }
            } catch (Exception e) {
                // Fallback to scalar on any error
                for (int i = from; i < to; i++) {
                    sum += (double) v[i] * (double) v[i];
                }
            }
            return sum;
        }

        @Override
        public int speciesLength() {
            return speciesLength;
        }
    }
}
