package com.eonmux.excalibur.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;

import javax.management.Notification;
import javax.management.NotificationFilter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MBean Notification Observer functionality.
 *
 * @author ExcaliburDB
 */
class MBeanNotificationObserverTest {

    private DefaultNotificationObserver observer;
    private MBeanNotificationBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        observer = new DefaultNotificationObserver();
        broadcaster = new MBeanNotificationBroadcaster(
            this,
            "TestMBean",
            new String[]{
                MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
                MBeanNotificationBroadcaster.NotificationTypes.SYSTEM_HEALTH
            }
        );
    }

    @AfterEach
    void tearDown() {
        observer.close();
        broadcaster.removeAllListeners();
    }

    // ============================================
    // DefaultNotificationObserver Tests
    // ============================================

    @Test
    @DisplayName("Observer receives notifications when registered")
    void testObserverReceivesNotifications() {
        // Register observer
        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );
        broadcaster.addNotificationListener(info, observer, false);

        // Send notification
        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Test message",
            100L
        );

        // Verify notification received
        assertTrue(observer.hasNotifications());
        assertEquals(1, observer.getNotificationCount());

        Notification[] notifications = observer.getNotifications();
        assertEquals(1, notifications.length);
        assertEquals(MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY, notifications[0].getType());
        assertEquals("Test message", notifications[0].getMessage());
        assertEquals(100L, notifications[0].getUserData());
    }

    @Test
    @DisplayName("Observer receives multiple notifications")
    void testObserverReceivesMultipleNotifications() {
        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );
        broadcaster.addNotificationListener(info, observer, false);

        // Send multiple notifications
        for (int i = 0; i < 10; i++) {
            broadcaster.sendNotification(
                MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
                "Message " + i,
                (long) i
            );
        }

        assertEquals(10, observer.getNotificationCount());
        Notification[] notifications = observer.getNotifications();
        assertEquals(10, notifications.length);
    }

    @Test
    @DisplayName("Observer does not receive notifications after close")
    void testObserverDoesNotReceiveAfterClose() {
        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );
        broadcaster.addNotificationListener(info, observer, false);

        // Send notification before close
        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Before close",
            1L
        );
        assertEquals(1, observer.getNotificationCount());

        // Close observer
        observer.close();

        // Send notification after close
        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "After close",
            2L
        );

        // Should still be 1 (notification after close was ignored)
        assertEquals(1, observer.getNotificationCount());
        assertTrue(observer.isClosed());
    }

    @Test
    @DisplayName("Observer filter works correctly")
    void testObserverFilter() {
        // Create observer with filter that only accepts QUERY_LATENCY
        NotificationFilter filter = notification ->
            notification.getType().equals(MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY);

        DefaultNotificationObserver filteredObserver = new DefaultNotificationObserver(filter, null);

        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );
        broadcaster.addNotificationListener(info, filteredObserver, false);

        // Send notification that passes filter
        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Query latency",
            100L
        );

        // Send notification that should be filtered out
        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.SYSTEM_HEALTH,
            "System health",
            0.95
        );

        // Only one notification should pass
        assertEquals(1, filteredObserver.getNotificationCount());
        assertEquals(MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            filteredObserver.getLatestNotification().getType());

        filteredObserver.close();
    }

    @Test
    @DisplayName("getNotifications clears queue")
    void testGetNotificationsClearsQueue() {
        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );
        broadcaster.addNotificationListener(info, observer, false);

        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Test",
            1L
        );

        assertTrue(observer.hasNotifications());
        Notification[] first = observer.getNotifications();
        assertEquals(1, first.length);

        // After getNotifications(), queue should be empty
        assertFalse(observer.hasNotifications());
        Notification[] second = observer.getNotifications();
        assertEquals(0, second.length);
    }

    @Test
    @DisplayName("peekNotifications does not clear queue")
    void testPeekNotificationsDoesNotClear() {
        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );
        broadcaster.addNotificationListener(info, observer, false);

        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Test",
            1L
        );

        Notification[] first = observer.peekNotifications();
        assertEquals(1, first.length);

        // After peekNotifications(), queue should still have items
        assertTrue(observer.hasNotifications());
        Notification[] second = observer.peekNotifications();
        assertEquals(1, second.length);
    }

    @Test
    @DisplayName("clearNotifications clears queue without closing")
    void testClearNotifications() {
        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );
        broadcaster.addNotificationListener(info, observer, false);

        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Test",
            1L
        );

        assertTrue(observer.hasNotifications());
        assertFalse(observer.isClosed());

        observer.clearNotifications();

        assertFalse(observer.hasNotifications());
        assertFalse(observer.isClosed()); // Should not be closed
    }

    @Test
    @DisplayName("getLatestNotification returns most recent")
    void testGetLatestNotification() {
        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );
        broadcaster.addNotificationListener(info, observer, false);

        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "First",
            1L
        );
        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Second",
            2L
        );
        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Third",
            3L
        );

        Notification latest = observer.getLatestNotification();
        assertNotNull(latest);
        assertEquals("Third", latest.getMessage());
    }

    @Test
    @DisplayName("waitForNotification blocks until notification arrives")
    void testWaitForNotification() throws InterruptedException {
        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );
        broadcaster.addNotificationListener(info, observer, false);

        // Send notification in another thread after delay
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(50);
                broadcaster.sendNotification(
                    MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
                    "Delayed",
                    1L
                );
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.start();

        // Wait should succeed
        boolean received = observer.waitForNotification(500);
        assertTrue(received);
        assertTrue(observer.hasNotifications());

        t.join();
    }

    @Test
    @DisplayName("waitForNotification times out when no notification")
    void testWaitForNotificationTimeout() throws InterruptedException {
        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );
        broadcaster.addNotificationListener(info, observer, false);

        // No notification sent - should timeout
        boolean received = observer.waitForNotification(100);
        assertFalse(received);
        assertFalse(observer.hasNotifications());
    }

    // ============================================
    // MBeanNotificationBroadcaster Tests
    // ============================================

    @Test
    @DisplayName("Broadcaster tracks listener count")
    void testBroadcasterListenerCount() {
        assertEquals(0, broadcaster.getListenerCount());
        assertFalse(broadcaster.hasListeners());

        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );
        broadcaster.addNotificationListener(info, observer, false);

        assertEquals(1, broadcaster.getListenerCount());
        assertTrue(broadcaster.hasListeners());

        // Add another observer
        DefaultNotificationObserver observer2 = new DefaultNotificationObserver();
        broadcaster.addNotificationListener(info, observer2, false);

        assertEquals(2, broadcaster.getListenerCount());

        broadcaster.removeNotificationListener(info, observer);
        assertEquals(1, broadcaster.getListenerCount());

        observer2.close();
    }

    @Test
    @DisplayName("Broadcaster removeAllListeners works")
    void testBroadcasterRemoveAllListeners() {
        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );

        DefaultNotificationObserver observer1 = new DefaultNotificationObserver();
        DefaultNotificationObserver observer2 = new DefaultNotificationObserver();

        broadcaster.addNotificationListener(info, observer1, false);
        broadcaster.addNotificationListener(info, observer2, false);

        assertEquals(2, broadcaster.getListenerCount());

        broadcaster.removeAllListeners();

        assertEquals(0, broadcaster.getListenerCount());
        assertFalse(broadcaster.hasListeners());

        observer1.close();
        observer2.close();
    }

    @Test
    @DisplayName("Broadcaster sequence number increments")
    void testBroadcasterSequenceNumber() {
        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );
        broadcaster.addNotificationListener(info, observer, false);

        assertEquals(0, broadcaster.getSequenceNumber());

        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Test1",
            1L
        );
        assertEquals(1, broadcaster.getSequenceNumber());

        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Test2",
            2L
        );
        assertEquals(2, broadcaster.getSequenceNumber());
    }

    @Test
    @DisplayName("Multiple observers all receive notifications")
    void testMultipleObserversReceiveNotifications() {
        DefaultNotificationObserver observer1 = new DefaultNotificationObserver();
        DefaultNotificationObserver observer2 = new DefaultNotificationObserver();
        DefaultNotificationObserver observer3 = new DefaultNotificationObserver();

        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );

        broadcaster.addNotificationListener(info, observer1, false);
        broadcaster.addNotificationListener(info, observer2, false);
        broadcaster.addNotificationListener(info, observer3, false);

        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Broadcast",
            42L
        );

        // All observers should receive
        assertEquals(1, observer1.getNotificationCount());
        assertEquals(1, observer2.getNotificationCount());
        assertEquals(1, observer3.getNotificationCount());

        observer1.close();
        observer2.close();
        observer3.close();
    }

    // ============================================
    // Concurrency Tests
    // ============================================

    @Test
    @DisplayName("Concurrent notification handling is thread-safe")
    void testConcurrentNotifications() throws InterruptedException {
        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );
        broadcaster.addNotificationListener(info, observer, false);

        int threadCount = 10;
        int notificationsPerThread = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < notificationsPerThread; j++) {
                        broadcaster.sendNotification(
                            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
                            "Thread " + threadId + " msg " + j,
                            (long) (threadId * 1000 + j)
                        );
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads
        for (Thread t : threads) {
            t.start();
        }
        startLatch.countDown();

        // Wait for completion
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));

        // Verify total count
        assertEquals(threadCount * notificationsPerThread, observer.getNotificationCount());

        // Cleanup
        for (Thread t : threads) {
            t.join();
        }
    }

    @Test
    @DisplayName("Exception in listener does not affect other listeners")
    void testExceptionInListenerDoesNotAffectOthers() {
        // Create a misbehaving observer that throws
        MBeanNotificationObserver badObserver = new MBeanNotificationObserver() {
            @Override
            public void notify(Notification notification) {
                throw new RuntimeException("Bad observer!");
            }

            @Override
            public void close() {
                // No-op
            }
        };

        DefaultNotificationObserver goodObserver = new DefaultNotificationObserver();

        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );

        broadcaster.addNotificationListener(info, badObserver, false);
        broadcaster.addNotificationListener(info, goodObserver, false);

        // This should not throw
        assertDoesNotThrow(() -> broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Test",
            1L
        ));

        // Good observer should still receive notification
        assertEquals(1, goodObserver.getNotificationCount());

        goodObserver.close();
    }

    // ============================================
    // NotificationListener Interface Tests
    // ============================================

    @Test
    @DisplayName("DefaultNotificationObserver implements NotificationListener")
    void testImplementsNotificationListener() {
        assertTrue(observer instanceof javax.management.NotificationListener);

        javax.management.NotificationListener listener = observer;

        Notification notification = new Notification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Test",
            1L,
            "Test message"
        );

        listener.handleNotification(notification, null);

        assertTrue(observer.hasNotifications());
        assertEquals(1, observer.getNotificationCount());
    }

    // ============================================
    // Metrics Integration Tests
    // ============================================

    @Test
    @DisplayName("Metrics recordQueryLatency emits notification")
    void testMetricsRecordQueryLatencyEmitsNotification() {
        DefaultNotificationObserver obs = new DefaultNotificationObserver();
        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );

        Metrics.getQueryLatencyMBean().addNotificationListener(info, obs, false);

        Metrics.recordQueryLatency(50L);

        assertTrue(obs.hasNotifications());
        Notification[] notifications = obs.getNotifications();
        assertEquals(1, notifications.length);
        assertEquals(MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY, notifications[0].getType());
        assertTrue(notifications[0].getMessage().contains("50"));

        obs.close();
    }

    @Test
    @DisplayName("Metrics setHealthScore emits notification")
    void testMetricsSetHealthScoreEmitsNotification() {
        DefaultNotificationObserver obs = new DefaultNotificationObserver();
        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.SYSTEM_HEALTH,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );

        Metrics.getSystemHealthMBean().addNotificationListener(info, obs, false);

        Metrics.setHealthScore(0.85);

        assertTrue(obs.hasNotifications());
        Notification[] notifications = obs.getNotifications();
        assertEquals(1, notifications.length);
        assertEquals(MBeanNotificationBroadcaster.NotificationTypes.SYSTEM_HEALTH, notifications[0].getType());
        assertTrue(notifications[0].getMessage().contains("0.85"));

        obs.close();
    }

    @Test
    @DisplayName("Metrics recordEpoch emits notification")
    void testMetricsRecordEpochEmitsNotification() {
        DefaultNotificationObserver obs = new DefaultNotificationObserver();
        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.VISIBILITY_EPOCH,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );

        Metrics.getVisibilityEpochMBean().addNotificationListener(info, obs, false);

        Metrics.recordEpoch(12345L);

        assertTrue(obs.hasNotifications());
        Notification[] notifications = obs.getNotifications();
        assertEquals(1, notifications.length);
        assertEquals(MBeanNotificationBroadcaster.NotificationTypes.VISIBILITY_EPOCH, notifications[0].getType());
        assertTrue(notifications[0].getMessage().contains("12345"));

        obs.close();
    }

    @Test
    @DisplayName("Metrics recordQueueDepth emits notification")
    void testMetricsRecordQueueDepthEmitsNotification() {
        DefaultNotificationObserver obs = new DefaultNotificationObserver();
        ObjectNotificationInfo info = new ObjectNotificationInfo(
            MBeanNotificationBroadcaster.NotificationTypes.INGESTION_QUEUE,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );

        Metrics.getIngestionQueueMBean().addNotificationListener(info, obs, false);

        Metrics.recordQueueDepth(500L);

        assertTrue(obs.hasNotifications());
        Notification[] notifications = obs.getNotifications();
        assertEquals(1, notifications.length);
        assertEquals(MBeanNotificationBroadcaster.NotificationTypes.INGESTION_QUEUE, notifications[0].getType());
        assertTrue(notifications[0].getMessage().contains("500"));

        obs.close();
    }

    // ============================================
    // getAttributes Tests
    // ============================================

    @Test
    @DisplayName("All MBeans return non-empty getAttributes set")
    void testAllMBeansReturnNonEmptyAttributesSet() {
        // Verify all 6 MBeans have getAttributes() that return non-empty sets
        assertTrue(Metrics.getQueryLatencyMBean().getAttributes().size() > 0,
            "QueryLatencyMBean should have attributes");
        assertTrue(Metrics.getSystemHealthMBean().getAttributes().size() > 0,
            "SystemHealthMBean should have attributes");
        assertTrue(Metrics.getIngestionQueueMBean().getAttributes().size() > 0,
            "IngestionQueueMBean should have attributes");
        assertTrue(Metrics.getVisibilityEpochMBean().getAttributes().size() > 0,
            "VisibilityEpochMBean should have attributes");
        assertTrue(Metrics.getCuckooFpRateMBean().getAttributes().size() > 0,
            "CuckooFpRateMBean should have attributes");
        assertTrue(Metrics.getHnswQueueDepthMBean().getAttributes().size() > 0,
            "HnswQueueDepthMBean should have attributes");

        // Verify expected attribute counts
        assertEquals(10, Metrics.getQueryLatencyMBean().getAttributes().size(),
            "QueryLatencyMBean should have 10 attributes");
        assertEquals(4, Metrics.getSystemHealthMBean().getAttributes().size(),
            "SystemHealthMBean should have 4 attributes");
        assertEquals(5, Metrics.getIngestionQueueMBean().getAttributes().size(),
            "IngestionQueueMBean should have 5 attributes");
        assertEquals(1, Metrics.getVisibilityEpochMBean().getAttributes().size(),
            "VisibilityEpochMBean should have 1 attribute");
        assertEquals(6, Metrics.getCuckooFpRateMBean().getAttributes().size(),
            "CuckooFpRateMBean should have 6 attributes");
        assertEquals(9, Metrics.getHnswQueueDepthMBean().getAttributes().size(),
            "HnswQueueDepthMBean should have 9 attributes");
    }

    @Test
    @DisplayName("MBean getAttributes contains expected attribute names")
    void testMBeanGetAttributesContainsExpectedNames() {
        // QueryLatencyMBean attributes
        java.util.Set<String> queryLatencyAttrs = Metrics.getQueryLatencyMBean().getAttributes();
        assertTrue(queryLatencyAttrs.contains("count"), "Should contain 'count'");
        assertTrue(queryLatencyAttrs.contains("p50"), "Should contain 'p50'");
        assertTrue(queryLatencyAttrs.contains("p90"), "Should contain 'p90'");
        assertTrue(queryLatencyAttrs.contains("p95"), "Should contain 'p95'");
        assertTrue(queryLatencyAttrs.contains("p99"), "Should contain 'p99'");
        assertTrue(queryLatencyAttrs.contains("p99.9"), "Should contain 'p99.9'");
        assertTrue(queryLatencyAttrs.contains("average"), "Should contain 'average'");
        assertTrue(queryLatencyAttrs.contains("minimum"), "Should contain 'minimum'");
        assertTrue(queryLatencyAttrs.contains("maximum"), "Should contain 'maximum'");

        // SystemHealthMBean attributes
        java.util.Set<String> healthAttrs = Metrics.getSystemHealthMBean().getAttributes();
        assertTrue(healthAttrs.contains("health"), "Should contain 'health'");
        assertTrue(healthAttrs.contains("isHealthy"), "Should contain 'isHealthy'");
        assertTrue(healthAttrs.contains("ready"), "Should contain 'ready'");
        assertTrue(healthAttrs.contains("coverage"), "Should contain 'coverage'");

        // IngestionQueueMBean attributes (this was missing before fix)
        java.util.Set<String> ingestionAttrs = Metrics.getIngestionQueueMBean().getAttributes();
        assertTrue(ingestionAttrs.contains("depth"), "Should contain 'depth'");
        assertTrue(ingestionAttrs.contains("percent"), "Should contain 'percent'");
        assertTrue(ingestionAttrs.contains("isUnderControl"), "Should contain 'isUnderControl'");
        assertTrue(ingestionAttrs.contains("isCritical"), "Should contain 'isCritical'");
        assertTrue(ingestionAttrs.contains("isFull"), "Should contain 'isFull'");

        // VisibilityEpochMBean attributes (this was missing before fix)
        java.util.Set<String> epochAttrs = Metrics.getVisibilityEpochMBean().getAttributes();
        assertTrue(epochAttrs.contains("epoch"), "Should contain 'epoch'");

        // CuckooFpRateMBean attributes (this was missing before fix)
        java.util.Set<String> cuckooAttrs = Metrics.getCuckooFpRateMBean().getAttributes();
        assertTrue(cuckooAttrs.contains("fpRate"), "Should contain 'fpRate'");
        assertTrue(cuckooAttrs.contains("fpRateEpoch"), "Should contain 'fpRateEpoch'");
        assertTrue(cuckooAttrs.contains("fpRateCount"), "Should contain 'fpRateCount'");
        assertTrue(cuckooAttrs.contains("avgFpRate"), "Should contain 'avgFpRate'");
        assertTrue(cuckooAttrs.contains("maxFpRate"), "Should contain 'maxFpRate'");
        assertTrue(cuckooAttrs.contains("currentEpoch"), "Should contain 'currentEpoch'");

        // HnswQueueDepthMBean attributes (this was missing before fix)
        java.util.Set<String> hnswAttrs = Metrics.getHnswQueueDepthMBean().getAttributes();
        assertTrue(hnswAttrs.contains("depth"), "Should contain 'depth'");
        assertTrue(hnswAttrs.contains("percent"), "Should contain 'percent'");
        assertTrue(hnswAttrs.contains("isUnderControl"), "Should contain 'isUnderControl'");
        assertTrue(hnswAttrs.contains("isCritical"), "Should contain 'isCritical'");
        assertTrue(hnswAttrs.contains("isFull"), "Should contain 'isFull'");
        assertTrue(hnswAttrs.contains("averageTailScan"), "Should contain 'averageTailScan'");
        assertTrue(hnswAttrs.contains("maxTailScan"), "Should contain 'maxTailScan'");
        assertTrue(hnswAttrs.contains("totalTailScanned"), "Should contain 'totalTailScanned'");
        assertTrue(hnswAttrs.contains("totalVectorEntriesFound"), "Should contain 'totalVectorEntriesFound'");
    }

    // ============================================
    // Standard JMX NotificationListener Tests
    // ============================================

    @Test
    @DisplayName("Standard JMX NotificationListener can be removed")
    void testStandardJmxListenerRemoval() throws Exception {
        javax.management.NotificationListener listener = (notification, handback) -> {};

        broadcaster.addNotificationListener(listener, null, null);
        assertEquals(1, broadcaster.getListenerCount());

        broadcaster.removeNotificationListener(listener);
        assertEquals(0, broadcaster.getListenerCount());
    }

    @Test
    @DisplayName("Standard JMX NotificationListener removal with filter and handback")
    void testStandardJmxListenerRemovalWithFilterAndHandback() throws Exception {
        javax.management.NotificationListener listener = (notification, handback) -> {};
        javax.management.NotificationFilter filter = notification -> true;
        Object handback = new Object();

        broadcaster.addNotificationListener(listener, filter, handback);
        assertEquals(1, broadcaster.getListenerCount());

        broadcaster.removeNotificationListener(listener, filter, handback);
        assertEquals(0, broadcaster.getListenerCount());
    }

    @Test
    @DisplayName("Standard JMX NotificationListener removal throws when not found")
    void testStandardJmxListenerRemovalThrowsWhenNotFound() {
        javax.management.NotificationListener listener = (notification, handback) -> {};

        assertThrows(javax.management.ListenerNotFoundException.class, () -> {
            broadcaster.removeNotificationListener(listener);
        });
    }

    @Test
    @DisplayName("Standard JMX NotificationListener receives notifications")
    void testStandardJmxListenerReceivesNotifications() throws Exception {
        AtomicInteger notificationCount = new AtomicInteger(0);
        javax.management.NotificationListener listener = (notification, handback) -> {
            notificationCount.incrementAndGet();
        };

        broadcaster.addNotificationListener(listener, null, null);

        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Test",
            1L
        );

        assertEquals(1, notificationCount.get());

        broadcaster.removeNotificationListener(listener);
    }

    @Test
    @DisplayName("Standard JMX NotificationListener filter works correctly")
    void testStandardJmxListenerFilter() throws Exception {
        AtomicInteger notificationCount = new AtomicInteger(0);
        javax.management.NotificationFilter filter = notification ->
            notification.getType().equals(MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY);
        Object handback = "test-handback";

        javax.management.NotificationListener listener = (notification, handbackObj) -> {
            notificationCount.incrementAndGet();
            assertEquals("test-handback", handbackObj);
        };

        broadcaster.addNotificationListener(listener, filter, handback);

        // This should pass the filter
        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Query latency",
            100L
        );

        // This should be filtered out
        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.SYSTEM_HEALTH,
            "System health",
            0.95
        );

        assertEquals(1, notificationCount.get());

        broadcaster.removeNotificationListener(listener, filter, handback);
    }

    @Test
    @DisplayName("Multiple standard JMX listeners can be added and removed individually")
    void testMultipleStandardJmxListeners() throws Exception {
        AtomicInteger count1 = new AtomicInteger(0);
        AtomicInteger count2 = new AtomicInteger(0);

        javax.management.NotificationListener listener1 = (n, h) -> count1.incrementAndGet();
        javax.management.NotificationListener listener2 = (n, h) -> count2.incrementAndGet();

        broadcaster.addNotificationListener(listener1, null, null);
        broadcaster.addNotificationListener(listener2, null, null);
        assertEquals(2, broadcaster.getListenerCount());

        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Test",
            1L
        );

        assertEquals(1, count1.get());
        assertEquals(1, count2.get());

        // Remove first listener
        broadcaster.removeNotificationListener(listener1);
        assertEquals(1, broadcaster.getListenerCount());

        broadcaster.sendNotification(
            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
            "Test2",
            2L
        );

        assertEquals(1, count1.get()); // Should not increment
        assertEquals(2, count2.get()); // Should increment

        broadcaster.removeNotificationListener(listener2);
        assertEquals(0, broadcaster.getListenerCount());
    }
}
