package io.excalibur.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That every response this node writes is counted, and that the two refusals an operator
 * pages on are counted separately from the rest of the 4xx.
 *
 * <p>Before these counters existed the node had no failure signal of any kind: a rate-limit
 * rejection, an authentication failure and an unhandled handler exception each wrote a log
 * line and vanished, so an error rate could not be computed from anything the process
 * published.</p>
 */
@DisplayName("HTTP response counters")
class ResponseCountersTest {

    @BeforeEach
    void reset() {
        ResponseCounters.reset();
    }

    @Test
    @DisplayName("counts a response against its status class")
    void countsByStatusClass() {
        ResponseCounters.record(200);
        ResponseCounters.record(204);
        ResponseCounters.record(304);
        ResponseCounters.record(404);
        ResponseCounters.record(500);

        assertThat(ResponseCounters.count(ResponseCounters.StatusClass.SUCCESS)).isEqualTo(2);
        assertThat(ResponseCounters.count(ResponseCounters.StatusClass.REDIRECTION)).isEqualTo(1);
        assertThat(ResponseCounters.count(ResponseCounters.StatusClass.CLIENT_ERROR)).isEqualTo(1);
        assertThat(ResponseCounters.count(ResponseCounters.StatusClass.SERVER_ERROR)).isEqualTo(1);
        assertThat(ResponseCounters.total()).isEqualTo(5);
    }

    @Test
    @DisplayName("breaks out the refusals that would otherwise disappear into 4xx")
    void breaksOutRefusals() {
        ResponseCounters.record(429);
        ResponseCounters.record(429);
        ResponseCounters.record(401);
        ResponseCounters.record(404);

        assertThat(ResponseCounters.rejections(ResponseCounters.Rejection.THROTTLED)).isEqualTo(2);
        assertThat(ResponseCounters.rejections(ResponseCounters.Rejection.UNAUTHENTICATED))
            .isEqualTo(1);
        // Still 4xx as well: the breakout is a second view of the same responses, not a
        // different bucket, so a sum over the classes stays the total.
        assertThat(ResponseCounters.count(ResponseCounters.StatusClass.CLIENT_ERROR)).isEqualTo(4);
    }

    @Test
    @DisplayName("counts an informational response rather than dropping it")
    void countsInformational() {
        ResponseCounters.record(100);
        assertThat(ResponseCounters.count(ResponseCounters.StatusClass.INFORMATIONAL)).isEqualTo(1);
        assertThat(ResponseCounters.total()).isEqualTo(1);
    }

    @Test
    @DisplayName("refuses a status code that is not an HTTP status code")
    void refusesNonsense() {
        assertThatThrownBy(() -> ResponseCounters.record(99))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResponseCounters.record(600))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(ResponseCounters.total()).isZero();
    }

    @Test
    @DisplayName("loses no response under concurrent writers")
    void losesNothingUnderConcurrency() throws InterruptedException {
        int writers = 8;
        int perWriter = 500;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            for (int i = 0; i < writers; i++) {
                pool.submit(() -> {
                    start.await();
                    for (int n = 0; n < perWriter; n++) {
                        ResponseCounters.record(200);
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(ResponseCounters.count(ResponseCounters.StatusClass.SUCCESS))
            .isEqualTo((long) writers * perWriter);
    }

    @Test
    @DisplayName("names each class the way a monitoring label does")
    void labelsAreStable() {
        assertThat(ResponseCounters.StatusClass.SUCCESS.label()).isEqualTo("2xx");
        assertThat(ResponseCounters.StatusClass.SERVER_ERROR.label()).isEqualTo("5xx");
        assertThat(ResponseCounters.Rejection.THROTTLED.label()).isEqualTo("throttled");
        assertThat(ResponseCounters.Rejection.UNAUTHENTICATED.label())
            .isEqualTo("unauthenticated");
    }
}
