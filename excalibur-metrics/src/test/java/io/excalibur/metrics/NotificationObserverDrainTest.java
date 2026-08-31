package io.excalibur.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.management.Notification;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that draining an observer cannot lose a notification.
 *
 * <p>Notifications are delivered inline on whichever thread recorded the metric, so a reader
 * calling {@code getNotifications()} always races the producers. A snapshot-then-clear drain
 * quietly discards everything that arrives between the two steps: the observer's own count
 * keeps rising while the notifications themselves are gone, and no caller can tell. Draining
 * element by element cannot: every element removed is an element returned.</p>
 *
 * @author ExcaliburDB
 */
class NotificationObserverDrainTest {

    @Test
    @DisplayName("Concurrent producers and a draining reader lose nothing")
    void concurrentDrainLosesNothing() throws Exception {
        int producers = 4;
        int perProducer = 20_000;
        int total = producers * perProducer;

        DefaultNotificationObserver observer = new DefaultNotificationObserver();
        ExecutorService executor = Executors.newFixedThreadPool(producers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers);

        for (int p = 0; p < producers; p++) {
            final int producer = p;
            executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perProducer; i++) {
                        observer.notify(new Notification(
                            MBeanNotificationBroadcaster.NotificationTypes.QUERY_LATENCY,
                            "test",
                            (long) producer * perProducer + i,
                            System.currentTimeMillis(),
                            "n"));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        Set<Long> drained = new HashSet<>();
        start.countDown();
        while (done.getCount() > 0) {
            collect(observer, drained);
        }
        assertTrue(done.await(30, TimeUnit.SECONDS), "producers should finish");
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "executor should terminate");

        // Final drain for anything offered after the last poll.
        collect(observer, drained);

        assertEquals(total, drained.size(),
            "every offered notification must be handed to a caller exactly once");
        assertEquals(total, observer.getNotificationCount(),
            "the reported count must match what the observer actually delivered");
        assertEquals(0, observer.peekNotifications().length,
            "the queue must be empty once fully drained");

        observer.close();
    }

    @Test
    @DisplayName("A drained notification is not handed out twice")
    void drainIsNotRepeated() {
        DefaultNotificationObserver observer = new DefaultNotificationObserver();
        observer.notify(new Notification("test", "src", 1L, "first"));
        observer.notify(new Notification("test", "src", 2L, "second"));

        assertEquals(2, observer.getNotifications().length, "first drain returns both");
        assertEquals(0, observer.getNotifications().length, "second drain returns nothing");
        assertEquals(2, observer.getNotificationCount(), "the count is cumulative, not drained");

        observer.close();
    }

    private static void collect(DefaultNotificationObserver observer, Set<Long> drained) {
        for (Notification notification : observer.getNotifications()) {
            assertTrue(drained.add(notification.getSequenceNumber()),
                "notification " + notification.getSequenceNumber() + " was handed out twice");
        }
    }
}
