package com.eonmux.excalibur.vector.distance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loading the SIMD kernels: whatever stops them loading must leave the scalar paths in charge
 * rather than failing the class every distance function depends on.
 */
@DisplayName("Vector API probe")
class VectorApiProbeTest {

    @Test
    @DisplayName("kernels that load are used")
    void loadedKernelsAreUsed() {
        VectorApiSupport.VectorOperations kernels = new SimdVectorOperations();

        assertThat(VectorApiSupport.loadOperations(() -> kernels)).isSameAs(kernels);
    }

    @Test
    @DisplayName("a missing module falls back to scalar")
    void aMissingModuleFallsBack() {
        assertThat(VectorApiSupport.loadOperations(() -> {
            throw new ClassNotFoundException("jdk.incubator.vector.FloatVector");
        })).isNull();
    }

    @Test
    @DisplayName("a kernel class that fails to initialise falls back to scalar")
    void aFailedInitialisationFallsBack() {
        assertThat(VectorApiSupport.loadOperations(() -> {
            throw new ExceptionInInitializerError(new IllegalStateException("no preferred species"));
        })).isNull();
    }

    @Test
    @DisplayName("any linkage failure falls back to scalar")
    void aLinkageFailureFallsBack() {
        assertThat(VectorApiSupport.loadOperations(() -> {
            throw new UnsupportedClassVersionError("compiled for a newer JVM");
        })).isNull();
    }

    @Test
    @DisplayName("a runtime failure while probing falls back to scalar")
    void aRuntimeFailureFallsBack() {
        assertThat(VectorApiSupport.loadOperations(() -> {
            throw new UnsupportedOperationException("species not supported on this CPU");
        })).isNull();
    }
}
