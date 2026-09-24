package com.eonmux.excalibur.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("QueryBudget")
class QueryBudgetTest {

    @Nested
    @DisplayName("an unbounded budget")
    class Unbounded {

        @Test
        @DisplayName("never expires and has no remainder to count down")
        void neverExpires() {
            QueryBudget budget = QueryBudget.unbounded();

            assertThat(budget.isBounded()).isFalse();
            assertThat(budget.isExpired()).isFalse();
            assertThat(budget.isExhausted()).isFalse();
            assertThat(budget.remainingNanos()).isEqualTo(Long.MAX_VALUE);
            assertThat(budget.toString()).contains("running");
        }

        @Test
        @DisplayName("runs a call on the calling thread")
        void runsInline() throws TimeoutException {
            Thread caller = Thread.currentThread();

            Thread ran = QueryBudget.unbounded().call(Thread::currentThread);

            assertThat(ran).isSameAs(caller);
        }

        @Test
        @DisplayName("sees a cancellation that arrived during an inline call once it returns")
        void cancellationDuringInlineCall() {
            QueryBudget budget = QueryBudget.unbounded();

            assertThatThrownBy(() -> budget.call(() -> budget.cancel("withdrawn")))
                .isInstanceOf(CancellationException.class)
                .hasMessage("withdrawn");
        }
    }

    @Nested
    @DisplayName("a bounded budget")
    class Bounded {

        @Test
        @DisplayName("refuses a timeout that is not positive")
        void refusesNonPositiveTimeout() {
            assertThatThrownBy(() -> QueryBudget.ofMillis(0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("expires at its deadline and reports nothing left")
        void expires() {
            QueryBudget budget = QueryBudget.until(System.nanoTime() - 1);

            assertThat(budget.isBounded()).isTrue();
            assertThat(budget.isExpired()).isTrue();
            assertThat(budget.isExhausted()).isTrue();
            assertThat(budget.remainingNanos()).isZero();
            assertThat(budget.toString()).contains("expired");
        }

        @Test
        @DisplayName("counts down from the timeout it was given")
        void countsDown() {
            QueryBudget budget = QueryBudget.ofMillis(60_000);

            assertThat(budget.isExpired()).isFalse();
            assertThat(budget.remainingNanos())
                .isPositive()
                .isLessThanOrEqualTo(TimeUnit.SECONDS.toNanos(60));
            assertThat(budget.toString()).contains("ms left");
        }

        @Test
        @DisplayName("returns what a call produced within the deadline")
        void returnsResult() throws TimeoutException {
            assertThat(QueryBudget.ofMillis(10_000).call(() -> 42)).isEqualTo(42);
        }

        @Test
        @DisplayName("gives up on a slow call at the deadline and interrupts it")
        void timesOutAndInterrupts() throws InterruptedException {
            QueryBudget budget = QueryBudget.ofMillis(100);
            CountDownLatch interrupted = new CountDownLatch(1);

            long start = System.nanoTime();
            assertThatThrownBy(() -> budget.call(() -> {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                }
                return null;
            })).isInstanceOf(TimeoutException.class);

            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(5_000);
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        @DisplayName("does not start a call once the deadline has passed")
        void refusesCallAfterDeadline() {
            AtomicBoolean ran = new AtomicBoolean();

            assertThatThrownBy(() -> QueryBudget.until(System.nanoTime() - 1)
                .call(() -> ran.getAndSet(true)))
                .isInstanceOf(TimeoutException.class);
            assertThat(ran).isFalse();
        }

        @Test
        @DisplayName("hands on a call's own unchecked failure unchanged")
        void propagatesFailure() {
            IllegalArgumentException failure = new IllegalArgumentException("bad");

            assertThatThrownBy(() -> QueryBudget.ofMillis(10_000).call(() -> {
                throw failure;
            })).isSameAs(failure);
        }

        @Test
        @DisplayName("hands on a call's error unchanged")
        void propagatesError() {
            AssertionError failure = new AssertionError("broken");

            assertThatThrownBy(() -> QueryBudget.ofMillis(10_000).call(() -> {
                throw failure;
            })).isSameAs(failure);
        }
    }

    @Nested
    @DisplayName("cancellation")
    class Cancellation {

        @Test
        @DisplayName("is set once, with its reason, and ends the query where it is checked")
        void setOnce() {
            QueryBudget budget = QueryBudget.ofMillis(60_000);

            assertThat(budget.cancel("withdrawn")).isTrue();
            assertThat(budget.cancel("again")).isFalse();

            assertThat(budget.isCancelled()).isTrue();
            assertThat(budget.isExhausted()).isTrue();
            assertThat(budget.toString()).contains("cancelled");
            assertThatThrownBy(budget::throwIfCancelled)
                .isInstanceOf(CancellationException.class)
                .hasMessage("withdrawn");
        }

        @Test
        @DisplayName("without a reason still says the query was cancelled")
        void withoutReason() {
            QueryBudget budget = QueryBudget.unbounded();
            budget.cancel(null);

            assertThatThrownBy(budget::throwIfCancelled)
                .isInstanceOf(CancellationException.class)
                .hasMessageContaining("cancelled");
        }

        @Test
        @DisplayName("refuses a call once the query is cancelled")
        void refusesCallAfterCancel() {
            QueryBudget budget = QueryBudget.ofMillis(60_000);
            budget.cancel("withdrawn");

            assertThatThrownBy(() -> budget.call(() -> 1))
                .isInstanceOf(CancellationException.class);
        }

        @Test
        @DisplayName("interrupts a call in flight at once rather than at the deadline")
        void interruptsCallInFlight() throws Exception {
            QueryBudget budget = QueryBudget.ofMillis(60_000);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            AtomicReference<Throwable> thrown = new AtomicReference<>();

            Thread waiter = Thread.ofVirtual().start(() -> {
                try {
                    budget.call(() -> {
                        started.countDown();
                        try {
                            Thread.sleep(60_000);
                        } catch (InterruptedException e) {
                            interrupted.countDown();
                        }
                        return null;
                    });
                } catch (Throwable t) {
                    thrown.set(t);
                }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            budget.cancel("withdrawn");
            waiter.join(5_000);

            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(5_000);
            assertThat(thrown.get()).isInstanceOf(CancellationException.class)
                .hasMessage("withdrawn");
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        @DisplayName("an interrupt of the waiting thread gives the call up and keeps the flag")
        void interruptOfWaiter() throws Exception {
            QueryBudget budget = QueryBudget.ofMillis(60_000);
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicBoolean flagKept = new AtomicBoolean();

            Thread waiter = Thread.ofVirtual().start(() -> {
                try {
                    budget.call(() -> {
                        started.countDown();
                        try {
                            Thread.sleep(60_000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                } catch (Throwable t) {
                    thrown.set(t);
                    flagKept.set(Thread.currentThread().isInterrupted());
                }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            waiter.interrupt();
            waiter.join(5_000);

            assertThat(thrown.get()).isInstanceOf(CancellationException.class);
            assertThat(flagKept).isTrue();
        }
    }

    @Test
    @DisplayName("records that a stage stopped short on the deadline")
    void marksTimedOut() {
        QueryBudget budget = QueryBudget.ofMillis(60_000);
        assertThat(budget.timedOut()).isFalse();

        budget.markTimedOut();

        assertThat(budget.timedOut()).isTrue();
    }
}
