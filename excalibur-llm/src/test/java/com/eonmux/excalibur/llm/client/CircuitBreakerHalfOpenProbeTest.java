package com.eonmux.excalibur.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the breaker to admitting a bounded number of probes when it half-opens.
 *
 * <p>The whole point of opening is to stop sending traffic to something that cannot take it.
 * A breaker that admits every waiting caller the instant the cooldown expires delivers the
 * entire backlog to the provider in one burst - the stampede it exists to prevent, and worse
 * than no breaker at all, because the requests now arrive together rather than spread out. One
 * request is enough to learn whether the provider has recovered; the rest should keep failing
 * fast until that one has answered.</p>
 */
@Timeout(value = 30)
class CircuitBreakerHalfOpenProbeTest {

    private static final Duration SHORT_COOLDOWN = Duration.ofMillis(100);

    private CircuitBreaker trippedAndCooledDown() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(1, SHORT_COOLDOWN, "probe-test");
        cb.recordFailure();
        Thread.sleep(SHORT_COOLDOWN.toMillis() + 20);
        return cb;
    }

    @Test
    @DisplayName("only the first caller after cooldown is admitted as a probe")
    void onlyOneProbeIsAdmitted() throws InterruptedException {
        CircuitBreaker cb = trippedAndCooledDown();

        assertTrue(cb.allowRequest(), "The first caller after cooldown is the probe");
        assertEquals(CircuitBreakerState.HALF_OPEN, cb.getState());

        for (int i = 0; i < 10; i++) {
            assertFalse(cb.allowRequest(),
                "Caller " + (i + 2) + " must fail fast while the probe is outstanding");
        }
    }

    @Test
    @DisplayName("a backlog arriving together yields exactly one probe")
    void concurrentBacklogYieldsOneProbe() throws InterruptedException {
        CircuitBreaker cb = trippedAndCooledDown();

        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger admitted = new AtomicInteger();

        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        if (cb.allowRequest()) {
                            admitted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "Workers must finish");
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, admitted.get(),
            "Exactly one of " + threads + " simultaneous callers may reach a provider that has "
                + "only just stopped failing");
    }

    @Test
    @DisplayName("a probe that fails re-opens the circuit and blocks the rest")
    void failedProbeReopens() throws InterruptedException {
        CircuitBreaker cb = trippedAndCooledDown();

        assertTrue(cb.allowRequest());
        cb.recordFailure();

        assertEquals(CircuitBreakerState.OPEN, cb.getState());
        assertFalse(cb.allowRequest(), "A failed probe restarts the cooldown");
    }

    @Test
    @DisplayName("a probe that succeeds closes the circuit and admits everyone")
    void succeededProbeCloses() throws InterruptedException {
        CircuitBreaker cb = trippedAndCooledDown();

        assertTrue(cb.allowRequest());
        cb.recordSuccess();

        assertEquals(CircuitBreakerState.CLOSED, cb.getState());
        for (int i = 0; i < 10; i++) {
            assertTrue(cb.allowRequest(), "A closed circuit admits every caller");
        }
    }

    @Test
    @DisplayName("a probe that never reports does not wedge the circuit shut")
    void abandonedProbeDoesNotWedgeTheCircuit() throws InterruptedException {
        CircuitBreaker cb = trippedAndCooledDown();

        assertTrue(cb.allowRequest(), "The probe is admitted");
        assertFalse(cb.allowRequest(), "and holds the only permit");

        // The probe's caller dies without recording anything - killed, or stuck in a socket read
        // that outlives every timeout. Nothing will ever settle the state on its behalf, so the
        // permit has to come back on its own or the breaker never reopens for anyone.
        Thread.sleep(SHORT_COOLDOWN.toMillis() + 20);

        assertTrue(cb.allowRequest(),
            "After the probe window lapses a fresh probe must be admitted, or a single lost "
                + "caller takes the endpoint out permanently");
    }

    @Test
    @DisplayName("the probe allowance is configurable for providers that need more signal")
    void probeAllowanceIsConfigurable() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(1, SHORT_COOLDOWN, "multi-probe", 3);
        cb.recordFailure();
        Thread.sleep(SHORT_COOLDOWN.toMillis() + 20);

        assertEquals(3, cb.getHalfOpenProbeAllowance());
        assertTrue(cb.allowRequest(), "probe 1");
        assertTrue(cb.allowRequest(), "probe 2");
        assertTrue(cb.allowRequest(), "probe 3");
        assertFalse(cb.allowRequest(), "the fourth caller is past the allowance");
    }

    @Test
    @DisplayName("the default allowance is a single probe")
    void defaultAllowanceIsOne() {
        assertEquals(1, new CircuitBreaker().getHalfOpenProbeAllowance());
        assertEquals(1, CircuitBreaker.DEFAULT_HALF_OPEN_PROBES);
    }
}
