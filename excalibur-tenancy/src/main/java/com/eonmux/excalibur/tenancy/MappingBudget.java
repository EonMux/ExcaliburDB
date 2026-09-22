package com.eonmux.excalibur.tenancy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
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
 *
 * <h2>The limits are the process's, so the rest of the process is counted too</h2>
 * <p>What this budget accounts for one by one is what a {@link NamespaceRegistry} opens and closes:
 * the graphs. The limits it works to are the whole process's, and the graphs are the smaller part
 * of it - the storage engine maps every segment, every segment has an HNSW graph, the chunk index
 * keeps its own files and the JVM maps its heap, code cache and class path. Comparing the graphs
 * alone against the limit let a node reach {@code vm.max_map_count} while this budget reported room
 * to spare, and the failure then lands on whatever maps next, which is as likely to be a storage
 * segment as a graph.</p>
 *
 * <p>So before a namespace is opened the budget asks the process what it holds - see
 * {@link ProcessResourceUsage} - and records everything that is not one of its graphs as
 * {@link #otherMappings()} and {@link #otherDescriptors()}. Those are treated as already spent: the
 * high-water mark is compared against the graphs plus the rest of the process, so the room a new
 * namespace is given is the room the process actually has. The rest is re-measured on every open
 * rather than taken once at startup, because it moves - segments split and compact, and the
 * lexical index merges - and a baseline from boot would be wrong in whichever direction the node had
 * grown since.</p>
 *
 * <p>Where the process cannot be asked, the rest is whatever it was last measured as, or nothing if
 * it never was, and the headroom the high-water fraction leaves below the limit is what absorbs it.
 * That is the arrangement this budget had before it could measure, and it is the fallback rather than
 * the design.</p>
 */
public final class MappingBudget {

    private static final Logger LOGGER = LoggerFactory.getLogger(MappingBudget.class);

    private static final Path MAX_MAP_COUNT = Path.of("/proc/sys/vm/max_map_count");

    private static final Path PROCESS_LIMITS = Path.of("/proc/self/limits");

    /** The line of {@code /proc/self/limits} carrying RLIMIT_NOFILE. */
    private static final String MAX_OPEN_FILES = "Max open files";

    /** What the soft limit reads when the process may open as many files as it likes. */
    private static final String UNLIMITED = "unlimited";

    /** Descriptor limit meaning "none published, so none enforced". */
    public static final long NO_DESCRIPTOR_LIMIT = 0L;

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
    private final long maxDescriptors;
    private final double highWaterFraction;
    private final boolean limitWasRead;
    private final ProcessResourceUsage process;
    private final AtomicLong mappings = new AtomicLong();
    private final AtomicLong descriptors = new AtomicLong();

    /** Mappings the process holds that are not graphs this budget accounts for. */
    private final AtomicLong otherMappings = new AtomicLong();

    /** Descriptors the process holds that are not graphs this budget accounts for. */
    private final AtomicLong otherDescriptors = new AtomicLong();

    private MappingBudget(long maxMappings, long maxDescriptors, double highWaterFraction,
                          boolean limitWasRead, ProcessResourceUsage process) {
        this.maxMappings = maxMappings;
        this.maxDescriptors = maxDescriptors;
        this.highWaterFraction = highWaterFraction;
        this.limitWasRead = limitWasRead;
        this.process = process;
    }

    /** A budget reading the platform's real limits where it can. */
    public static MappingBudget detect() {
        return detect(DEFAULT_HIGH_WATER_FRACTION);
    }

    /** A budget at a chosen high-water fraction. */
    public static MappingBudget detect(double highWaterFraction) {
        MappingBudget budget = detectLimits(highWaterFraction, ProcessResourceUsage.ofThisProcess());
        if (!budget.refreshProcessUsage()) {
            LOGGER.info("the process's own mappings and descriptors cannot be read here, so the "
                + "budget counts its graphs alone and relies on the {}% headroom below each "
                + "limit for everything else the node holds",
                Math.round((1 - highWaterFraction) * 100));
        } else {
            LOGGER.info("the rest of the process holds {} mappings and {} descriptors; they are "
                + "counted against the same limits, and re-measured whenever a namespace opens",
                budget.otherMappings(), budget.otherDescriptors());
        }
        return budget;
    }

    /**
     * Reads the platform's limits and pairs them with a way of measuring the process.
     *
     * @param highWaterFraction Fraction of a limit at which eviction starts
     * @param process           Measures what the whole process holds
     * @return The budget, not yet measured
     */
    private static MappingBudget detectLimits(double highWaterFraction,
                                              ProcessResourceUsage process) {
        checkFraction(highWaterFraction);
        long descriptorLimit = detectDescriptorLimit();
        try (InputStream in = Files.newInputStream(MAX_MAP_COUNT)) {
            long limit = readLimit(in);
            if (limit > 0) {
                LOGGER.info("vm.max_map_count is {}; evicting namespaces above {} mappings"
                        + " or {} descriptors",
                    limit, (long) (limit * highWaterFraction),
                    (long) (descriptorLimit * highWaterFraction));
                return new MappingBudget(limit, descriptorLimit, highWaterFraction, true, process);
            }
        } catch (IOException | RuntimeException e) {
            // NumberFormatException lands here too: a limit file with unexpected content is the
            // same situation as one that cannot be read.
            LOGGER.debug("could not read {}", MAX_MAP_COUNT, e);
        }
        LOGGER.info("vm.max_map_count unavailable; assuming {} mappings", DEFAULT_MAX_MAPPINGS);
        return new MappingBudget(DEFAULT_MAX_MAPPINGS, descriptorLimit, highWaterFraction, false,
            process);
    }

    /**
     * Reads RLIMIT_NOFILE, or returns {@link #NO_DESCRIPTOR_LIMIT} where it cannot.
     *
     * <p>No fallback guess, unlike the mapping limit. Guessing low there costs an early eviction;
     * guessing low here would evict namespaces a deployment has the descriptors for, on a machine
     * that never published a limit to disagree with.
     */
    private static long detectDescriptorLimit() {
        try {
            long limit = readDescriptorLimit(Files.readString(PROCESS_LIMITS));
            if (limit > 0) {
                return limit;
            }
            LOGGER.info("no enforceable open-file limit in {}; descriptors are counted but not "
                + "bound", PROCESS_LIMITS);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("could not read {}", PROCESS_LIMITS, e);
        }
        return NO_DESCRIPTOR_LIMIT;
    }

    /**
     * Pulls the soft open-file limit out of the contents of {@code /proc/self/limits}.
     *
     * <p>The soft limit, which is the one the kernel enforces now; the hard limit is only what
     * the process could raise itself to and evicting against it would be evicting against a
     * number nothing is checking.
     *
     * @param limits The whole file
     * @return The soft limit, or {@link #NO_DESCRIPTOR_LIMIT} when it is unlimited or absent
     */
    static long readDescriptorLimit(String limits) {
        for (String line : limits.split("\n")) {
            if (!line.startsWith(MAX_OPEN_FILES)) {
                continue;
            }
            String[] fields = line.substring(MAX_OPEN_FILES.length()).trim().split("\\s+");
            if (fields.length == 0 || UNLIMITED.equals(fields[0])) {
                return NO_DESCRIPTOR_LIMIT;
            }
            try {
                return Long.parseLong(fields[0]);
            } catch (NumberFormatException e) {
                return NO_DESCRIPTOR_LIMIT;
            }
        }
        return NO_DESCRIPTOR_LIMIT;
    }

    /**
     * Reads a limit file to its end.
     *
     * <p>To the end of the stream rather than to the end of the first read. {@code /proc} reports
     * no size for its files, and a short read there returns a leading digit of the real number -
     * which parses cleanly and is wrong by orders of magnitude. A limit of 1 read out of a file
     * holding 16777216 puts the high-water mark at zero, so the first namespace to open is
     * already over budget and every open afterwards reports an eviction it cannot perform.</p>
     *
     * @param in The open limit file
     * @return The limit it holds
     * @throws IOException           if the file cannot be read
     * @throws NumberFormatException if it holds something other than a number
     */
    static long readLimit(InputStream in) throws IOException {
        return Long.parseLong(
            new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim());
    }

    /**
     * A budget with an explicit mapping limit and no descriptor bound.
     *
     * @param maxMappings       The mapping limit
     * @param highWaterFraction Fraction of a limit at which eviction starts
     * @return The budget
     */
    public static MappingBudget of(long maxMappings, double highWaterFraction) {
        return of(maxMappings, highWaterFraction, NO_DESCRIPTOR_LIMIT);
    }

    /**
     * A budget with both limits named, for tests and for deployments that know better.
     *
     * @param maxMappings       The mapping limit
     * @param highWaterFraction Fraction of a limit at which eviction starts
     * @param maxDescriptors    The descriptor limit, or {@link #NO_DESCRIPTOR_LIMIT} for none
     * @return The budget
     */
    public static MappingBudget of(long maxMappings, double highWaterFraction,
                                   long maxDescriptors) {
        return of(maxMappings, highWaterFraction, maxDescriptors,
            ProcessResourceUsage.unmeasured());
    }

    /**
     * A budget with both limits named and the rest of the process measured by {@code process}.
     *
     * <p>Nothing is measured until {@link #refreshProcessUsage()} is first called, which a
     * {@link NamespaceRegistry} does before every open.</p>
     *
     * @param maxMappings       The mapping limit
     * @param highWaterFraction Fraction of a limit at which eviction starts
     * @param maxDescriptors    The descriptor limit, or {@link #NO_DESCRIPTOR_LIMIT} for none
     * @param process           Measures what the whole process holds
     * @return The budget
     */
    public static MappingBudget of(long maxMappings, double highWaterFraction,
                                   long maxDescriptors, ProcessResourceUsage process) {
        java.util.Objects.requireNonNull(process, "process must not be null");
        if (maxMappings <= 0) {
            throw new IllegalArgumentException("maxMappings must be positive, got " + maxMappings);
        }
        if (maxDescriptors < 0) {
            throw new IllegalArgumentException(
                "maxDescriptors must not be negative, got " + maxDescriptors);
        }
        checkFraction(highWaterFraction);
        return new MappingBudget(maxMappings, maxDescriptors, highWaterFraction, true, process);
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

    /** The descriptor limit this budget is working to, or 0 when none is enforced. */
    public long maxDescriptors() {
        return maxDescriptors;
    }

    /** Mapping count above which namespaces should be evicted. */
    public long highWaterMark() {
        return (long) (maxMappings * highWaterFraction);
    }

    /** Descriptor count above which namespaces should be evicted, or 0 when none is enforced. */
    public long descriptorHighWaterMark() {
        return (long) (maxDescriptors * highWaterFraction);
    }

    /** Mappings currently accounted for: the graphs this budget has been told about. */
    public long mappings() {
        return mappings.get();
    }

    /** File descriptors currently accounted for: the graphs this budget has been told about. */
    public long descriptors() {
        return descriptors.get();
    }

    /**
     * Mappings the rest of the process held when it was last measured.
     *
     * @return The count, or 0 where the process has never been measured
     */
    public long otherMappings() {
        return otherMappings.get();
    }

    /**
     * Descriptors the rest of the process held when it was last measured.
     *
     * @return The count, or 0 where the process has never been measured
     */
    public long otherDescriptors() {
        return otherDescriptors.get();
    }

    /**
     * Measures the process and records what its graphs do not account for.
     *
     * <p>The rest of the process is what it holds less what this budget has been told about, and
     * never less than zero: an accounted graph whose Lucene share has shrunk since it was costed
     * would otherwise make the rest of the node look negative, and a negative figure is headroom
     * that does not exist. Measured and accounted are read a moment apart, so a namespace opening
     * between the two is counted twice until the next measurement - an over-count, which costs an
     * eviction a little early and never the limit.</p>
     *
     * <p>A figure the process cannot supply leaves the previous one in place rather than
     * resetting it: the rest of the node did not shrink because a read of {@code /proc} failed.</p>
     *
     * @return true if both figures were measured this time
     */
    public boolean refreshProcessUsage() {
        OptionalLong processMappings = process.mappings();
        OptionalLong processDescriptors = process.descriptors();
        processMappings.ifPresent(
            measured -> otherMappings.set(Math.max(0, measured - mappings.get())));
        processDescriptors.ifPresent(
            measured -> otherDescriptors.set(Math.max(0, measured - descriptors.get())));
        return processMappings.isPresent() && processDescriptors.isPresent();
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

    /**
     * Whether usage has passed the point where namespaces should be evicted.
     *
     * <p>Either half is enough. A namespace is thirteen mappings and two Lucene indexes' worth of
     * descriptors, and which limit a deployment meets first depends on its {@code max_map_count}
     * against its {@code RLIMIT_NOFILE} - so a budget that watched only mappings would let the
     * other run out, which fails as "Too many open files" on the next thing to open a file
     * rather than on the namespace that spent the descriptors.
     *
     * <p>Usage is the graphs plus the rest of the process as last measured, because the limits
     * are the process's.
     */
    public boolean isOverHighWater() {
        return wouldExceedHighWater(0, 0);
    }

    /**
     * Whether the rest of the process alone, without a single graph, is over the high-water mark.
     *
     * <p>The case eviction cannot fix. Closing every graph gives back only what the graphs hold,
     * so a node in this state evicts its whole working set on every open and is still over; what
     * it needs is a higher limit, or less of everything else.
     *
     * @return true when either half is over before any graph is counted
     */
    public boolean isRestOfProcessOverHighWater() {
        return otherMappings.get() > highWaterMark()
            || (maxDescriptors > NO_DESCRIPTOR_LIMIT
                && otherDescriptors.get() > descriptorHighWaterMark());
    }

    /** Whether the descriptor half is over, allowing for a load about to happen. */
    private boolean isOverDescriptorHighWater(long additionalDescriptors) {
        return maxDescriptors > NO_DESCRIPTOR_LIMIT
            && descriptors.get() + otherDescriptors.get() + additionalDescriptors
                > descriptorHighWaterMark();
    }

    /**
     * Whether {@code additionalMappings} more would pass the high-water mark.
     *
     * <p>Asked before opening a namespace, not after: the point is to evict first so the open
     * succeeds, rather than to discover afterwards that it should not have happened.
     *
     * @param additionalMappings    Mappings the load is expected to take
     * @param additionalDescriptors Descriptors the load is expected to take
     * @return true when either half of the budget would be passed
     */
    public boolean wouldExceedHighWater(long additionalMappings, long additionalDescriptors) {
        return mappings.get() + otherMappings.get() + additionalMappings > highWaterMark()
            || isOverDescriptorHighWater(additionalDescriptors);
    }

    /** Fraction of the mapping limit the process is using, graphs and the rest, for a gauge. */
    public double utilisation() {
        return (mappings.get() + otherMappings.get()) / (double) maxMappings;
    }

    /**
     * Everything this budget knows, read once, with the process measured now.
     *
     * <p>For reporting. The process figures are read afresh rather than taken from the last
     * refresh, because a report is read when something looks wrong and a figure from the last
     * namespace open may be hours old; they are not recorded, so reporting never moves an
     * eviction decision.
     *
     * @return The snapshot
     */
    public Usage usage() {
        return new Usage(mappings.get(), process.mappings(), highWaterMark(), maxMappings,
            descriptors.get(), process.descriptors(), descriptorHighWaterMark(), maxDescriptors,
            limitWasRead);
    }

    /**
     * What the budget holds and what the process holds, against the limits both are held to.
     *
     * @param graphMappings        Mappings the graphs are accounted at
     * @param processMappings      Mappings the whole process holds, or empty where unmeasurable
     * @param mappingHighWater     Where eviction starts
     * @param maxMappings          {@code vm.max_map_count}, or the assumed default
     * @param graphDescriptors     Descriptors the graphs are accounted at
     * @param processDescriptors   Descriptors the whole process holds, or empty
     * @param descriptorHighWater  Where eviction starts, or 0 when no limit is enforced
     * @param maxDescriptors       {@code RLIMIT_NOFILE}, or 0 when none is enforced
     * @param limitWasRead         Whether the mapping limit came from the platform
     */
    public record Usage(long graphMappings, OptionalLong processMappings, long mappingHighWater,
                        long maxMappings, long graphDescriptors, OptionalLong processDescriptors,
                        long descriptorHighWater, long maxDescriptors, boolean limitWasRead) {

        /**
         * Validates that nothing is missing.
         *
         * @throws NullPointerException if either process figure is null
         */
        public Usage {
            java.util.Objects.requireNonNull(processMappings, "processMappings must not be null");
            java.util.Objects.requireNonNull(processDescriptors,
                "processDescriptors must not be null");
        }
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
            "MappingBudget[mappings=%d/%d (%.1f%% with the rest of the process), "
                + "highWater=%d, fds=%d/%d, fdHighWater=%d, restOfProcess=%d mappings %d fds "
                + "(%s), limitRead=%b]",
            mappings.get(), maxMappings, utilisation() * 100, highWaterMark(),
            descriptors.get(), maxDescriptors, descriptorHighWaterMark(),
            otherMappings.get(), otherDescriptors.get(), process, limitWasRead);
    }
}
