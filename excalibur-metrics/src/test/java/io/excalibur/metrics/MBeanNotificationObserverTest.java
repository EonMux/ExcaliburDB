package io.excalibur.metrics;

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
}
