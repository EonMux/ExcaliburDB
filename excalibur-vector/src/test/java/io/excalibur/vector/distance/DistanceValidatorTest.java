package io.excalibur.vector.distance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link DistanceValidator}.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Cosine similarity validation: range [-1, 1]</li>
 *   <li>L2 distance validation: range [0, +infinity)</li>
 *   <li>Inner product validation: unbounded range</li>
 *   <li>Clamping functions produce correct values</li>
 *   <li>Edge cases: NaN, Infinity, boundary values</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
@DisplayName("DistanceValidator Tests")
class DistanceValidatorTest {

    // ==================== Cosine Similarity Validation ====================

    @Nested
    @DisplayName("Cosine Similarity Validation")
    class CosineSimilarityValidation {

        @Test
        @DisplayName("Valid cosine similarity in range [-1, 1]")
        void validCosineSimilarity() {
            assertThat(DistanceValidator.isValidCosineSimilarity(0.0f)).isTrue();
            assertThat(DistanceValidator.isValidCosineSimilarity(1.0f)).isTrue();
            assertThat(DistanceValidator.isValidCosineSimilarity(-1.0f)).isTrue();
            assertThat(DistanceValidator.isValidCosineSimilarity(0.5f)).isTrue();
            assertThat(DistanceValidator.isValidCosineSimilarity(-0.5f)).isTrue();
            assertThat(DistanceValidator.isValidCosineSimilarity(0.999999f)).isTrue();
            assertThat(DistanceValidator.isValidCosineSimilarity(-0.999999f)).isTrue();
        }

        @Test
        @DisplayName("Invalid cosine similarity outside range [-1, 1]")
        void invalidCosineSimilarity() {
            assertThat(DistanceValidator.isValidCosineSimilarity(1.0001f)).isFalse();
            assertThat(DistanceValidator.isValidCosineSimilarity(-1.0001f)).isFalse();
            assertThat(DistanceValidator.isValidCosineSimilarity(2.0f)).isFalse();
            assertThat(DistanceValidator.isValidCosineSimilarity(-2.0f)).isFalse();
            assertThat(DistanceValidator.isValidCosineSimilarity(100.0f)).isFalse();
        }

        @Test
        @DisplayName("NaN is considered valid (indicates invalid input)")
        void nanIsValid() {
            assertThat(DistanceValidator.isValidCosineSimilarity(Float.NaN)).isTrue();
        }

        @Test
        @DisplayName("Infinity values outside valid range")
        void infinityInvalid() {
            assertThat(DistanceValidator.isValidCosineSimilarity(Float.POSITIVE_INFINITY)).isFalse();
            assertThat(DistanceValidator.isValidCosineSimilarity(Float.NEGATIVE_INFINITY)).isFalse();
        }

        @Test
        @DisplayName("Values within tolerance of boundaries are valid")
        void toleranceAtBoundaries() {
            float tolerance = DistanceValidator.RANGE_TOLERANCE;
            assertThat(DistanceValidator.isValidCosineSimilarity(1.0f + tolerance / 2)).isTrue();
            assertThat(DistanceValidator.isValidCosineSimilarity(-1.0f - tolerance / 2)).isTrue();
        }
    }

    // ==================== L2 Distance Validation ====================

    @Nested
    @DisplayName("L2 Distance Validation")
    class L2DistanceValidation {

        @Test
        @DisplayName("Valid L2 distance is non-negative")
        void validL2Distance() {
            assertThat(DistanceValidator.isValidL2Distance(0.0f)).isTrue();
            assertThat(DistanceValidator.isValidL2Distance(1.0f)).isTrue();
            assertThat(DistanceValidator.isValidL2Distance(100.0f)).isTrue();
            assertThat(DistanceValidator.isValidL2Distance(0.0001f)).isTrue();
            assertThat(DistanceValidator.isValidL2Distance(Float.MAX_VALUE)).isTrue();
        }

        @Test
        @DisplayName("Negative L2 distance is invalid")
        void invalidL2Distance() {
            assertThat(DistanceValidator.isValidL2Distance(-0.0001f)).isFalse();
            assertThat(DistanceValidator.isValidL2Distance(-1.0f)).isFalse();
            assertThat(DistanceValidator.isValidL2Distance(-100.0f)).isFalse();
        }

        @Test
        @DisplayName("NaN is considered valid (indicates invalid input)")
        void nanIsValid() {
            assertThat(DistanceValidator.isValidL2Distance(Float.NaN)).isTrue();
        }

