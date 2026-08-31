package io.excalibur.graph.store;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Access-pattern hints for mapped columns, via a {@code madvise(2)} downcall.
 *
 * <p>The kernel's default readahead assumes sequential access. That is right for a CSR neighbour
 * run, which is contiguous, and wrong for the offset array, where a traversal jumps to one entity's
 * slot and then to an unrelated one — there, readahead pulls in pages around each hit that will
 * never be touched, spending bandwidth and evicting pages that would have been. Telling the kernel
 * which pattern each column has is worth more than any micro-optimisation inside the loop.
 *
 * <p>Every method degrades to a no-op returning {@code false} when the platform has no
 * {@code madvise} — Windows, or a JDK without native access. A hint is an optimisation, so failing
 * to apply one is never an error; it is logged once at debug level and never again.
 */
public final class MemoryAdvice {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryAdvice.class);

    /** No special treatment; the kernel's default. */
    private static final int MADV_NORMAL = 0;

    /** Expect references in random order, so readahead is wasted. */
    private static final int MADV_RANDOM = 1;

    /** Expect references in sequential order, so read ahead aggressively. */
    private static final int MADV_SEQUENTIAL = 2;

    /** Expect access in the near future; start paging in now. */
    private static final int MADV_WILLNEED = 3;

    /**
     * {@code int madvise(void *addr, size_t length, int advice)}, or null where unavailable.
     *
     * <p>These constants agree across Linux and macOS for the four values used here, which is why
     * one set of literals is correct on both.
     */
    private static final MethodHandle MADVISE = lookupMadvise();

    private static final long PAGE_SIZE = detectPageSize();

    private MemoryAdvice() {
    }

    private static MethodHandle lookupMadvise() {
        try {
            Linker linker = Linker.nativeLinker();
            return linker.defaultLookup()
                .find("madvise")
                .map(addr -> linker.downcallHandle(addr, FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,      // return
                    ValueLayout.ADDRESS,       // addr
                    ValueLayout.JAVA_LONG,     // length (size_t)
                    ValueLayout.JAVA_INT)))    // advice
                .orElse(null);
        } catch (RuntimeException | Error e) {
            // No native access, a restricted-access denial, or a platform without the symbol.
            // All of them mean the same thing here: hints are unavailable.
            LOGGER.debug("madvise unavailable; mapped columns will use default readahead", e);
            return null;
        }
    }

    private static long detectPageSize() {
        try {
            return Long.getLong("io.excalibur.graph.pageSize", 4096L);
        } catch (RuntimeException e) {
            return 4096L;
        }
    }

    /** Whether hints can actually be applied on this platform. */
    public static boolean isAvailable() {
        return MADVISE != null;
    }

    /**
     * Hints that {@code segment} is read in random order.
     *
     * <p>The right hint for an offset array and for adjacency read through a permutation, where
     * successive reads are unrelated and readahead is pure waste.
     */
    public static boolean adviseRandom(MemorySegment segment) {
        return advise(segment, MADV_RANDOM, "RANDOM");
    }

    /**
     * Hints that {@code segment} is read front to back.
     *
     * <p>The right hint while building a store, and for a full scan of a column.
     */
    public static boolean adviseSequential(MemorySegment segment) {
        return advise(segment, MADV_SEQUENTIAL, "SEQUENTIAL");
    }

    /** Hints that {@code segment} will be needed shortly, so paging it in now is worthwhile. */
    public static boolean adviseWillNeed(MemorySegment segment) {
        return advise(segment, MADV_WILLNEED, "WILLNEED");
    }

    /** Restores default kernel behaviour for {@code segment}. */
    public static boolean adviseNormal(MemorySegment segment) {
        return advise(segment, MADV_NORMAL, "NORMAL");
    }

    private static boolean advise(MemorySegment segment, int advice, String name) {
        if (MADVISE == null || segment == null || segment.byteSize() == 0) {
            return false;
        }
        // madvise requires a page-aligned address. A segment straight from FileChannel.map is
        // aligned, but a slice of one need not be, so round the start down and extend the length
        // by as much as that moved. Advice applies to whole pages either way.
        long address = segment.address();
        long misalignment = address % PAGE_SIZE;
        long start = address - misalignment;
        long length = segment.byteSize() + misalignment;

        try {
            int result = (int) MADVISE.invokeExact(
                MemorySegment.ofAddress(start), length, advice);
            if (result != 0) {
                LOGGER.debug("madvise({}) returned {} for {} bytes", name, result, length);
                return false;
            }
            return true;
        } catch (Throwable t) {
            LOGGER.debug("madvise({}) failed", name, t);
            return false;
        }
    }

    /**
     * Applies {@code advice} to a segment for the lifetime of {@code arena}.
     *
     * <p>Present so callers do not have to remember that a hint outlives nothing — it is a property
     * of the mapping, not of a call — and becomes meaningless once the arena closes and the mapping
     * is gone.
     */
    public static void adviseForArena(Arena arena, MemorySegment segment, boolean random) {
        if (arena == null || segment == null) {
            return;
        }
        if (random) {
            adviseRandom(segment);
        } else {
            adviseSequential(segment);
        }
    }
}
