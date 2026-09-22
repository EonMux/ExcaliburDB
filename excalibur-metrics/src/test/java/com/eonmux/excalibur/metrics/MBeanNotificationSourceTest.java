package com.eonmux.excalibur.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.management.MBeanServer;
import javax.management.Notification;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a notification delivered through the MBeanServer identifies its emitter.
 *
 * <p>A listener registered with {@code MBeanServer.addNotificationListener(ObjectName, ...)}
 * is entitled to a notification whose source is the emitting MBean's ObjectName - that is how
 * a client with one listener across many MBeans tells them apart. The server substitutes the
 * ObjectName only when the notification's source is the MBean instance itself, so emitting a
 * bare type String silently defeats it and every notification arrives indistinguishable.</p>
 *
 * @author ExcaliburDB
 */
class MBeanNotificationSourceTest {

    private static final String HEALTH_NAME = "com.eonmux.excalibur:type=SystemHealth";
    private static final String EPOCH_NAME = "com.eonmux.excalibur:type=VisibilityEpoch";

    private MBeanServer mbeanServer;
    private ObjectName healthName;
    private ObjectName epochName;

    @BeforeEach
    void setUp() throws Exception {
        mbeanServer = ManagementFactory.getPlatformMBeanServer();
        healthName = new ObjectName(HEALTH_NAME);
        epochName = new ObjectName(EPOCH_NAME);
        unregister();
        mbeanServer.registerMBean(Metrics.getSystemHealthMBean(), healthName);
        mbeanServer.registerMBean(Metrics.getVisibilityEpochMBean(), epochName);
    }

    @AfterEach
    void tearDown() throws Exception {
        unregister();
    }

    private void unregister() throws Exception {
        for (ObjectName name : new ObjectName[]{healthName, epochName}) {
            if (mbeanServer.isRegistered(name)) {
                mbeanServer.unregisterMBean(name);
            }
        }
    }

    @Test
    @DisplayName("Notification source is the emitting MBean's ObjectName")
    void notificationSourceIsTheObjectName() throws Exception {
        DefaultNotificationObserver observer = new DefaultNotificationObserver();
        mbeanServer.addNotificationListener(healthName, observer, null, null);
        try {
            Metrics.setHealthScore(0.6);

            Notification notification = awaitNotification(observer);
            assertInstanceOf(ObjectName.class, notification.getSource(),
                "source must be an ObjectName, not the MBean type string");
            assertEquals(healthName, notification.getSource(),
                "source must name the MBean that emitted the notification");
        } finally {
            mbeanServer.removeNotificationListener(healthName, observer);
            observer.close();
        }
    }

    @Test
    @DisplayName("One listener on two MBeans can tell their notifications apart")
    void sourcesDistinguishEmitters() throws Exception {
        DefaultNotificationObserver observer = new DefaultNotificationObserver();
        mbeanServer.addNotificationListener(healthName, observer, null, null);
        mbeanServer.addNotificationListener(epochName, observer, null, null);
        try {
            Metrics.setHealthScore(0.55);
            Metrics.recordEpoch(4242L);

            boolean sawHealth = false;
            boolean sawEpoch = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && !(sawHealth && sawEpoch)) {
                for (Notification notification : observer.getNotifications()) {
                    if (healthName.equals(notification.getSource())) {
                        sawHealth = true;
                    } else if (epochName.equals(notification.getSource())) {
                        sawEpoch = true;
                    }
                }
            }

            assertTrue(sawHealth, "a notification should be attributable to " + HEALTH_NAME);
            assertTrue(sawEpoch, "a notification should be attributable to " + EPOCH_NAME);
        } finally {
            mbeanServer.removeNotificationListener(healthName, observer);
            mbeanServer.removeNotificationListener(epochName, observer);
            observer.close();
        }
    }

    private static Notification awaitNotification(DefaultNotificationObserver observer) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Notification[] received = observer.getNotifications();
            if (received.length > 0) {
                return received[0];
            }
        }
        throw new AssertionError("no notification was delivered within 5s");
    }
}