        @Test
        @DisplayName("Positive infinity is valid for L2")
        void positiveInfinityValid() {
            assertThat(DistanceValidator.isValidL2Distance(Float.POSITIVE_INFINITY)).isTrue();
        }

        @Test
        @DisplayName("Negative infinity is invalid for L2")
        void negativeInfinityInvalid() {
            assertThat(DistanceValidator.isValidL2Distance(Float.NEGATIVE_INFINITY)).isFalse();
        }
    }

    // ==================== Inner Product Validation ====================

    @Nested
    @DisplayName("Inner Product Validation")
    class InnerProductValidation {

        @Test
        @DisplayName("All finite values are valid for inner product")
        void allValuesValid() {
            assertThat(DistanceValidator.isValidInnerProduct(0.0f)).isTrue();
            assertThat(DistanceValidator.isValidInnerProduct(1.0f)).isTrue();
            assertThat(DistanceValidator.isValidInnerProduct(-1.0f)).isTrue();
            assertThat(DistanceValidator.isValidInnerProduct(1000000.0f)).isTrue();
            assertThat(DistanceValidator.isValidInnerProduct(-1000000.0f)).isTrue();
        }

        @Test
        @DisplayName("NaN is valid for inner product")
        void nanIsValid() {
            assertThat(DistanceValidator.isValidInnerProduct(Float.NaN)).isTrue();
        }

        @Test
        @DisplayName("Infinity is valid for inner product")
        void infinityValid() {
            assertThat(DistanceValidator.isValidInnerProduct(Float.POSITIVE_INFINITY)).isTrue();
            assertThat(DistanceValidator.isValidInnerProduct(Float.NEGATIVE_INFINITY)).isTrue();
        }
    }

    // ==================== Clamping Functions ====================

    @Nested
    @DisplayName("Cosine Similarity Clamping")
    class CosineClamping {

        @Test
        @DisplayName("Values within range are unchanged")
        void withinRangeUnchanged() {
            assertThat(DistanceValidator.clampCosineSimilarity(0.0f)).isEqualTo(0.0f);
            assertThat(DistanceValidator.clampCosineSimilarity(0.5f)).isEqualTo(0.5f);
            assertThat(DistanceValidator.clampCosineSimilarity(-0.5f)).isEqualTo(-0.5f);
            assertThat(DistanceValidator.clampCosineSimilarity(1.0f)).isEqualTo(1.0f);
            assertThat(DistanceValidator.clampCosineSimilarity(-1.0f)).isEqualTo(-1.0f);
        }

        @Test
        @DisplayName("Values above 1.0 are clamped to 1.0")
        void aboveMaxClamped() {
            assertThat(DistanceValidator.clampCosineSimilarity(1.0001f)).isEqualTo(1.0f);
            assertThat(DistanceValidator.clampCosineSimilarity(1.5f)).isEqualTo(1.0f);
            assertThat(DistanceValidator.clampCosineSimilarity(100.0f)).isEqualTo(1.0f);
            assertThat(DistanceValidator.clampCosineSimilarity(Float.POSITIVE_INFINITY)).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("Values below -1.0 are clamped to -1.0")
        void belowMinClamped() {
            assertThat(DistanceValidator.clampCosineSimilarity(-1.0001f)).isEqualTo(-1.0f);
            assertThat(DistanceValidator.clampCosineSimilarity(-1.5f)).isEqualTo(-1.0f);
            assertThat(DistanceValidator.clampCosineSimilarity(-100.0f)).isEqualTo(-1.0f);
            assertThat(DistanceValidator.clampCosineSimilarity(Float.NEGATIVE_INFINITY)).isEqualTo(-1.0f);
        }

        @Test
        @DisplayName("NaN is preserved")
        void nanPreserved() {
            assertThat(DistanceValidator.clampCosineSimilarity(Float.NaN)).isNaN();
        }
    }

    @Nested
    @DisplayName("L2 Distance Clamping")
    class L2Clamping {

        @Test
        @DisplayName("Non-negative values are unchanged")
        void nonNegativeUnchanged() {
            assertThat(DistanceValidator.clampL2Distance(0.0f)).isEqualTo(0.0f);
            assertThat(DistanceValidator.clampL2Distance(1.0f)).isEqualTo(1.0f);
            assertThat(DistanceValidator.clampL2Distance(100.0f)).isEqualTo(100.0f);
        }

