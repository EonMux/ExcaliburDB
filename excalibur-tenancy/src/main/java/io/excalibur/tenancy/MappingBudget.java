package io.excalibur.tenancy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks how many memory mappings and file descriptors the process is using against the limits the
 * kernel will actually enforce.
 *
 * <p>A per-tenant index is a directory of mapped files, so mappings scale with tenants times files
 * per tenant. Linux caps that at {@code vm.max_map_count}, commonly 65530, and the failure when it
 * is reached is not graceful: {@code mmap} returns ENOMEM, which surfaces as an
 * {@link OutOfMemoryError} naming map failure while there is plenty of heap and plenty of RAM. That
 * is a genuinely confusing outage, and it arrives suddenly — everything is fine until the tenant
 * that crosses the line.
 *
 * <p>So the mapping count is a first-class metric, watched rather than discovered. Crossing the
 * high-water mark is what drives eviction, and eviction has to happen before the limit rather than
 * in response to it, because by then the allocation has already failed.
 *
 * <p>Reading the limits is best-effort. On a platform that does not publish them the budget falls
 * back to conservative defaults and says so, which is better than assuming they are unlimited.
 */
public final class MappingBudget {

    private static final Logger LOGGER = LoggerFactory.getLogger(MappingBudget.class);

    private static final Path MAX_MAP_COUNT = Path.of("/proc/sys/vm/max_map_count");

    /**
     * Assumed limit where the real one cannot be read.
     *
     * <p>The common Linux default. Guessing low is the safe direction: the cost of being wrong is
     * evicting a namespace sooner than strictly necessary, where the cost in the other direction is
     * the ENOMEM outage above.
     */
    public static final long DEFAULT_MAX_MAPPINGS = 65_530L;

    /**
     * Fraction of the limit at which eviction starts.
     *
     * <p>Well below the limit, because the process maps things this budget does not know about —
     * the JVM's own mappings, loaded classes, thread stacks, any library that maps a file — and
     * because a namespace being opened needs headroom for all of its files at once.
     */
    public static final double DEFAULT_HIGH_WATER_FRACTION = 0.7;

    private final long maxMappings;
    private final double highWaterFraction;
    private final boolean limitWasRead;
    private final AtomicLong mappings = new AtomicLong();
    private final AtomicLong descriptors = new AtomicLong();

    private MappingBudget(long maxMappings, double highWaterFraction, boolean limitWasRead) {
        this.maxMappings = maxMappings;
        this.highWaterFraction = highWaterFraction;
        this.limitWasRead = limitWasRead;
    }

    /** A budget reading the platform's real limits where it can. */
    public static MappingBudget detect() {
        return detect(DEFAULT_HIGH_WATER_FRACTION);
    }

    /** A budget at a chosen high-water fraction. */
    public static MappingBudget detect(double highWaterFraction) {
        checkFraction(highWaterFraction);
        try {
            if (Files.isReadable(MAX_MAP_COUNT)) {
                long limit = Long.parseLong(Files.readString(MAX_MAP_COUNT).trim());
                if (limit > 0) {
                    LOGGER.info("vm.max_map_count is {}; evicting namespaces above {} mappings",
                        limit, (long) (limit * highWaterFraction));
                    return new MappingBudget(limit, highWaterFraction, true);
                }
            }
        } catch (IOException | RuntimeException e) {
            // NumberFormatException lands here too: a limit file with unexpected content is the
            // same situation as one that cannot be read.
            LOGGER.debug("could not read {}", MAX_MAP_COUNT, e);
        }
        LOGGER.info("vm.max_map_count unavailable; assuming {} mappings", DEFAULT_MAX_MAPPINGS);
        return new MappingBudget(DEFAULT_MAX_MAPPINGS, highWaterFraction, false);
    }

    /** A budget with an explicit limit, for tests and for deployments that know better. */
    public static MappingBudget of(long maxMappings, double highWaterFraction) {
        if (maxMappings <= 0) {
            throw new IllegalArgumentException("maxMappings must be positive, got " + maxMappings);
        }
        checkFraction(highWaterFraction);
        return new MappingBudget(maxMappings, highWaterFraction, true);
    }

    private static void checkFraction(double fraction) {
        if (fraction <= 0 || fraction > 1 || Double.isNaN(fraction)) {
            throw new IllegalArgumentException(
                "highWaterFraction must be in (0, 1], got " + fraction);
        }
    }

    /** Whether the limit came from the platform rather than a fallback. */
    public boolean limitWasRead() {
        return limitWasRead;
    }

    /** The mapping limit this budget is working to. */
    public long maxMappings() {
        return maxMappings;
    }

    /** Mapping count above which namespaces should be evicted. */
    public long highWaterMark() {
        return (long) (maxMappings * highWaterFraction);
    }

    /** Mappings currently accounted for. */
    public long mappings() {
        return mappings.get();
    }

    /** File descriptors currently accounted for. */
    public long descriptors() {
        return descriptors.get();
    }

    /** Records mappings and descriptors taken by something that was just opened. */
    public void acquire(long mappingCount, long descriptorCount) {
        if (mappingCount < 0 || descriptorCount < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        mappings.addAndGet(mappingCount);
        descriptors.addAndGet(descriptorCount);
    }

    /** Records mappings and descriptors given back by something that was just closed. */
    public void release(long mappingCount, long descriptorCount) {
        if (mappingCount < 0 || descriptorCount < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        // Clamped at zero: an accounting slip must not make the budget believe it has negative
        // usage and therefore unlimited headroom, which would defeat the whole mechanism.
        mappings.updateAndGet(current -> Math.max(0, current - mappingCount));
        descriptors.updateAndGet(current -> Math.max(0, current - descriptorCount));
    }

    /** Whether usage has passed the point where namespaces should be evicted. */
    public boolean isOverHighWater() {
        return mappings.get() > highWaterMark();
    }

    /**
     * Whether {@code additionalMappings} more would pass the high-water mark.
     *
     * <p>Asked before opening a namespace, not after: the point is to evict first so the open
     * succeeds, rather than to discover afterwards that it should not have happened.
     */
    public boolean wouldExceedHighWater(long additionalMappings) {
        return mappings.get() + additionalMappings > highWaterMark();
    }

    /** Fraction of the limit currently in use, for a gauge. */
    public double utilisation() {
        return mappings.get() / (double) maxMappings;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
            "MappingBudget[mappings=%d/%d (%.1f%%), highWater=%d, fds=%d, limitRead=%b]",
            mappings.get(), maxMappings, utilisation() * 100, highWaterMark(),
            descriptors.get(), limitWasRead);
    }
}
