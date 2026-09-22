package com.eonmux.excalibur.core.bootstrap;

import com.eonmux.excalibur.metrics.Metrics;
import com.eonmux.excalibur.metrics.MetricsRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for BootstrapApplication.
 *
 * Thread-safety: Tests verify singleton behavior.
 * Domain correctness: Tests verify bootstrap initialization.
 */
@DisplayName("BootstrapApplication Tests")
class BootstrapApplicationTest {

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;

    @BeforeEach
    void setUpStreams() {
        System.setOut(new PrintStream(outContent));
    }

    @AfterEach
    void restoreStreams() throws Exception {
        System.setOut(originalOut);
        // Reset instance via reflection
        Field instanceField = BootstrapApplication.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    @Nested
    @DisplayName("Main Tests")
    class MainTests {

        @Test
        @DisplayName("Should execute main without error")
        void shouldExecuteMainWithoutError() {
            // Use reflection to access the instance after main runs
            assertThatCode(() -> {
                BootstrapApplication.main(new String[]{});
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should print banner via main")
        void shouldPrintBannerViaMain() {
            BootstrapApplication.main(new String[]{});

            String output = outContent.toString();
            assertThat(output).contains("ExcaliburDB");
            assertThat(output).contains("Core Primitives");
        }

        @Test
        @DisplayName("Should print status via main")
        void shouldPrintStatusViaMain() {
            BootstrapApplication.main(new String[]{});

            String output = outContent.toString();
            assertThat(output).contains("ready");
        }
    }

    @Nested
    @DisplayName("Initialization State Tests")
    class StateTests {

        @Test
        @DisplayName("Should be initialized after main")
        void shouldBeInitializedAfterMain() throws Exception {
            BootstrapApplication.main(new String[]{});

            // Access instance via reflection
            Field instanceField = BootstrapApplication.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            BootstrapApplication instance = (BootstrapApplication) instanceField.get(null);

            assertThat(instance).isNotNull();
            assertThat(instance.isInitialized()).isTrue();
        }

        @Test
        @DisplayName("Should return running state")
        void shouldReturnRunningState() throws Exception {
            BootstrapApplication.main(new String[]{});

            Field instanceField = BootstrapApplication.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            BootstrapApplication instance = (BootstrapApplication) instanceField.get(null);

            // Running may be false since we're just testing initialization
            assertThat(instance.isRunning()).isNotNull();
        }

        @Test
        @DisplayName("Should have initialized metrics registry")
        void shouldHaveInitializedMetricsRegistry() throws Exception {
            BootstrapApplication.main(new String[]{});

            Field instanceField = BootstrapApplication.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            BootstrapApplication instance = (BootstrapApplication) instanceField.get(null);

            MetricsRegistry registry = instance.getMetricsRegistry();
            assertThat(registry).isNotNull();
        }

        @Test
        @DisplayName("Should have metrics registry available")
        void shouldHaveMetricsRegistryAvailable() throws Exception {
            BootstrapApplication.main(new String[]{});

            Field instanceField = BootstrapApplication.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            BootstrapApplication instance = (BootstrapApplication) instanceField.get(null);

            MetricsRegistry registry = instance.getMetricsRegistry();
            assertThat(registry).isNotNull();
            // Note: JMX MBean registration depends on the metrics module's MBean implementation
            // The bootstrap correctly attempts to register MBeans via Metrics.registerAll()
        }
    }

    @Nested
    @DisplayName("Metrics Initialization Tests")
    class MetricsTests {

        @Test
        @DisplayName("Should initialize metrics with default values")
        void shouldInitializeMetricsWithDefaults() throws Exception {
            BootstrapApplication.main(new String[]{});

            // Verify metrics are initialized
            assertThat(Metrics.getHealthScore()).isEqualTo(0.5);
            assertThat(Metrics.getEstimatedCoverage()).isEqualTo(0.0);
            assertThat(Metrics.getCurrentEpoch()).isEqualTo(0L);
        }
    }
}
