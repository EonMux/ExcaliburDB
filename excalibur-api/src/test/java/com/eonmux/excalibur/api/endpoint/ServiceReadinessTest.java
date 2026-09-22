package com.eonmux.excalibur.api.endpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That an operator can take a node out of rotation without stopping it.
 *
 * <p>There was no way to. {@code stop(int)} is reachable only from the SIGTERM hook, so a
 * rolling restart or a manual compaction began with the load balancer still sending traffic
 * to a node that was about to stop answering. Draining is the half of a graceful shutdown
 * that has to happen before the shutdown.</p>
 */
@DisplayName("Service readiness flag")
class ServiceReadinessTest {

    @Test
    @DisplayName("serves by default, because a node that starts drained never joins the pool")
    void servesByDefault() {
        assertThat(new ServiceReadiness().isDraining()).isFalse();
    }

    @Test
    @DisplayName("remembers that it was drained, and why")
    void remembersTheReason() {
        ServiceReadiness readiness = new ServiceReadiness();
        assertThat(readiness.beginDrain("rolling restart")).isTrue();

        assertThat(readiness.isDraining()).isTrue();
        assertThat(readiness.reason()).isEqualTo("rolling restart");
        assertThat(readiness.drainingSinceMs()).isPositive();
    }

    @Test
    @DisplayName("is idempotent, so a retried request does not restart the clock")
    void isIdempotent() throws InterruptedException {
        ServiceReadiness readiness = new ServiceReadiness();
        readiness.beginDrain("first");
        long since = readiness.drainingSinceMs();
        Thread.sleep(5);

        assertThat(readiness.beginDrain("second")).isFalse();
        assertThat(readiness.drainingSinceMs())
            .as("a retry must not look like a fresh drain")
            .isEqualTo(since);
        assertThat(readiness.reason()).isEqualTo("first");
    }

    @Test
    @DisplayName("returns to service, and forgets the drain it was in")
    void returnsToService() {
        ServiceReadiness readiness = new ServiceReadiness();
        readiness.beginDrain("maintenance");

        assertThat(readiness.endDrain()).isTrue();
        assertThat(readiness.isDraining()).isFalse();
        assertThat(readiness.reason()).isNull();
        assertThat(readiness.drainingSinceMs()).isZero();
        assertThat(readiness.endDrain()).isFalse();
    }

    @Test
    @DisplayName("accepts a drain with no reason given")
    void acceptsNoReason() {
        ServiceReadiness readiness = new ServiceReadiness();
        readiness.beginDrain(null);
        assertThat(readiness.isDraining()).isTrue();
        assertThat(readiness.reason()).isNull();
    }
}
