package com.eonmux.excalibur.ingest.source;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;

/**
 * The {@code robots.txt} rules one crawl run has read, by origin, and what they kept out of it.
 *
 * <h2>One per run</h2>
 * <p>RFC 9309 scopes a {@code robots.txt} to one origin - scheme, host and port - and lets a
 * crawler cache it. Each origin's file is therefore requested once and every URL on that origin
 * is judged against the same answer, which also keeps a run consistent with itself: a page the
 * walk left out is not fetched later in the same run because the file changed in between. A new
 * run starts a new cache, so an edit to the file is seen at the next crawl.</p>
 *
 * <p>An entry is still dropped after {@link #MAX_AGE}, the longest RFC 9309 lets a crawler rely
 * on one. A crawl of a large site at a gentle pace can run for longer than that.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Callers asking about an origin whose file is being read wait for that one
 * request instead of issuing their own, and the wait parks rather than holding a lock, so a
 * virtual thread waiting on a slow host does not pin its carrier.</p>
 */
final class RobotsPolicy {

    /** How long one reading of a file is relied on. */
    static final Duration MAX_AGE = Duration.ofHours(24);

    /**
     * Reads an origin's rules.
     *
     * <p>Never fails over what the origin answered or whether it answered: that is part of the
     * rules, as {@link RobotsRules#unreachable(String)} or {@link RobotsRules#ALLOW_ALL}.</p>
     */
    @FunctionalInterface
    interface Loader {

        /**
         * @param origin {@code scheme://host:port}, as {@link #originOf(URI)} forms it
         * @return The rules that apply to this crawler on that origin
         * @throws InterruptedException if the run is stopped while the file is being read
         */
        RobotsRules load(String origin) throws InterruptedException;
    }

    private final Loader loader;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Entry> byOrigin = new ConcurrentHashMap<>();

    /** Distinct URLs the rules kept out of this run. */
    private final Set<String> disallowed = ConcurrentHashMap.newKeySet();

    /**
     * Starts a run's cache.
     *
     * @param loader Reads an origin's rules
     */
    RobotsPolicy(Loader loader) {
        this(loader, System::nanoTime);
    }

    /**
     * Starts a run's cache against a clock a test can move.
     *
     * @param loader    Reads an origin's rules
     * @param nanoClock Monotonic nanoseconds, as {@link System#nanoTime()}
     */
    RobotsPolicy(Loader loader, LongSupplier nanoClock) {
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
    }

    /**
     * The rules for an origin, read on first use.
     *
     * @param origin {@code scheme://host:port}
     * @return The rules
     * @throws InterruptedException if the run is stopped while waiting for them
     */
    RobotsRules rulesFor(String origin) throws InterruptedException {
        while (true) {
            Entry entry = byOrigin.get(origin);
            if (entry == null) {
                Entry mine = new Entry(new CompletableFuture<>(), nanoClock.getAsLong());
                entry = byOrigin.putIfAbsent(origin, mine);
                if (entry == null) {
                    return load(origin, mine);
                }
            }
            if (entry.isStale(nanoClock.getAsLong())) {
                byOrigin.remove(origin, entry);
                continue;
            }
            try {
                return entry.rules().get();
            } catch (ExecutionException e) {
                // The thread reading it was stopped before it had an answer, and took its entry
                // back out; this caller is still running, so it reads the file itself.
                byOrigin.remove(origin, entry);
            }
        }
    }

    private RobotsRules load(String origin, Entry mine) throws InterruptedException {
        try {
            RobotsRules rules = loader.load(origin);
            mine.rules().complete(rules);
            return rules;
        } catch (InterruptedException | RuntimeException e) {
            byOrigin.remove(origin, mine);
            mine.rules().completeExceptionally(e);
            throw e;
        }
    }

    /**
     * Records a URL the rules kept out of this run.
     *
     * @param url The URL that was not fetched, or not listed
     */
    void recordDisallowed(String url) {
        disallowed.add(url);
    }

    /**
     * How many distinct URLs the rules kept out of this run so far.
     *
     * @return The count
     */
    long disallowedCount() {
        return disallowed.size();
    }

    /**
     * The origin a URL belongs to, as RFC 9309 scopes a {@code robots.txt}.
     *
     * <p>The port is always written out, so {@code https://example.com} and
     * {@code https://example.com:443} are one origin and share one file.</p>
     *
     * @param uri An absolute http or https URL
     * @return {@code scheme://host:port}, scheme and host lower-cased
     */
    static String originOf(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    /**
     * What a rule is matched against: the raw path, {@code /} when there is none, and the raw
     * query after a {@code ?} when there is one.
     *
     * <p>Raw, so an escape reaches {@link RobotsRules#normalize(String)} as it was written and a
     * {@code %2F} is not mistaken for a separator.</p>
     *
     * @param uri The URL
     * @return Its path and query
     */
    static String pathAndQuery(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = uri.getRawQuery();
        return query == null ? path : path + "?" + query;
    }

    /**
     * One origin's rules, or the read of them in progress.
     *
     * @param rules      Completed with the rules once they are read
     * @param readAtNanos When the read began, from the policy's clock
     */
    private record Entry(CompletableFuture<RobotsRules> rules, long readAtNanos) {

        /** Whether a completed read is older than {@link #MAX_AGE}; a read in progress never is. */
        boolean isStale(long nowNanos) {
            return rules.isDone() && nowNanos - readAtNanos > MAX_AGE.toNanos();
        }
    }
}
