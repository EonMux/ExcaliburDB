package com.eonmux.excalibur.api.server;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The one deadline a node's shutdown runs against, and how it is shared between the steps.
 *
 * <h2>Why one deadline</h2>
 * <p>A supervisor sends SIGKILL after its stop timeout, and the steps that make the stop worth
 * waiting for - the publish of the chunk index's buffer and the forcing of staged graph facts
 * and the document registry - run after every drain. Waits of their own, one per drain, would
 * add up to a worst case nobody configured, and a kill during the drains would lose exactly
 * those writes. With one deadline the worst case is a number, {@link #KEY_TIMEOUT}, that a stop
 * timeout can be set from; and because every drain waits what this leaves it rather than a
 * figure of its own, a longer budget buys a slow drain a longer wait instead of only ever
 * cutting a fixed one short.</p>
 *
 * <h2>The reserve</h2>
 * <p>{@link #DURABILITY_RESERVE} of the total is held back from the drains for the writes that
 * follow them. A drain that has not finished when the drains' share runs out is not waited for
 * further and is not interrupted: it is reported as still running, and what it uses is left open
 * rather than closed underneath it, which costs a replay on the next start. The writes then start
 * with the reserve still whole. Losing a drain's cleanliness is recoverable; losing the buffer is
 * not, because nothing afterwards indexes chunks storage holds and the index was never told
 * about.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable; the clock it reads is the only thing that moves.</p>
 *
 * @author ExcaliburDB
 */
public final class ShutdownBudget {

    /** Property naming how long a whole shutdown may take, in seconds. */
    public static final String KEY_TIMEOUT = "excalibur.api.shutdown-timeout-seconds";

    /**
     * The total when none is configured.
     *
     * <p>Ten seconds under the forty the deployment guides set as the stop timeout, which is the
     * margin for the JVM to reach its shutdown hook and for the storage engine's own close.</p>
     */
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** The smallest total accepted: the reserve, plus five seconds for everything else. */
    public static final int MIN_TIMEOUT_SECONDS = 15;

    /** The largest total accepted. Past an hour a stop is not a stop. */
    public static final int MAX_TIMEOUT_SECONDS = 3600;

    /**
     * The share held back for the durable writes that follow the drains: publishing the chunk
     * index's buffer, forcing staged graph facts and the document registry, and closing the
     * storage engine. Each is a flush and a force of files already in memory, which a healthy
     * disk finishes in well under a second; ten is room for one that is not healthy.
     */
    public static final Duration DURABILITY_RESERVE = Duration.ofSeconds(10);

    private final long deadlineNanos;
    private final long drainsEndNanos;
    private final LongSupplier clock;

    /**
     * Starts a budget now.
     *
     * @param total How long the whole shutdown may take
     * @param clock A nanosecond clock, {@link System#nanoTime()} outside tests
     * @throws IllegalArgumentException if the total is not larger than the reserve
     */
    ShutdownBudget(Duration total, LongSupplier clock) {
        Objects.requireNonNull(total, "total cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        if (total.compareTo(DURABILITY_RESERVE) <= 0) {
            throw new IllegalArgumentException("a shutdown budget of " + total.toSeconds()
                + "s leaves nothing beyond the " + DURABILITY_RESERVE.toSeconds()
                + "s held back for the durable writes");
        }
        long now = clock.getAsLong();
        this.deadlineNanos = now + total.toNanos();
        this.drainsEndNanos = deadlineNanos - DURABILITY_RESERVE.toNanos();
    }

    /**
     * Starts a budget of {@code total}, from now.
     *
     * @param total How long the whole shutdown may take
     * @return The budget
     * @throws IllegalArgumentException if the total is not larger than the reserve
     */
    public static ShutdownBudget startingNow(Duration total) {
        return new ShutdownBudget(total, System::nanoTime);
    }

    /**
     * Reads the configured total.
     *
     * <p>A value that is not a whole number of seconds in range stops startup rather than falling
     * back, for the reason {@link ListenPort} gives: a node that quietly used thirty seconds when
     * it was told ninety is one whose supervisor was set to match the ninety, and whose stop
     * therefore looks clean while cutting its drains short.</p>
     *
     * @param properties The effective properties, file merged with {@code EXCALIBUR_*}
     * @return The total
     * @throws IllegalArgumentException if the configured value is not usable
     */
    public static Duration configuredTimeout(Map<String, String> properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        String value = properties.get(KEY_TIMEOUT);
        if (value == null || value.isBlank()) {
            return Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS);
        }
        int seconds;
        try {
            seconds = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                KEY_TIMEOUT + " is not a whole number of seconds: '" + value + "'", e);
        }
        if (seconds < MIN_TIMEOUT_SECONDS || seconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException(KEY_TIMEOUT + " must be in range "
                + MIN_TIMEOUT_SECONDS + "-" + MAX_TIMEOUT_SECONDS + ": " + seconds);
        }
        return Duration.ofSeconds(seconds);
    }

    /**
     * How long the drains have left before the reserve begins.
     *
     * @return The time, zero once it has run out
     */
    public Duration forDrains() {
        return until(drainsEndNanos);
    }

    /**
     * How long the whole shutdown has left.
     *
     * @return The time, zero once it has run out
     */
    public Duration remaining() {
        return until(deadlineNanos);
    }

    /**
     * A step's own wait, cut to what the drains have left.
     *
     * @param wanted How long the step would wait on its own
     * @return The shorter of the two
     */
    public Duration bound(Duration wanted) {
        Objects.requireNonNull(wanted, "wanted cannot be null");
        Duration left = forDrains();
        return wanted.compareTo(left) <= 0 ? wanted : left;
    }

    /**
     * How long the listener may wait for in-flight exchanges, in the whole seconds it takes.
     *
     * <p>Half the drains' share rather than all of it. Requests the listener stops waiting for
     * are waited for again by the request drain at the end, so its grace is not their only
     * chance; the background work drained in between has no second chance, and a listener that
     * spent the whole share would leave every one of them reported as still running. Rounded
     * down, so the listener never waits past what it was given.</p>
     *
     * @return The grace in seconds, zero when under a second is left
     */
    public int listenerGraceSeconds() {
        long seconds = forDrains().toSeconds() / 2;
        return (int) Math.min(Integer.MAX_VALUE, seconds);
    }

    private Duration until(long endNanos) {
        long left = endNanos - clock.getAsLong();

        return left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
    }
}