        @Test
        @DisplayName("Negative values are clamped to 0.0")
        void negativeClampedToZero() {
            assertThat(DistanceValidator.clampL2Distance(-0.0001f)).isEqualTo(0.0f);
            assertThat(DistanceValidator.clampL2Distance(-1.0f)).isEqualTo(0.0f);
            assertThat(DistanceValidator.clampL2Distance(-100.0f)).isEqualTo(0.0f);
            assertThat(DistanceValidator.clampL2Distance(Float.NEGATIVE_INFINITY)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("NaN is preserved")
        void nanPreserved() {
            assertThat(DistanceValidator.clampL2Distance(Float.NaN)).isNaN();
        }

        @Test
        @DisplayName("Positive infinity is preserved")
        void positiveInfinityPreserved() {
            assertThat(DistanceValidator.clampL2Distance(Float.POSITIVE_INFINITY)).isEqualTo(Float.POSITIVE_INFINITY);
        }
    }

    // ==================== Distance Function Integration ====================

    @Nested
    @DisplayName("Distance Function Integration")
    class DistanceFunctionIntegration {

        @Test
        @DisplayName("isValidDistance with CosineSimilarity")
        void isValidWithCosine() {
            DistanceFunction cosine = CosineSimilarity.getInstance();
            assertThat(DistanceValidator.isValidDistance(0.5f, cosine)).isTrue();
            assertThat(DistanceValidator.isValidDistance(1.5f, cosine)).isFalse();
            assertThat(DistanceValidator.isValidDistance(-1.5f, cosine)).isFalse();
        }

        @Test
        @DisplayName("isValidDistance with L2Distance")
        void isValidWithL2() {
            DistanceFunction l2 = L2Distance.getInstance();
            assertThat(DistanceValidator.isValidDistance(1.0f, l2)).isTrue();
            assertThat(DistanceValidator.isValidDistance(-1.0f, l2)).isFalse();
            assertThat(DistanceValidator.isValidDistance(0.0f, l2)).isTrue();
        }

        @Test
        @DisplayName("isValidDistance with InnerProduct")
        void isValidWithInnerProduct() {
            DistanceFunction innerProduct = InnerProduct.getInstance();
            assertThat(DistanceValidator.isValidDistance(1.0f, innerProduct)).isTrue();
            assertThat(DistanceValidator.isValidDistance(-1.0f, innerProduct)).isTrue();
            assertThat(DistanceValidator.isValidDistance(0.0f, innerProduct)).isTrue();
        }

        @Test
        @DisplayName("isValidDistance with null throws NPE")
        void isValidWithNull() {
            assertThatThrownBy(() -> DistanceValidator.isValidDistance(0.0f, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("DistanceFunction");
        }

        @Test
        @DisplayName("clampDistance with CosineSimilarity")
        void clampWithCosine() {
            DistanceFunction cosine = CosineSimilarity.getInstance();
            assertThat(DistanceValidator.clampDistance(0.5f, cosine)).isEqualTo(0.5f);
            assertThat(DistanceValidator.clampDistance(1.5f, cosine)).isEqualTo(1.0f);
            assertThat(DistanceValidator.clampDistance(-1.5f, cosine)).isEqualTo(-1.0f);
        }

        @Test
        @DisplayName("clampDistance with L2Distance")
        void clampWithL2() {
            DistanceFunction l2 = L2Distance.getInstance();
            assertThat(DistanceValidator.clampDistance(1.0f, l2)).isEqualTo(1.0f);
            assertThat(DistanceValidator.clampDistance(-1.0f, l2)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("clampDistance with null throws NPE")
        void clampWithNull() {
            assertThatThrownBy(() -> DistanceValidator.clampDistance(0.0f, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("DistanceFunction");
        }
    }

    // ==================== Utility Methods ====================

    @Nested
    @DisplayName("Utility Methods")
    class UtilityMethods {

        @Test
        @DisplayName("isFinite returns correct values")
        void isFinite() {
            assertThat(DistanceValidator.isFinite(0.0f)).isTrue();
            assertThat(DistanceValidator.isFinite(1.0f)).isTrue();
            assertThat(DistanceValidator.isFinite(-1.0f)).isTrue();
            assertThat(DistanceValidator.isFinite(Float.MAX_VALUE)).isTrue();
            assertThat(DistanceValidator.isFinite(Float.MIN_VALUE)).isTrue();
            assertThat(DistanceValidator.isFinite(Float.NaN)).isFalse();
            assertThat(DistanceValidator.isFinite(Float.POSITIVE_INFINITY)).isFalse();
            assertThat(DistanceValidator.isFinite(Float.NEGATIVE_INFINITY)).isFalse();
        }

        @Test
        @DisplayName("isValidResult returns correct values")
        void isValidResult() {
            assertThat(DistanceValidator.isValidResult(0.0f)).isTrue();
            assertThat(DistanceValidator.isValidResult(Float.POSITIVE_INFINITY)).isTrue();
            assertThat(DistanceValidator.isValidResult(Float.NEGATIVE_INFINITY)).isTrue();
            assertThat(DistanceValidator.isValidResult(Float.NaN)).isFalse();
        }

        @Test
        @DisplayName("getValidRangeDescription returns correct strings")
        void getValidRangeDescription() {
            assertThat(DistanceValidator.getValidRangeDescription(CosineSimilarity.getInstance()))
                .isEqualTo("[-1.0, 1.0]");
            assertThat(DistanceValidator.getValidRangeDescription(L2Distance.getInstance()))
                .isEqualTo("[0.0, +infinity)");
            assertThat(DistanceValidator.getValidRangeDescription(InnerProduct.getInstance()))
                .isEqualTo("(-infinity, +infinity)");
        }

        @Test
        @DisplayName("getValidRangeDescription with null throws NPE")
        void getValidRangeDescriptionWithNull() {
            assertThatThrownBy(() -> DistanceValidator.getValidRangeDescription(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("DistanceFunction");
        }
    }

    // ==================== validateAndClamp ====================

    @Nested
    @DisplayName("validateAndClamp")
    class ValidateAndClampTests {

        @Test
        @DisplayName("Returns clamped value for out-of-range inputs")
        void returnsClampedValue() {
            DistanceFunction cosine = CosineSimilarity.getInstance();
            assertThat(DistanceValidator.validateAndClamp(1.5f, cosine)).isEqualTo(1.0f);
            assertThat(DistanceValidator.validateAndClamp(-1.5f, cosine)).isEqualTo(-1.0f);

            DistanceFunction l2 = L2Distance.getInstance();
            assertThat(DistanceValidator.validateAndClamp(-1.0f, l2)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("Returns unchanged value for in-range inputs")
        void returnsUnchangedValue() {
            DistanceFunction cosine = CosineSimilarity.getInstance();
            assertThat(DistanceValidator.validateAndClamp(0.5f, cosine)).isEqualTo(0.5f);

            DistanceFunction l2 = L2Distance.getInstance();
            assertThat(DistanceValidator.validateAndClamp(5.0f, l2)).isEqualTo(5.0f);
        }

        @Test
        @DisplayName("Preserves NaN")
        void preservesNaN() {
            DistanceFunction cosine = CosineSimilarity.getInstance();
            assertThat(DistanceValidator.validateAndClamp(Float.NaN, cosine)).isNaN();

            DistanceFunction l2 = L2Distance.getInstance();
            assertThat(DistanceValidator.validateAndClamp(Float.NaN, l2)).isNaN();
        }

        @Test
        @DisplayName("With null throws NPE")
        void withNull() {
            assertThatThrownBy(() -> DistanceValidator.validateAndClamp(0.0f, null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    // ==================== Score Normalization ====================

    @Nested
    @DisplayName("Score Normalization")
    class ScoreNormalization {

        @Nested
        @DisplayName("Cosine Similarity Normalization")
        class CosineNormalization {

            @Test
            @DisplayName("Cosine 1.0 (identical) normalizes to score 1.0")
            void identicalVectors() {
                assertThat(DistanceValidator.normalizeCosineToScore(1.0f)).isEqualTo(1.0f);
            }

            @Test
            @DisplayName("Cosine 0.0 (orthogonal) normalizes to score 0.5")
            void orthogonalVectors() {
                assertThat(DistanceValidator.normalizeCosineToScore(0.0f)).isEqualTo(0.5f);
            }

            @Test
            @DisplayName("Cosine -1.0 (opposite) normalizes to score 0.0")
            void oppositeVectors() {
                assertThat(DistanceValidator.normalizeCosineToScore(-1.0f)).isEqualTo(0.0f);
            }

            @Test
            @DisplayName("Cosine 0.5 normalizes to score 0.75")
            void positiveSimilarity() {
                assertThat(DistanceValidator.normalizeCosineToScore(0.5f)).isEqualTo(0.75f);
            }

            @Test
            @DisplayName("Cosine -0.5 normalizes to score 0.25")
            void negativeSimilarity() {
                assertThat(DistanceValidator.normalizeCosineToScore(-0.5f)).isEqualTo(0.25f);
            }

            @Test
            @DisplayName("NaN input produces NaN output")
            void nanInput() {
                assertThat(DistanceValidator.normalizeCosineToScore(Float.NaN)).isNaN();
            }

            @Test
            @DisplayName("Out of range values are clamped before normalization")
            void outOfRangeClamped() {
                // 1.5 clamped to 1.0 → normalized to 1.0
                assertThat(DistanceValidator.normalizeCosineToScore(1.5f)).isEqualTo(1.0f);
                // -1.5 clamped to -1.0 → normalized to 0.0
                assertThat(DistanceValidator.normalizeCosineToScore(-1.5f)).isEqualTo(0.0f);
            }
        }

        @Nested
        @DisplayName("L2 Distance Normalization")
        class L2Normalization {

            @Test
            @DisplayName("L2 distance 0.0 (identical) normalizes to score 1.0")
            void identicalVectors() {
                assertThat(DistanceValidator.normalizeL2ToScore(0.0f)).isEqualTo(1.0f);
            }

            @Test
            @DisplayName("L2 distance 1.0 normalizes to score 0.5")
            void distanceOne() {
                assertThat(DistanceValidator.normalizeL2ToScore(1.0f)).isEqualTo(0.5f);
            }

            @Test
            @DisplayName("Higher L2 distance produces lower score")
            void higherDistanceLowerScore() {
                float score0 = DistanceValidator.normalizeL2ToScore(0.0f);
                float score1 = DistanceValidator.normalizeL2ToScore(1.0f);
                float score2 = DistanceValidator.normalizeL2ToScore(2.0f);

                assertThat(score0).isGreaterThan(score1);
                assertThat(score1).isGreaterThan(score2);
            }

            @Test
            @DisplayName("NaN input produces NaN output")
            void nanInput() {
                assertThat(DistanceValidator.normalizeL2ToScore(Float.NaN)).isNaN();
            }

            @Test
            @DisplayName("Negative values are clamped to 0 before normalization")
            void negativeClamped() {
                // -1.0 clamped to 0.0 → normalized to 1.0
                assertThat(DistanceValidator.normalizeL2ToScore(-1.0f)).isEqualTo(1.0f);
            }

            @Test
            @DisplayName("Large distances produce scores close to 0")
            void largeDistance() {
                float score = DistanceValidator.normalizeL2ToScore(1000.0f);
                assertThat(score).isGreaterThan(0.0f);
                assertThat(score).isLessThan(0.01f);
            }
        }

        @Nested
        @DisplayName("Inner Product Normalization")
        class InnerProductNormalization {

            @Test
            @DisplayName("Inner product 0.0 normalizes to score 0.5")
            void zeroProduct() {
                assertThat(DistanceValidator.normalizeInnerProductToScore(0.0f)).isEqualTo(0.5f);
            }

            @Test
            @DisplayName("Positive infinity produces score 1.0")
            void positiveInfinity() {
                assertThat(DistanceValidator.normalizeInnerProductToScore(Float.POSITIVE_INFINITY)).isEqualTo(1.0f);
            }

            @Test
            @DisplayName("Negative infinity produces score 0.0")
            void negativeInfinity() {
                assertThat(DistanceValidator.normalizeInnerProductToScore(Float.NEGATIVE_INFINITY)).isEqualTo(0.0f);
            }

            @Test
            @DisplayName("NaN input produces NaN output")
            void nanInput() {
                assertThat(DistanceValidator.normalizeInnerProductToScore(Float.NaN)).isNaN();
            }

            @Test
            @DisplayName("Higher inner product produces higher score")
            void higherProductHigherScore() {
                float scoreNeg = DistanceValidator.normalizeInnerProductToScore(-10.0f);
                float score0 = DistanceValidator.normalizeInnerProductToScore(0.0f);
                float scorePos = DistanceValidator.normalizeInnerProductToScore(10.0f);

                assertThat(scoreNeg).isLessThan(score0);
                assertThat(score0).isLessThan(scorePos);
            }

            @Test
            @DisplayName("All outputs are in valid range [0, 1]")
            void outputsInValidRange() {
                for (float ip : new float[]{-100.0f, -10.0f, -1.0f, 0.0f, 1.0f, 10.0f, 100.0f}) {
                    float score = DistanceValidator.normalizeInnerProductToScore(ip);
                    assertThat(score).isGreaterThanOrEqualTo(0.0f);
                    assertThat(score).isLessThanOrEqualTo(1.0f);
                }
            }
        }

        @Nested
        @DisplayName("Generic Normalization")
        class GenericNormalization {

            @Test
            @DisplayName("normalizeToScore with CosineSimilarity")
            void withCosine() {
                DistanceFunction cosine = CosineSimilarity.getInstance();
                assertThat(DistanceValidator.normalizeToScore(1.0f, cosine)).isEqualTo(1.0f);
                assertThat(DistanceValidator.normalizeToScore(0.0f, cosine)).isEqualTo(0.5f);
                assertThat(DistanceValidator.normalizeToScore(-1.0f, cosine)).isEqualTo(0.0f);
            }

            @Test
            @DisplayName("normalizeToScore with L2Distance")
            void withL2() {
                DistanceFunction l2 = L2Distance.getInstance();
                assertThat(DistanceValidator.normalizeToScore(0.0f, l2)).isEqualTo(1.0f);
                assertThat(DistanceValidator.normalizeToScore(1.0f, l2)).isEqualTo(0.5f);
            }

            @Test
            @DisplayName("normalizeToScore with InnerProduct")
            void withInnerProduct() {
                DistanceFunction ip = InnerProduct.getInstance();
                assertThat(DistanceValidator.normalizeToScore(0.0f, ip)).isEqualTo(0.5f);
            }

            @Test
            @DisplayName("normalizeToScore with null throws NPE")
            void withNull() {
                assertThatThrownBy(() -> DistanceValidator.normalizeToScore(0.0f, null))
                    .isInstanceOf(NullPointerException.class);
            }
        }

        @Nested
        @DisplayName("Validate Clamp and Normalize")
        class ValidateClampAndNormalize {

            @Test
            @DisplayName("Clamps and normalizes out-of-range cosine values")
            void cosineOutOfRange() {
                DistanceFunction cosine = CosineSimilarity.getInstance();
                // 2.0 clamped to 1.0 → normalized to 1.0
                assertThat(DistanceValidator.validateClampAndNormalize(2.0f, cosine)).isEqualTo(1.0f);
                // -2.0 clamped to -1.0 → normalized to 0.0
                assertThat(DistanceValidator.validateClampAndNormalize(-2.0f, cosine)).isEqualTo(0.0f);
            }

            @Test
            @DisplayName("Clamps and normalizes negative L2 values")
            void l2Negative() {
                DistanceFunction l2 = L2Distance.getInstance();
                // -5.0 clamped to 0.0 → normalized to 1.0
                assertThat(DistanceValidator.validateClampAndNormalize(-5.0f, l2)).isEqualTo(1.0f);
            }

            @Test
            @DisplayName("Preserves NaN")
            void preservesNaN() {
                DistanceFunction cosine = CosineSimilarity.getInstance();
                assertThat(DistanceValidator.validateClampAndNormalize(Float.NaN, cosine)).isNaN();
            }

            @Test
            @DisplayName("With null throws NPE")
            void withNull() {
                assertThatThrownBy(() -> DistanceValidator.validateClampAndNormalize(0.0f, null))
                    .isInstanceOf(NullPointerException.class);
            }
        }
    }

    // ==================== Constructor ====================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Private constructor throws AssertionError")
        void privateConstructor() {
            assertThatThrownBy(() -> {
                var constructor = DistanceValidator.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                constructor.newInstance();
            }).hasCauseInstanceOf(AssertionError.class);
        }
    }

    // ==================== Parameterized Tests ====================

    @ParameterizedTest
    @CsvSource({
        "0.0, true",
        "1.0, true",
        "-1.0, true",
        "0.5, true",
        "-0.5, true",
        "1.001, false",
        "-1.001, false",
        "2.0, false",
        "-2.0, false"
    })
    @DisplayName("Cosine validity parameterized")
    void cosineValidityParameterized(float value, boolean expected) {
        assertThat(DistanceValidator.isValidCosineSimilarity(value)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(floats = {0.0f, 0.001f, 1.0f, 10.0f, 1000.0f, Float.MAX_VALUE})
    @DisplayName("Valid L2 distances")
    void validL2Distances(float value) {
        assertThat(DistanceValidator.isValidL2Distance(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(floats = {-0.001f, -1.0f, -100.0f, Float.NEGATIVE_INFINITY})
    @DisplayName("Invalid L2 distances")
    void invalidL2Distances(float value) {
        assertThat(DistanceValidator.isValidL2Distance(value)).isFalse();
    }
}
