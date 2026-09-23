package com.eonmux.excalibur.integration;

import com.eonmux.excalibur.vector.distance.VectorApiSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The end-to-end suites run the SIMD distance kernels, as the node does.
 *
 * <p>The node starts with {@code --add-modules=jdk.incubator.vector}, and without it
 * {@link VectorApiSupport} does not fail: it logs one INFO line and falls back to scalar
 * arithmetic. A test JVM missing the flag therefore passes every assertion in this module while
 * exercising a distance path production never takes, and nothing about any other test's outcome
 * would say so. This asserts the one fact that decides which path the other suites exercise, so
 * dropping the flag from this module's test JVM is a failure rather than a silent downgrade.</p>
 */
@DisplayName("Vector API resolution in the integration test JVM")
class VectorApiResolutionTest {

    @Test
    @DisplayName("the incubating Vector API is resolved, so distances run on the SIMD kernels")
    void theSimdKernelsAreTheOnesUnderTest() {
        VectorApiSupport support = VectorApiSupport.getInstance();

        assertThat(support.isAvailable())
            .as("jdk.incubator.vector must be resolved in this JVM, as it is in the node's; "
                + "see excalibur-integration/build.gradle.kts")
            .isTrue();
        assertThat(support.getPreferredSpeciesLength())
            .as("an available Vector API reports how many float lanes a kernel step covers")
            .isPositive();
    }
}
