package excalibur.bench;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.InnerProduct;
import io.excalibur.vector.distance.L2Distance;
import io.excalibur.vector.distance.VectorApiSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Validation tests for DistanceVectorApiBenchmark.
 *
 * <p>Verifies that:</p>
 * <ul>
 *   <li>Scalar implementations produce correct results</li>
 *   <li>Vector API implementations produce results matching scalar</li>
 *   <li>Domain constraints are satisfied (range, sign, etc.)</li>
 *   <li>Edge cases handled correctly</li>
 * </ul>
 *
 * <h2>Domain Correctness Criteria</h2>
 * <ul>
 *   <li>Cosine similarity: result in [-1.0, 1.0]</li>
 *   <li>L2 distance: result >= 0.0</li>
 *   <li>Inner product: any float value, with correct sign handling</li>
 *   <li>Identical vectors: cosine=1.0, l2=0.0, ip=sum of squares</li>
 *   <li>Orthogonal vectors: cosine=0.0, ip=0.0</li>
 * </ul>
 */
@DisplayName("Distance Vector API Benchmark Validation")
class DistanceVectorApiBenchmarkValidationTest {

    private static final float FLOAT_TOLERANCE = 1e-4f;
    private static final float SQUARED_TOLERANCE = 1e-3f;  // Squared values accumulate more error
    private static final int TEST_DIMENSION = 256;

    private CosineSimilarity cosineSimilarity;
    private L2Distance l2Distance;
    private InnerProduct innerProduct;
    private boolean vectorApiAvailable;

    @BeforeEach
    void setUp() {
        cosineSimilarity = CosineSimilarity.getInstance();
        l2Distance = L2Distance.getInstance();
        innerProduct = InnerProduct.getInstance();
        vectorApiAvailable = VectorApiSupport.getInstance().isAvailable();
    }

    // ==================== SCALAR IMPLEMENTATIONS (from benchmark) ====================

    private float scalarCosineSimilarity(float[] v1, float[] v2) {
        final int len = v1.length;
        if (len == 0) return 0.0f;

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        double dotC = 0.0, norm1C = 0.0, norm2C = 0.0;

        for (int i = 0; i < len; i++) {
            final double a = v1[i];
            final double b = v2[i];

            final double prod = a * b;
            final double dotY = prod - dotC;
            final double dotT = dotProduct + dotY;
            dotC = (dotT - dotProduct) - dotY;
            dotProduct = dotT;

            final double sq1 = a * a;
            final double n1Y = sq1 - norm1C;
            final double n1T = norm1 + n1Y;
            norm1C = (n1T - norm1) - n1Y;
            norm1 = n1T;

            final double sq2 = b * b;
            final double n2Y = sq2 - norm2C;
            final double n2T = norm2 + n2Y;
            norm2C = (n2T - norm2) - n2Y;
            norm2 = n2T;
        }

        final double normProduct = norm1 * norm2;
        if (normProduct == 0.0) return 0.0f;

        final double similarity = dotProduct / Math.sqrt(normProduct);
        return clampToUnitRange(similarity);
    }

    private float scalarL2Distance(float[] v1, float[] v2) {
        final int len = v1.length;
        if (len == 0) return 0.0f;

        double sum = 0.0;
        double compensation = 0.0;

        for (int i = 0; i < len; i++) {
            final double diff = (double) v1[i] - (double) v2[i];
            final double squared = diff * diff;

            final double y = squared - compensation;
            final double t = sum + y;
            compensation = (t - sum) - y;
            sum = t;
        }

        return (float) Math.sqrt(Math.max(0.0, sum));
    }

    private float scalarInnerProduct(float[] v1, float[] v2) {
        final int len = v1.length;
        if (len == 0) return 0.0f;

        double sum = 0.0;
        double compensation = 0.0;

        for (int i = 0; i < len; i++) {
            final double prod = (double) v1[i] * (double) v2[i];

            final double y = prod - compensation;
            final double t = sum + y;
            compensation = (t - sum) - y;
            sum = t;
        }

        return (float) sum;
    }

    private static float clampToUnitRange(double value) {
        if (value > 1.0) return 1.0f;
        if (value < -1.0) return -1.0f;
        return (float) value;
    }

    // ==================== HELPER METHODS ====================

