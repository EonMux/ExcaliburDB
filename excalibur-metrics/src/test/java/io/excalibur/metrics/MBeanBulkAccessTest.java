package io.excalibur.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.ReflectionException;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bulk and write-side halves of the {@link DynamicMBean} contract.
 *
 * <p>JMX consoles read through {@code getAttributes(String[])} rather than one attribute at a
 * time, so that path - not {@code getAttribute} - is what an operator actually sees. It differs
 * from the single-attribute path in one way that matters: it swallows failures per attribute
 * instead of throwing, which is right for a console that asked for a stale name but means a
 * silently short list is the only symptom. What is pinned here is that the list is short
 * <em>only</em> for names that do not exist, and that every real attribute survives the bulk
 * call with the same value the single call gives.</p>
 *
 * <p>The write side matters for a different reason: these MBeans are read-only views of
 * counters, and a console offering an editable field that appears to succeed while changing
 * nothing is worse than one that refuses.</p>
 */
@DisplayName("MBean bulk access")
class MBeanBulkAccessTest {

    private static List<DynamicMBean> allMBeans() {
        return List.of(
            Metrics.getQueryLatencyMBean(),
            Metrics.getSystemHealthMBean(),
            Metrics.getIngestionQueueMBean(),
            Metrics.getVisibilityEpochMBean(),
            Metrics.getCuckooFpRateMBean(),
            Metrics.getHnswQueueDepthMBean(),
            Metrics.getIngestionThroughputMBean()
        );
    }

    private static void giveEveryCounterAValue() {
        Metrics.recordQueryLatency(42L);
        Metrics.setHealthScore(0.7);
        Metrics.setEstimatedCoverage(0.3);
        Metrics.recordEpoch(9L);
        Metrics.recordCuckooFpRate(0.0004);
        Metrics.recordHnswDepth(1234L);
        Metrics.recordTailScanMetrics(10, 4);
        Metrics.recordIngestEntry();
    }

    private static String[] declaredNames(DynamicMBean mbean) throws Exception {
        return java.util.Arrays.stream(mbean.getMBeanInfo().getAttributes())
            .map(MBeanAttributeInfo::getName)
            .toArray(String[]::new);
    }

