package com.eonmux.excalibur.store;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ingests document chunks into a default {@link StorageEngine} the way the ingest pipeline does,
 * in a JVM of its own, and reports what the routing directory cost.
 *
 * <p>Run as a separate process by {@link SplitHeapBoundBenchmarkTest} so that the heap it gets
 * is the one under test rather than the build's. Each chunk is what
 * {@code DocumentIngestor.writeChunks} stores for a chunk with an embedding: about 2 KB of text
 * followed by a 1536-dimensional float vector, keyed by a chunk id, written through
 * {@link StorageEngine#putReportingSegment(byte[], byte[])} from several writer threads at once.
 * Segments are the engine's default geometry, so every split the workload needs happens.</p>
 *
 * <p>Output is line-oriented so the parent can read it: {@code PROGRESS} lines once a second
 * from a monitor thread - the last one printed before a failure is the state at that moment -
 * then either {@code RESULT} or {@code FAILURE}. Exit status 0 means every chunk was written
 * and read back intact.</p>
 */
public final class ChunkIngestDriver {

    /** Text bytes per chunk, the ingest pipeline's typical chunk. */
    static final int TEXT_BYTES = 2048;

    /** Embedding dimensions, as a common embedding model produces. */
    static final int DIMENSIONS = 1536;

    private ChunkIngestDriver() {
    }

    /**
     * Runs the ingest.
     *
     * @param args data directory, chunk count, writer thread count
     * @throws Exception if the data directory cannot be prepared
     */
    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args[0]);
        int chunks = Integer.parseInt(args[1]);
        int threads = Integer.parseInt(args[2]);
        Files.createDirectories(dataDir);

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        AtomicLong written = new AtomicLong();
        AtomicLong peakHeap = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        StorageEngine engine = new StorageEngine(dataDir);
        engine.start();

        Thread monitor = Thread.ofPlatform().daemon().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                long used = memory.getHeapMemoryUsage().getUsed();
                peakHeap.accumulateAndGet(used, Math::max);
                System.out.println("PROGRESS " + stats(engine, written.get(), used));
                System.out.flush();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

        long start = System.nanoTime();
        List<Thread> writers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int first = t;
            writers.add(Thread.ofPlatform().start(() -> {
                try {
                    for (int i = first; i < chunks && failure.get() == null; i += threads) {
                        engine.putReportingSegment(key(i), value(i));
                        written.incrementAndGet();
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }));
        }
        for (Thread writer : writers) {
            writer.join();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        Throwable failed = failure.get();
        if (failed == null) {
            failed = verify(engine, chunks);
        }
        monitor.interrupt();
        long used = memory.getHeapMemoryUsage().getUsed();
        peakHeap.accumulateAndGet(used, Math::max);
        String summary = stats(engine, written.get(), used) + " peakHeapMb=" + peakHeap.get() / (1 << 20)
            + " maxHeapMb=" + Runtime.getRuntime().maxMemory() / (1 << 20) + " elapsedMs=" + elapsedMs;
        if (failed != null) {
            System.out.println("FAILURE " + summary + " cause=" + failed);
            failed.printStackTrace(System.out);
            System.out.flush();
            Runtime.getRuntime().halt(1);
        }
        engine.close();
        System.out.println("RESULT " + summary);
        System.out.flush();
    }

    /**
     * Reads every chunk back and compares it with what was written.
     *
     * @param engine The engine
     * @param chunks How many chunks were written
     * @return Null when all are intact, otherwise what was wrong
     */
    private static Throwable verify(StorageEngine engine, int chunks) {
        for (int i = 0; i < chunks; i++) {
            MemorySegment slice = engine.get(key(i));
            if (slice == null) {
                return new AssertionError("chunk " + i + " is not readable");
            }
            if (!Arrays.equals(slice.toArray(ValueLayout.JAVA_BYTE), value(i))) {
                return new AssertionError("chunk " + i + " reads back different bytes");
            }
        }
        return null;
    }

    /**
     * Describes the routing directory next to the data it routes.
     *
     * @param engine  The engine
     * @param written Chunks written so far
     * @param heap    Heap in use, in bytes
     * @return One line of key=value pairs
     */
    static String stats(StorageEngine engine, long written, long heap) {
        int width = engine.routing.width();
        return "written=" + written
            + " payloadMb=" + written * chunkBytes() / (1 << 20)
            + " directorySlots=" + width
            + " globalDepth=" + Integer.numberOfTrailingZeros(Math.max(width, 1))
            + " segments=" + engine.canonicalIndex.size()
            + " splits=" + engine.getSplitWorker().getSplitCount()
            + " heapMb=" + heap / (1 << 20);
    }

    /**
     * Returns the size of one chunk's stored value.
     *
     * @return Bytes per chunk
     */
    static long chunkBytes() {
        return TEXT_BYTES + Integer.BYTES + (long) DIMENSIONS * Float.BYTES;
    }

    /**
     * Returns the chunk id a document ingest would write for chunk {@code i}.
     *
     * @param i The chunk number
     * @return The key bytes
     */
    static byte[] key(int i) {
        return ("bench-source:document-" + (i / 16) + "#" + (i % 16)).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns chunk {@code i}'s value: text, then the embedding, deterministic per chunk.
     *
     * @param i The chunk number
     * @return The value bytes
     */
    static byte[] value(int i) {
        SplittableRandom random = new SplittableRandom(i);
        ByteBuffer buffer = ByteBuffer.allocate((int) chunkBytes()).order(ByteOrder.LITTLE_ENDIAN);
        for (int b = 0; b < TEXT_BYTES; b++) {
            buffer.put((byte) ('a' + random.nextInt(26)));
        }
        buffer.putInt(DIMENSIONS);
        for (int d = 0; d < DIMENSIONS; d++) {
            buffer.putFloat((float) random.nextDouble(-1.0, 1.0));
        }
        return buffer.array();
    }
}
