package excalibur.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the per-client token buckets.
 */
@DisplayName("ClientRateLimiter")
class ClientRateLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    @DisplayName("allows up to the burst then refuses with a retry hint")
    void allowsBurstThenRefuses() {
        ClientRateLimiter limiter = new ClientRateLimiter(1.0, 3.0, 1.0, 3.0, 10);
        long now = 0L;

        assertThat(limiter.check("a", false, now).allowed()).isTrue();
        assertThat(limiter.check("a", false, now).allowed()).isTrue();
        assertThat(limiter.check("a", false, now).allowed()).isTrue();

        ClientRateLimiter.Decision refused = limiter.check("a", false, now);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("refills over time")
    void refills() {
        ClientRateLimiter limiter = new ClientRateLimiter(2.0, 2.0, 1.0, 1.0, 10);
        long now = 0L;

        limiter.check("a", false, now);
        limiter.check("a", false, now);
        assertThat(limiter.check("a", false, now).allowed()).isFalse();

        assertThat(limiter.check("a", false, now + SECOND).allowed()).isTrue();
    }

    @Test
    @DisplayName("keeps clients independent")
    void clientsAreIndependent() {
        ClientRateLimiter limiter = new ClientRateLimiter(1.0, 1.0, 1.0, 1.0, 10);

        assertThat(limiter.check("a", false, 0L).allowed()).isTrue();
        assertThat(limiter.check("a", false, 0L).allowed()).isFalse();
        assertThat(limiter.check("b", false, 0L).allowed()).isTrue();
    }

    @Test
    @DisplayName("meters reads and mutations from separate budgets")
    void separateBudgets() {
        ClientRateLimiter limiter = new ClientRateLimiter(10.0, 10.0, 1.0, 1.0, 10);

        assertThat(limiter.check("a", true, 0L).allowed()).isTrue();
        assertThat(limiter.check("a", true, 0L).allowed()).isFalse();
        assertThat(limiter.check("a", false, 0L).allowed()).isTrue();
    }

    @Test
    @DisplayName("stays bounded when many distinct clients arrive")
    void boundsTrackedClients() {
        ClientRateLimiter limiter = new ClientRateLimiter(10.0, 10.0, 10.0, 10.0, 8);

        for (int i = 0; i < 500; i++) {
            limiter.check("client-" + i, false, i * 1_000_000L);
        }

        // Without a bound this map is a leak any caller can drive by spoofing addresses.
        assertThat(limiter.trackedClients()).isLessThanOrEqualTo(8);
    }

    @Test
    @DisplayName("evicts idle clients before live ones")
    void evictsIdleFirst() {
        // One token each and effectively no refill, so "has this client kept its bucket" is
        // observable: a client still tracked has spent its token and is refused, while one that
        // was forgotten is indistinguishable from a newcomer and is allowed.
        ClientRateLimiter limiter = new ClientRateLimiter(1e-6, 1.0, 1e-6, 1.0, 2);
        long now = SECOND;

        assertThat(limiter.check("idle", false, now).allowed()).isTrue();
        long later = now + ClientRateLimiter.IDLE_EVICTION_NANOS + SECOND;
        assertThat(limiter.check("active", false, later).allowed()).isTrue();
        limiter.check("newcomer", false, later);

        assertThat(limiter.trackedClients()).isLessThanOrEqualTo(2);
        assertThat(limiter.check("active", false, later).allowed())
            .as("the client seen a moment ago must keep the bucket it has already spent")
            .isFalse();
        assertThat(limiter.check("idle", false, later).allowed())
            .as("the client idle past the threshold is the one that should have gone")
            .isTrue();
    }

    @Test
    @DisplayName("rejects a non-positive configuration")
    void rejectsBadConfiguration() {
        try {
            new ClientRateLimiter(0.0, 1.0, 1.0, 1.0, 1);
            assertThat(false).as("expected IllegalArgumentException").isTrue();
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("readRatePerSecond");
        }
    }
}