    @Test
    @DisplayName("a bulk read returns every declared attribute")
    void bulkReadReturnsEverything() throws Exception {
        giveEveryCounterAValue();

        for (DynamicMBean mbean : allMBeans()) {
            String[] names = declaredNames(mbean);
            AttributeList list = mbean.getAttributes(names);

            assertThat(list)
                .as("%s declares %d attributes", mbean.getClass().getSimpleName(), names.length)
                .hasSize(names.length);
            assertThat(list.asList().stream().map(Attribute::getName).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(names);
        }
    }

    @Test
    @DisplayName("a bulk read agrees with reading the same attributes one at a time")
    void bulkAndSingleReadsAgree() throws Exception {
        giveEveryCounterAValue();

        for (DynamicMBean mbean : allMBeans()) {
            String[] names = declaredNames(mbean);
            Map<String, Object> bulk = mbean.getAttributes(names).asList().stream()
                .collect(Collectors.toMap(Attribute::getName, Attribute::getValue));

            for (String name : names) {
                assertThat(bulk.get(name))
                    .as("%s.%s read in bulk", mbean.getClass().getSimpleName(), name)
                    .isEqualTo(mbean.getAttribute(name));
            }
        }
    }

    @Test
    @DisplayName("an unknown name is skipped rather than failing the whole read")
    void unknownNamesAreSkipped() throws Exception {
        // A console asking for a name from an older build must still get the rest.
        for (DynamicMBean mbean : allMBeans()) {
            String[] names = declaredNames(mbean);
            String[] withGarbage = new String[names.length + 2];
            withGarbage[0] = "no-such-attribute";
            System.arraycopy(names, 0, withGarbage, 1, names.length);
            withGarbage[withGarbage.length - 1] = "";

            AttributeList list = mbean.getAttributes(withGarbage);

            assertThat(list)
                .as("%s drops only the two names it does not serve",
                    mbean.getClass().getSimpleName())
                .hasSize(names.length);
            assertThat(list.asList().stream().map(Attribute::getName).toList())
                .doesNotContain("no-such-attribute", "");
        }
    }

    @Test
    @DisplayName("an empty request returns an empty list rather than everything")
    void emptyRequestReturnsNothing() {
        for (DynamicMBean mbean : allMBeans()) {
            assertThat(mbean.getAttributes(new String[0])).isEmpty();
        }
    }

    @Test
    @DisplayName("reading an attribute that does not exist is an error, not a null")
    void unknownSingleAttributeThrows() {
        for (DynamicMBean mbean : allMBeans()) {
            assertThatThrownBy(() -> mbean.getAttribute("no-such-attribute"))
                .as(mbean.getClass().getSimpleName())
                .isInstanceOf(AttributeNotFoundException.class);
        }
    }

    @Test
    @DisplayName("every attribute is read-only, and says so rather than silently ignoring a write")
    void writesAreRefused() throws Exception {
        for (DynamicMBean mbean : allMBeans()) {
            String name = declaredNames(mbean)[0];

            assertThatThrownBy(() -> mbean.setAttribute(new Attribute(name, 1L)))
                .as("%s.%s", mbean.getClass().getSimpleName(), name)
                .isInstanceOf(AttributeNotFoundException.class);

            assertThat(mbean.setAttributes(new AttributeList(
                List.of(new Attribute(name, 1L)))))
                .as("a bulk write reports that it changed nothing")
                .isEmpty();
        }
    }

    @Test
    @DisplayName("these MBeans expose no operations")
    void invokeIsRefused() {
        for (DynamicMBean mbean : allMBeans()) {
            assertThatThrownBy(() -> mbean.invoke("reset", new Object[0], new String[0]))
                .as(mbean.getClass().getSimpleName())
                .isInstanceOf(ReflectionException.class);
        }
    }

    /** Distinguishes the object names this class registers from every other test's. */
    private static final AtomicInteger UNIQUE = new AtomicInteger();

    /**
     * Registers every MBean under names unique to this call and returns them by name.
     *
     * <p>The platform MBean server is process-wide and refuses a duplicate object name, so a
     * fixed name here would make this class depend on the order it happened to run in relative
     * to the other JMX tests.</p>
     */
    private static Map<String, DynamicMBean> registerAll(MetricsRegistry registry,
                                                         List<String> toUnregister)
            throws Exception {
        String run = "test" + UNIQUE.incrementAndGet() + "-" + Thread.currentThread().threadId();
        Map<String, DynamicMBean> byName = new LinkedHashMap<>();
        for (DynamicMBean mbean : allMBeans()) {
            String name = "io.excalibur.test:type=" + mbean.getClass().getSimpleName()
                + ",run=" + run;
            registry.registerMBean(mbean, name);
            toUnregister.add(name);
            byName.put(name, mbean);
        }
        return byName;
    }

    private static void unregister(List<String> names) {
        for (String name : names) {
            try {
                ManagementFactory.getPlatformMBeanServer()
                    .unregisterMBean(new javax.management.ObjectName(name));
            } catch (Exception ignored) {
                // Best effort: a name that was never registered needs no cleanup.
            }
        }
    }

    @Test
    @DisplayName("the registry collects every attribute of every MBean it holds")
    void registryCollectsEveryAttribute() throws Exception {
        giveEveryCounterAValue();
        MetricsRegistry registry = new MetricsRegistry();
        List<String> registered = new ArrayList<>();
        try {
            Map<String, DynamicMBean> byName = registerAll(registry, registered);

            Map<String, Object> all = registry.getAllMBeanAttributes();

            assertThat(all).isNotEmpty();
            // Keys are qualified by MBean name so two MBeans publishing "depth" stay distinct.
            assertThat(all.keySet()).allMatch(key -> key.contains("."));
            assertThat(registry.getRegisteredMBeanNames())
                .containsExactlyInAnyOrderElementsOf(byName.keySet());

            for (Map.Entry<String, DynamicMBean> entry : byName.entrySet()) {
                for (MBeanAttributeInfo info : entry.getValue().getMBeanInfo().getAttributes()) {
                    assertThat(all)
                        .as("%s.%s is missing from the collected map",
                            entry.getKey(), info.getName())
                        .containsKey(entry.getKey() + "." + info.getName());
                }
            }
        } finally {
            unregister(registered);
        }
    }

    @Test
    @DisplayName("two MBeans publishing the same attribute name stay distinct")
    void sameAttributeOnTwoMBeansDoesNotCollide() throws Exception {
        // Both queue MBeans publish "depth". Qualifying by MBean name is what keeps the
        // collected map from reporting one of them twice and losing the other.
        MetricsRegistry registry = new MetricsRegistry();
        List<String> registered = new ArrayList<>();
        try {
            Map<String, DynamicMBean> byName = registerAll(registry, registered);
            Map<String, Object> all = registry.getAllMBeanAttributes();

            List<String> depthKeys = all.keySet().stream()
                .filter(key -> key.endsWith(".depth")).toList();

            assertThat(depthKeys)
                .as("both queue MBeans publish depth, under their own names")
                .hasSizeGreaterThanOrEqualTo(2);
            assertThat(byName).isNotEmpty();
        } finally {
            unregister(registered);
        }
    }

    @Test
    @DisplayName("a registry holding nothing collects nothing")
    void emptyRegistryCollectsNothing() {
        MetricsRegistry registry = new MetricsRegistry();

        assertThat(registry.getAllMBeanAttributes()).isEmpty();
        assertThat(registry.getRegisteredMBeanNames()).isEmpty();
        assertThat(registry.getAllMBeanNames()).isEmpty();
    }

    @Test
    @DisplayName("the registry reads the same values the MBeans serve directly")
    void registryReadsAgreeWithTheMBeans() throws Exception {
        Metrics.recordEpoch(77L);
        Metrics.setEstimatedCoverage(0.25);
        Metrics.recordHnswDepth(4321L);
        Metrics.setHealthScore(0.55);
        MetricsRegistry registry = new MetricsRegistry();

        assertThat(registry.getEpochFromMBean()).isEqualTo(77L);
        assertThat(registry.getCoverageFromMBean()).isEqualTo(0.25);
        assertThat(registry.getQueueDepthFromMBean()).isEqualTo(4321L);
        assertThat(registry.getHealthScoreFromMBean()).isEqualTo(0.55);
    }

    @Test
    @DisplayName("an unregistered MBean name reads as absent rather than throwing")
    void unknownMBeanNameIsAbsent() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        List<String> registered = new ArrayList<>();
        try {
            Map<String, DynamicMBean> byName = registerAll(registry, registered);

            assertThat(registry.getMBean("no-such-mbean")).isNull();
            assertThat(registry.getAllMBeanNames())
                .containsExactlyInAnyOrderElementsOf(byName.keySet());
        } finally {
            unregister(registered);
        }
    }

