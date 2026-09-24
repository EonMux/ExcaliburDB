package com.eonmux.excalibur.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link MetricsRegistry} puts beans on the platform MBean server and records
 * exactly what the server accepted.
 *
 * <p>Every name used here is unique to the test that uses it: the platform server is
 * process-wide and refuses a duplicate name, so a fixed name would make these tests depend on
 * the order they ran in relative to the other JMX tests.</p>
 *
 * @author ExcaliburDB
 */
class MetricsRegistryTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private final List<String> toUnregister = new ArrayList<>();

    @AfterEach
    void unregister() throws Exception {
        for (String name : toUnregister) {
            ObjectName objectName = new ObjectName(name);
            if (server.isRegistered(objectName)) {
                server.unregisterMBean(objectName);
            }
        }
    }

    private String uniqueName(String type) {
        String name = "excalibur.registrytest:type=" + type + ",run=" + UNIQUE.incrementAndGet();
        toUnregister.add(name);
        return name;
    }

    @Test
    @DisplayName("A registered bean is on the platform server and remembered under its name")
    void registrationReachesThePlatformServer() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        String name = uniqueName("SystemHealth");

        registry.registerMBean(Metrics.getSystemHealthMBean(), name);

        assertTrue(server.isRegistered(new ObjectName(name)),
            "the bean must be visible to JMX consoles");
        assertSame(Metrics.getSystemHealthMBean(), registry.getMBean(name));
        assertEquals(List.of(name), registry.getRegisteredMBeanNames());
    }

    @Test
    @DisplayName("A console's read through the server reaches the bean's live value")
    void serverReadsTheLiveValue() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        String name = uniqueName("VisibilityEpoch");
        registry.registerMBean(Metrics.getVisibilityEpochMBean(), name);

        Metrics.recordEpoch(4242L);

        assertEquals(4242L, server.getAttribute(new ObjectName(name), "epoch"));
    }

    @Test
    @DisplayName("A name the server refuses leaves no trace in the registry")
    void refusedRegistrationIsNotRemembered() throws Exception {
        MetricsRegistry first = new MetricsRegistry();
        MetricsRegistry second = new MetricsRegistry();
        String name = uniqueName("HnswQueueDepth");
        first.registerMBean(Metrics.getHnswQueueDepthMBean(), name);

        assertThrows(InstanceAlreadyExistsException.class,
            () -> second.registerMBean(Metrics.getHnswQueueDepthMBean(), name));

        assertNull(second.getMBean(name));
        assertTrue(second.getRegisteredMBeanNames().isEmpty());
    }

    @Test
    @DisplayName("A malformed name is refused before anything is registered")
    void malformedNameIsRefused() {
        MetricsRegistry registry = new MetricsRegistry();

        assertThrows(MalformedObjectNameException.class,
            () -> registry.registerMBean(Metrics.getQueryLatencyMBean(), "not a jmx name"));

        assertTrue(registry.getRegisteredMBeanNames().isEmpty());
    }

    @Test
    @DisplayName("An unknown name reads as absent, and a fresh registry holds nothing")
    void unknownNameIsAbsent() {
        MetricsRegistry registry = new MetricsRegistry();

        assertNull(registry.getMBean("no-such-mbean"));
        assertTrue(registry.getRegisteredMBeanNames().isEmpty());
    }

    @Test
    @DisplayName("The name list is a snapshot the caller cannot use to change the registry")
    void nameListIsASnapshot() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        String name = uniqueName("IngestionQueue");
        registry.registerMBean(Metrics.getIngestionQueueMBean(), name);

        List<String> names = registry.getRegisteredMBeanNames();

        assertThrows(UnsupportedOperationException.class, () -> names.add("other"));
        assertEquals(List.of(name), registry.getRegisteredMBeanNames());
    }
}
