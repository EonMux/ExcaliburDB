package excalibur.query.executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CancellationToken.
 */
class CancellationTokenTest {

    @Test
    @DisplayName("CancellationToken should start in non-cancelled state")
    void testInitialState() {
        CancellationToken token = new CancellationToken();

        assertFalse(token.isCancelled());
        assertNull(token.cancellationReason());
    }

    @Test
    @DisplayName("CancellationToken should be cancellable")
    void testCancel() {
        CancellationToken token = new CancellationToken();

        assertTrue(token.cancel());
        assertTrue(token.isCancelled());
        assertFalse(token.cancel()); // Idempotent
    }

    @Test
    @DisplayName("CancellationToken should store cancellation reason")
    void testCancelWithReason() {
        CancellationToken token = new CancellationToken();

        assertTrue(token.cancel("User requested"));
        assertTrue(token.isCancelled());
        assertEquals("User requested", token.cancellationReason());
    }

    @Test
    @DisplayName("CancellationToken.cancelled() should create pre-cancelled token")
    void testCancelledFactory() {
        CancellationToken token = CancellationToken.cancelled();

        assertTrue(token.isCancelled());
    }

    @Test
    @DisplayName("CancellationToken.cancelled(reason) should create pre-cancelled token with reason")
    void testCancelledFactoryWithReason() {
        CancellationToken token = CancellationToken.cancelled("Timeout");

        assertTrue(token.isCancelled());
        assertEquals("Timeout", token.cancellationReason());
    }

    @Test
    @DisplayName("checkCancelled() should throw when cancelled")
    void testCheckCancelled() {
        CancellationToken token = new CancellationToken();

        // Should not throw
        token.checkCancelled();

        token.cancel("Test cancellation");

        CancellationException ex = assertThrows(CancellationException.class, token::checkCancelled);
        assertTrue(ex.getMessage().contains("Test cancellation"));
    }

    @Test
    @DisplayName("checkCancelled(message) should include custom message")
    void testCheckCancelledWithMessage() {
        CancellationToken token = CancellationToken.cancelled("Original reason");

        CancellationException ex = assertThrows(CancellationException.class,
            () -> token.checkCancelled("Stage 2 check"));

        assertTrue(ex.getMessage().contains("Stage 2 check"));
        assertTrue(ex.getMessage().contains("Original reason"));
    }

    @Test
    @DisplayName("checkCancelled(message) without reason should use only message")
    void testCheckCancelledWithMessageNoReason() {
        CancellationToken token = CancellationToken.cancelled();

        CancellationException ex = assertThrows(CancellationException.class,
            () -> token.checkCancelled("Custom message"));

        assertEquals("Custom message", ex.getMessage());
    }

    @Test
    @DisplayName("addListener should be called on cancellation")
    void testAddListener() {
        CancellationToken token = new CancellationToken();
        AtomicBoolean called = new AtomicBoolean(false);

        token.addListener(() -> called.set(true));

        assertFalse(called.get());
        token.cancel();
        assertTrue(called.get());
    }

    @Test
    @DisplayName("addListener should run immediately if already cancelled")
    void testAddListenerAlreadyCancelled() {
        CancellationToken token = CancellationToken.cancelled();
        AtomicBoolean called = new AtomicBoolean(false);

        token.addListener(() -> called.set(true));

        assertTrue(called.get());
    }

    @Test
    @DisplayName("addListener should reject null")
    void testAddListenerNull() {
        CancellationToken token = new CancellationToken();

        assertThrows(NullPointerException.class, () -> token.addListener(null));
    }

    @Test
    @DisplayName("removeListener should remove registered listener")
    void testRemoveListener() {
        CancellationToken token = new CancellationToken();
        AtomicBoolean called = new AtomicBoolean(false);
        Runnable listener = () -> called.set(true);

        token.addListener(listener);
        assertTrue(token.removeListener(listener));

        token.cancel();
        assertFalse(called.get()); // Listener was removed
    }

    @Test
    @DisplayName("removeListener should return false for unregistered listener")
    void testRemoveListenerNotRegistered() {
        CancellationToken token = new CancellationToken();

        assertFalse(token.removeListener(() -> {}));
    }

    @Test
    @DisplayName("addReasonListener should provide cancellation reason")
    void testAddReasonListener() {
        CancellationToken token = new CancellationToken();
        AtomicReference<String> capturedReason = new AtomicReference<>();

        token.addReasonListener(capturedReason::set);

        assertNull(capturedReason.get());
        token.cancel("Test reason");
        assertEquals("Test reason", capturedReason.get());
    }

    @Test
    @DisplayName("Multiple listeners should all be notified")
    void testMultipleListeners() {
        CancellationToken token = new CancellationToken();
        int[] counter = {0};

        token.addListener(() -> counter[0]++);
        token.addListener(() -> counter[0]++);
        token.addListener(() -> counter[0]++);

        token.cancel();
        assertEquals(3, counter[0]);
    }

    @Test
    @DisplayName("Listener exceptions should not prevent other listeners")
    void testListenerException() {
        CancellationToken token = new CancellationToken();
        AtomicBoolean secondCalled = new AtomicBoolean(false);

        token.addListener(() -> { throw new RuntimeException("First listener fails"); });
        token.addListener(() -> secondCalled.set(true));

        token.cancel();
        assertTrue(secondCalled.get()); // Second listener should still be called
    }

    @Test
    @DisplayName("toString should show cancelled state")
    void testToString() {
        CancellationToken token = new CancellationToken();

        assertTrue(token.toString().contains("cancelled=false"));

        token.cancel("Test");
        assertTrue(token.toString().contains("cancelled=true"));
        assertTrue(token.toString().contains("Test"));
    }

    @Test
    @DisplayName("Concurrent cancellation should be thread-safe")
    void testConcurrentCancellation() throws InterruptedException {
        CancellationToken token = new CancellationToken();
        int[] successCount = {0};

        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                if (token.cancel()) {
                    synchronized (successCount) {
                        successCount[0]++;
                    }
                }
            });
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        assertTrue(token.isCancelled());
        assertEquals(1, successCount[0]); // Only one should succeed
    }
}