    @Test
    @DisplayName("registry reads fall back to the counters when the MBean is not registered")
    void registryFallsBackWithoutMBeans() {
        // getEpochFromMBean and friends look up fixed canonical names. A registry that was
        // never given them must still answer from the counters rather than returning a
        // default that looks like a real reading of zero.
        Metrics.recordEpoch(123L);
        Metrics.setEstimatedCoverage(0.5);
        MetricsRegistry registry = new MetricsRegistry();

        assertThat(registry.getEpochFromMBean()).isEqualTo(Metrics.getCurrentEpoch());
        assertThat(registry.getCoverageFromMBean()).isEqualTo(Metrics.getEstimatedCoverage());
        assertThat(registry.getHealthScoreFromMBean()).isEqualTo(Metrics.getHealthScore());
    }

    @Test
    @DisplayName("latency percentiles and counts are served through the registry")
    void latencyIsServedThroughTheRegistry() {
        MetricsRegistry registry = new MetricsRegistry();
        registry.clear();
        for (int i = 0; i < 100; i++) {
            Metrics.recordQueryLatency(10L);
        }

        assertThat(registry.getLatencyCount()).isPositive();
        assertThat(registry.getLatencyPercentile(50)).isNotNegative();
        assertThat(registry.getLatencyPercentile(99)).isNotNegative();
        assertThat(registry.getLatencyPercentile(50))
            .as("a lower percentile is never above a higher one")
            .isLessThanOrEqualTo(registry.getLatencyPercentile(99));
    }
}
