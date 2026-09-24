package com.eonmux.excalibur.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.ReflectionException;

import java.util.List;
import java.util.Map;
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
            Metrics.getHnswQueueDepthMBean(),
            Metrics.getIngestionThroughputMBean()
        );
    }

    private static void giveEveryCounterAValue() {
        Metrics.recordQueryLatency(42L);
        Metrics.setHealthScore(0.7);
        Metrics.setEstimatedCoverage(0.3);
        Metrics.recordEpoch(9L);
        Metrics.recordHnswDepth(1234L);
        Metrics.recordTailScanMetrics(10, 4);
        Metrics.recordIngestEntries(1);
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
}
