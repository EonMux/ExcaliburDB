package com.eonmux.excalibur.api.security;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client token-bucket rate limiter with separate read and mutate budgets.
 *
 * <p>Each client - identified by its remote address - gets two buckets. Reads and
 * mutations are metered independently so that a burst of queries cannot exhaust the
 * (much smaller) budget that protects ingest and reindex.</p>
 *
 * <h2>Bounded state</h2>
 * <p>The limiter is itself an attack surface: an unbounded map keyed by remote
 * address is a memory leak that any client can drive. The number of tracked clients
 * is therefore capped. When the cap is reached, idle entries are evicted first and,
 * failing that, the least recently seen entry is dropped.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Bucket state is guarded by the bucket's own monitor
 * and the client map is a {@link ConcurrentHashMap}.</p>
 *
 * @author ExcaliburDB
 */
public final class ClientRateLimiter {

    /** How long an untouched client entry survives before it may be evicted. */
    static final long IDLE_EVICTION_NANOS = 10L * 60L * 1_000_000_000L;

    private final double readRatePerSecond;
    private final double readBurst;
    private final double mutateRatePerSecond;
    private final double mutateBurst;
    private final int maxClients;

    private final ConcurrentHashMap<String, ClientBuckets> clients = new ConcurrentHashMap<>();

    /**
     * Creates a limiter from a security policy.
     *
     * @param config The policy supplying both budgets and the client cap
     */
    public ClientRateLimiter(ApiSecurityConfig config) {
        this(config.readRatePerSecond(), config.readBurst(),
            config.mutateRatePerSecond(), config.mutateBurst(),
            config.rateLimitMaxClients());
    }

    /**
     * Creates a limiter with explicit budgets.
     *
     * @param readRatePerSecond   Read refill rate
     * @param readBurst           Read bucket capacity
     * @param mutateRatePerSecond Mutate refill rate
     * @param mutateBurst         Mutate bucket capacity
     * @param maxClients          Maximum tracked clients
     */
    public ClientRateLimiter(double readRatePerSecond, double readBurst,
                             double mutateRatePerSecond, double mutateBurst,
                             int maxClients) {
        this.readRatePerSecond = requirePositive(readRatePerSecond, "readRatePerSecond");
        this.readBurst = requirePositive(readBurst, "readBurst");
        this.mutateRatePerSecond = requirePositive(mutateRatePerSecond, "mutateRatePerSecond");
        this.mutateBurst = requirePositive(mutateBurst, "mutateBurst");
        if (maxClients <= 0) {
            throw new IllegalArgumentException("maxClients must be positive: " + maxClients);
        }
        this.maxClients = maxClients;
    }

    /**
     * Charges one request against the client's budget.
     *
     * @param clientKey Identifier for the client, typically its remote address
     * @param mutating  true for a mutating request, false for a read
     * @return The decision, including a Retry-After hint when the request is refused
     */
    public Decision check(String clientKey, boolean mutating) {
        return check(clientKey, mutating, System.nanoTime());
    }

    /**
     * Charges one request against the client's budget at an explicit instant.
     *
     * @param clientKey Identifier for the client
     * @param mutating  true for a mutating request
     * @param nowNanos  The instant to meter against
     * @return The decision
     */
    public Decision check(String clientKey, boolean mutating, long nowNanos) {
        String key = clientKey != null ? clientKey : "unknown";
        ClientBuckets buckets = clients.get(key);
        if (buckets == null) {
            evictIfFull(nowNanos);
            buckets = clients.computeIfAbsent(key, ignored -> new ClientBuckets(
                new TokenBucket(readRatePerSecond, readBurst, nowNanos),
                new TokenBucket(mutateRatePerSecond, mutateBurst, nowNanos),
                nowNanos));
        }
        buckets.lastSeenNanos = nowNanos;
        TokenBucket bucket = mutating ? buckets.mutate : buckets.read;
        return bucket.tryAcquire(nowNanos);
    }

    /**
     * Returns the number of clients currently tracked.
     *
     * @return Tracked client count
     */
    public int trackedClients() {
        return clients.size();
    }

    /**
     * Forgets all tracked clients.
     */
    public void reset() {
        clients.clear();
    }

    private void evictIfFull(long nowNanos) {
        if (clients.size() < maxClients) {
            return;
        }
        clients.entrySet().removeIf(entry ->
            nowNanos - entry.getValue().lastSeenNanos > IDLE_EVICTION_NANOS);
        while (clients.size() >= maxClients) {
            Map.Entry<String, ClientBuckets> oldest = clients.entrySet().stream()
                .min(Comparator.comparingLong(e -> e.getValue().lastSeenNanos))
                .orElse(null);
            if (oldest == null) {
                return;
            }
            clients.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private static double requirePositive(double value, String name) {
        if (!(value > 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be positive and finite: " + value);
        }
        return value;
    }

    /**
     * Outcome of a rate-limit check.
     *
     * @param allowed           true when the request may proceed
     * @param retryAfterSeconds Seconds the client should wait before retrying; at
     *                          least 1 when the request was refused, 0 otherwise
     */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0L);
    }

    private static final class ClientBuckets {
        private final TokenBucket read;
        private final TokenBucket mutate;

        /**
         * When this client was last charged.
         *
         * <p>Stamped at construction rather than left at zero and set by the caller afterwards.
         * {@code System.nanoTime()} is far past the idle threshold by the time a process serves
         * traffic, so an unstamped entry reads as ten minutes idle for the moment between being
         * published into the map and being stamped - and another thread's eviction sweep, which
         * runs precisely when the map is full, deletes it. The client it belonged to then gets a
         * fresh bucket on its next request, which is a hole in the limit exactly when the limit
         * is the thing holding.</p>
         */
        private volatile long lastSeenNanos;

        ClientBuckets(TokenBucket read, TokenBucket mutate, long nowNanos) {
            this.read = read;
            this.mutate = mutate;
            this.lastSeenNanos = nowNanos;
        }
    }

    /**
     * A single token bucket. Tokens refill continuously up to the bucket capacity.
     */
    private static final class TokenBucket {
        private static final double NANOS_PER_SECOND = 1_000_000_000.0;

        private final double ratePerSecond;
        private final double capacity;
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(double ratePerSecond, double capacity, long nowNanos) {
            this.ratePerSecond = ratePerSecond;
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefillNanos = nowNanos;
        }

        synchronized Decision tryAcquire(long nowNanos) {
            refill(nowNanos);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return Decision.ALLOWED;
            }
            double deficit = 1.0 - tokens;
            long retryAfterSeconds = (long) Math.ceil(deficit / ratePerSecond);
            return new Decision(false, Math.max(1L, retryAfterSeconds));
        }

        private void refill(long nowNanos) {
            long elapsed = nowNanos - lastRefillNanos;
            if (elapsed <= 0) {
                return;
            }
            lastRefillNanos = nowNanos;
            tokens = Math.min(capacity, tokens + (elapsed / NANOS_PER_SECOND) * ratePerSecond);
        }
    }
}
