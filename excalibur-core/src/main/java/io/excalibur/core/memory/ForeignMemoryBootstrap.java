package io.excalibur.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Initializes the {@code java.lang.foreign} classes on one thread, before any others use them.
 *
 * <h2>Why this exists</h2>
 * <p>Initializing the FFM classes from two threads at once can deadlock the JVM. The cycle runs
 * through class-initialization monitors inside {@code java.lang.foreign}: one thread holds
 * {@code MemorySegment}'s monitor and waits for {@code MemorySessionImpl}'s, while another holds
 * {@code MemorySessionImpl}'s and waits for {@code GlobalSession}'s. Neither can proceed.</p>
 *
 * <p>It is the JDK's own classes that deadlock, not this codebase's: two threads calling nothing
 * but {@code Arena.ofAuto()} and {@code MemorySegment.ofArray(...)} at the same instant reproduce
 * it, in 4 of 60 fresh JVMs on Java 21.0.12.</p>
 *
 * <p>Nothing recovers from it. A thread waiting on a class-initialization monitor cannot be
 * interrupted, so a timeout fires and changes nothing, and the process hangs rather than fails.
 * The only remedy is to never let the race happen, which is what this class is for: the first
 * touch of FFM happens here, on one thread, and every later thread finds the classes already
 * initialized.</p>
 *
 * <h2>Two threads is all it takes</h2>
 * <p>This is not a property of running tests in parallel. Storage, query, retrieval, graph and
 * shard components all start virtual threads that reach memory-mapped storage, so a single
 * sequential caller that hands work to a worker is already two threads racing for the first
 * touch. Sixty-three files under {@code src/main} use FFM directly and only five of them go
 * through {@link MemorySegmentFactory}, so no one entry point can carry this on their behalf.</p>
 *
 * <h2>Using it</h2>
 * <p>Call {@link #ensureInitialized()} once during single-threaded startup, before anything
 * spawns a thread that might reach FFM. {@code ExcaliburApplication.main} does this for the
 * server; <strong>an application embedding these libraries must do it itself</strong>, as the
 * first statement of its own startup. It is idempotent and costs nothing after the first call.
 * Calling it late is not an error but buys nothing: by then the race has either happened or it
 * has not.</p>
 *
 * <p><strong>Thread safety:</strong> safe from any thread and at any time. Concurrent first
 * calls are serialized by the JVM on this class's own initialization monitor.</p>
 */
public final class ForeignMemoryBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger(ForeignMemoryBootstrap.class);

    /** One page is enough to reach the mapped-segment implementation; nothing reads it. */
    private static final int PROBE_MAPPING_BYTES = 4096;

    static {
        // Calling ensureInitialized() triggers this, and a static method call is itself an
        // initialization trigger (JLS 12.4.1), so the method body can stay empty.
        warmUp();
    }

    private ForeignMemoryBootstrap() {
    }

    /**
     * Initializes the foreign-memory classes if they have not been initialized yet.
     *
     * <p>Returns once they are ready. Safe to call repeatedly and from any thread.</p>
     */
    public static void ensureInitialized() {
        // Intentionally empty. Reaching this method has already run the class initializer
        // above, which is the whole of the work.
    }

    /**
     * Touches every foreign-memory class this codebase later reaches from more than one thread.
     *
     * <p>The paths do not share an initialization path, so each is exercised rather than
     * assuming one covers the rest: a heap segment, the three arena flavours, a mapped segment
     * (the database's primary path), a {@code VarHandle} over a layout (how
     * {@code io.excalibur.core.atomic.AtomicOps} initializes its own static fields), and the
     * native linker (how {@code io.excalibur.graph.store.MemoryAdvice} initializes its
     * {@code madvise} handle). The last two are reached from static initializers elsewhere,
     * which is precisely where a first touch is least visible.</p>
     */
    private static void warmUp() {
        try {
            Arena.global();
            MemorySegment.ofArray(new byte[Long.BYTES]).get(ValueLayout.JAVA_LONG_UNALIGNED, 0);
            try (Arena confined = Arena.ofConfined()) {
                confined.allocate(Long.BYTES, Long.BYTES).set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 0L);
            }
            Arena.ofAuto().allocate(Long.BYTES, Long.BYTES);
            Arena.ofShared().close();
            ValueLayout.JAVA_INT.varHandle();
            Linker.nativeLinker().defaultLookup();
            warmUpMappedSegments();
        } catch (Throwable t) {
            // Every throwable, and this is the one place in this codebase where that is right.
            // The contract here is "the classes are initialized, or they never will be", and a
            // failure satisfies it either way: whatever broke, no thread can now be the second
            // one to initialize these classes. Letting it escape would be far worse - it would
            // leave this class permanently unloadable, and with it MemorySegmentFactory, turning
            // a JVM that merely forbids native access into one where storage cannot open at all.
            // Whether FFM is usable is MemorySegmentFactory's question; it asks it separately.
            LOGGER.debug("Foreign memory warm-up did not complete; "
                + "Panama availability is determined separately", t);
        }
    }

    /**
     * Maps one page of a throwaway file, the only way to reach the mapped-segment classes.
     *
     * <p>Memory-mapped segments are how this database reads every segment, index and filter, so
     * leaving their classes to be initialized by whichever two workers happen to open a file
     * first would leave the largest gap of all.</p>
     *
     * @throws java.io.IOException if the probe file cannot be created, mapped or removed
     */
    private static void warmUpMappedSegments() throws java.io.IOException {
        Path probe = Files.createTempFile("excalibur-ffm-warmup", ".tmp");
        try (Arena arena = Arena.ofConfined();
             FileChannel channel = FileChannel.open(probe, StandardOpenOption.READ,
                 StandardOpenOption.WRITE)) {
            channel.map(FileChannel.MapMode.READ_WRITE, 0, PROBE_MAPPING_BYTES, arena)
                .get(ValueLayout.JAVA_LONG_UNALIGNED, 0);
        } finally {
            Files.deleteIfExists(probe);
        }
    }
}