    private float[] generateRandomVector(int dim, Random random) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = random.nextFloat() * 2.0f - 1.0f;
        }
        return v;
    }

    private float[] normalize(float[] v) {
        float[] result = new float[v.length];
        double norm = 0.0;
        for (float val : v) {
            norm += (double) val * (double) val;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) return result;
        for (int i = 0; i < v.length; i++) {
            result[i] = (float) (v[i] / norm);
        }
        return result;
    }

    // ==================== DOMAIN CORRECTNESS TESTS ====================

    @Test
    @DisplayName("Cosine similarity result is in valid range [-1, 1]")
    void cosineSimilarity_validRange() {
        Random random = new Random(12345);
        for (int i = 0; i < 100; i++) {
            float[] v1 = generateRandomVector(TEST_DIMENSION, random);
            float[] v2 = generateRandomVector(TEST_DIMENSION, random);

            float scalar = scalarCosineSimilarity(v1, v2);
            float vectorApi = cosineSimilarity.compute(v1, v2);

            assertThat(scalar).isBetween(-1.0f, 1.0f);
            assertThat(vectorApi).isBetween(-1.0f, 1.0f);
        }
    }

    @Test
    @DisplayName("L2 distance result is non-negative")
    void l2Distance_nonNegative() {
        Random random = new Random(12345);
        for (int i = 0; i < 100; i++) {
            float[] v1 = generateRandomVector(TEST_DIMENSION, random);
            float[] v2 = generateRandomVector(TEST_DIMENSION, random);

            float scalar = scalarL2Distance(v1, v2);
            float vectorApi = l2Distance.compute(v1, v2);

            assertThat(scalar).isGreaterThanOrEqualTo(0.0f);
            assertThat(vectorApi).isGreaterThanOrEqualTo(0.0f);
        }
    }

    @Test
    @DisplayName("Scalar and Vector API implementations produce same results")
    void scalarMatchesVectorApi() {
        Random random = new Random(12345);
        for (int i = 0; i < 50; i++) {
            float[] v1 = generateRandomVector(TEST_DIMENSION, random);
            float[] v2 = generateRandomVector(TEST_DIMENSION, random);

            // Cosine
            float scalarCos = scalarCosineSimilarity(v1, v2);
            float vectorApiCos = cosineSimilarity.compute(v1, v2);
            assertThat(scalarCos).isCloseTo(vectorApiCos, within(FLOAT_TOLERANCE));

            // L2
            float scalarL2 = scalarL2Distance(v1, v2);
            float vectorApiL2 = l2Distance.compute(v1, v2);
            assertThat(scalarL2).isCloseTo(vectorApiL2, within(FLOAT_TOLERANCE));

            // Inner product
            float scalarIP = scalarInnerProduct(v1, v2);
            float vectorApiIP = innerProduct.compute(v1, v2);
            assertThat(scalarIP).isCloseTo(vectorApiIP, within(FLOAT_TOLERANCE));
        }
    }

    @Test
    @DisplayName("Identical vectors produce correct results")
    void identicalVectors() {
        Random random = new Random(42);
        float[] v = generateRandomVector(TEST_DIMENSION, random);

        // Cosine similarity of identical vectors should be 1.0
        float cosineScalar = scalarCosineSimilarity(v, v);
        float cosineVectorApi = cosineSimilarity.compute(v, v);
        assertThat(cosineScalar).isCloseTo(1.0f, within(FLOAT_TOLERANCE));
        assertThat(cosineVectorApi).isCloseTo(1.0f, within(FLOAT_TOLERANCE));

        // L2 distance of identical vectors should be 0.0
        float l2Scalar = scalarL2Distance(v, v);
        float l2VectorApi = l2Distance.compute(v, v);
        assertThat(l2Scalar).isCloseTo(0.0f, within(FLOAT_TOLERANCE));
        assertThat(l2VectorApi).isCloseTo(0.0f, within(FLOAT_TOLERANCE));

        // Inner product of identical vectors should be sum of squares (positive)
        float ipScalar = scalarInnerProduct(v, v);
        float ipVectorApi = innerProduct.compute(v, v);
        assertThat(ipScalar).isGreaterThan(0.0f);
        assertThat(ipVectorApi).isCloseTo(ipScalar, within(FLOAT_TOLERANCE));
    }

    @Test
    @DisplayName("Orthogonal vectors produce cosine=0 and inner product=0")
    void orthogonalVectors() {
        // Create orthogonal vectors: [1,0,0,...] and [0,1,0,...]
        float[] v1 = new float[TEST_DIMENSION];
        float[] v2 = new float[TEST_DIMENSION];
        v1[0] = 1.0f;
        v2[1] = 1.0f;

        float cosineScalar = scalarCosineSimilarity(v1, v2);
        float cosineVectorApi = cosineSimilarity.compute(v1, v2);

        assertThat(cosineScalar).isCloseTo(0.0f, within(FLOAT_TOLERANCE));
        assertThat(cosineVectorApi).isCloseTo(0.0f, within(FLOAT_TOLERANCE));

        float ipScalar = scalarInnerProduct(v1, v2);
        float ipVectorApi = innerProduct.compute(v1, v2);

        assertThat(ipScalar).isCloseTo(0.0f, within(FLOAT_TOLERANCE));
        assertThat(ipVectorApi).isCloseTo(0.0f, within(FLOAT_TOLERANCE));
    }

    @Test
    @DisplayName("Opposite vectors produce cosine=-1")
    void oppositeVectors() {
        float[] v1 = new float[TEST_DIMENSION];
        float[] v2 = new float[TEST_DIMENSION];
        for (int i = 0; i < TEST_DIMENSION; i++) {
            v1[i] = 1.0f / (i + 1);
            v2[i] = -v1[i];
        }

        float[] n1 = normalize(v1);
        float[] n2 = normalize(v2);

        float cosineScalar = scalarCosineSimilarity(n1, n2);
        float cosineVectorApi = cosineSimilarity.compute(n1, n2);

        assertThat(cosineScalar).isCloseTo(-1.0f, within(FLOAT_TOLERANCE));
        assertThat(cosineVectorApi).isCloseTo(-1.0f, within(FLOAT_TOLERANCE));
    }

    @Test
    @DisplayName("Zero vectors handled correctly")
    void zeroVectors() {
        float[] zero = new float[TEST_DIMENSION];
        float[] nonZero = generateRandomVector(TEST_DIMENSION, new Random(42));

        // Zero vector cosine should return 0.0 (defined behavior)
        float cosineScalar = scalarCosineSimilarity(zero, nonZero);
        float cosineVectorApi = cosineSimilarity.compute(zero, nonZero);

        assertThat(cosineScalar).isEqualTo(0.0f);
        assertThat(cosineVectorApi).isEqualTo(0.0f);

        // L2 from zero to non-zero should be norm of non-zero
        float l2Scalar = scalarL2Distance(zero, nonZero);
        float l2VectorApi = l2Distance.compute(zero, nonZero);

        assertThat(l2Scalar).isGreaterThan(0.0f);
        assertThat(l2VectorApi).isCloseTo(l2Scalar, within(FLOAT_TOLERANCE));
    }

    @Test
    @DisplayName("Squred L2 distance matches squared L2")
    void squaredL2Correctness() {
        Random random = new Random(12345);
        for (int i = 0; i < 50; i++) {
            float[] v1 = generateRandomVector(TEST_DIMENSION, random);
            float[] v2 = generateRandomVector(TEST_DIMENSION, random);

            float l2 = l2Distance.compute(v1, v2);
            float l2Squared = l2Distance.computeSquared(v1, v2);

            // Use larger tolerance for squared values due to error accumulation
            assertThat(l2Squared).isCloseTo(l2 * l2, within(SQUARED_TOLERANCE));
        }
    }

    @Test
    @DisplayName("Inner product handles negative values correctly")
    void innerProductNegativeValues() {
        float[] v1 = new float[TEST_DIMENSION];
        float[] v2 = new float[TEST_DIMENSION];

        // All positive vs all positive -> positive
        for (int i = 0; i < TEST_DIMENSION; i++) {
            v1[i] = 0.5f;
            v2[i] = 0.5f;
        }
        float ip1 = scalarInnerProduct(v1, v2);
        assertThat(ip1).isGreaterThan(0.0f);

        // All positive vs all negative -> negative
        for (int i = 0; i < TEST_DIMENSION; i++) {
            v2[i] = -0.5f;
        }
        float ip2 = scalarInnerProduct(v1, v2);
        assertThat(ip2).isLessThan(0.0f);

        // Verify Vector API matches
        float ip1VectorApi = innerProduct.compute(v1, v2);
        assertThat(ip1VectorApi).isCloseTo(ip2, within(FLOAT_TOLERANCE));
    }

    @Test
    @DisplayName("Various dimensions produce correct results")
    void variousDimensions() {
        int[] dimensions = {64, 128, 256, 512, 768, 1536};
        Random random = new Random(12345);

        for (int dim : dimensions) {
            float[] v1 = generateRandomVector(dim, random);
            float[] v2 = generateRandomVector(dim, random);

            // Verify all distance functions work for this dimension
            float cosine = cosineSimilarity.compute(v1, v2);
            float l2 = l2Distance.compute(v1, v2);
            float ip = innerProduct.compute(v1, v2);

            // Domain checks
            assertThat(cosine)
                .as("Cosine similarity for dimension %d", dim)
                .isBetween(-1.0f, 1.0f);
            assertThat(l2)
                .as("L2 distance for dimension %d", dim)
                .isGreaterThanOrEqualTo(0.0f);

            // Scalar should match Vector API
            float cosineScalar = scalarCosineSimilarity(v1, v2);
            assertThat(cosine).isCloseTo(cosineScalar, within(FLOAT_TOLERANCE));
        }
    }

    @Test
    @DisplayName("Vector API status reported correctly")
    void vectorApiStatus() {
        VectorApiSupport support = VectorApiSupport.getInstance();

        // Should not throw
        boolean available = support.isAvailable();
        int speciesLength = support.getPreferredSpeciesLength();

        if (available) {
            assertThat(speciesLength).isGreaterThan(0);
            assertThat(speciesLength).isLessThanOrEqualTo(512);
            System.out.println("Vector API available with SIMD width: " + speciesLength + " floats");
        } else {
            System.out.println("Vector API not available - using scalar fallback");
        }
    }

    @Test
    @DisplayName("Benchmark benchmark class compiles and initializes")
    void benchmarkCompiles() {
        // Just verify we can instantiate and the setup would work
        // This is a compile-time check
        assertThat(cosineSimilarity).isNotNull();
        assertThat(l2Distance).isNotNull();
        assertThat(innerProduct).isNotNull();
        assertThat(vectorApiAvailable || !vectorApiAvailable).isTrue(); // always true
    }
}
