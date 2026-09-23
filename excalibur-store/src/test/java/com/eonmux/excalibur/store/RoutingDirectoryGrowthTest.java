package com.eonmux.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The routing directory grows with the deepest segment, not with the number of splits.
 *
 * <p>Extendible hashing gives every segment a local depth - how many low hash bits all of its
 * keys share - and the directory is exactly as wide as the deepest segment needs. A segment
 * shallower than the directory already owns several slots, so splitting it re-points half of
 * them and the directory keeps its width; only splitting a segment as deep as the directory
 * doubles it. A directory that doubled on every split instead was 2^splits times its starting
 * width: fifteen splits into an ingest it held two million slots for seventy-nine segments, and
 * the heap ran out with 61 MB stored.</p>
 */
@DisplayName("Routing directory growth")
class RoutingDirectoryGrowthTest {

    private static final int KEYS = 3000;

    /** A width no correct split sequence in these tests comes near; past it the test stops. */
    private static final int RUNAWAY_WIDTH = 1 << 12;

    @TempDir
    Path tempDir;

    private Path dataDir;
    private StorageEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        dataDir = Files.createDirectories(tempDir.resolve("growth"));
        engine = new StorageEngine(dataDir);
        engine.start();
        for (int i = 0; i < KEYS; i++) {
            engine.put(key(i), value(i));
        }
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @DisplayName("splitting a segment shallower than the directory re-points its slots and keeps the width")
    void shallowSplitKeepsTheWidth() throws Exception {
        split(engine.getSegment(0));
        assertThat(engine.getSegmentCount()).as("the first split of a base segment doubles").isEqualTo(128);

        Segment parent = engine.getSegment(1);
        assertThat(engine.getSegment(65)).as("slot 65 shares segment 1 before it splits").isSameAs(parent);
        split(parent);

        assertThat(engine.getSegmentCount())
            .as("segment 1 already owned two slots, so its split needs no new ones")
            .isEqualTo(128);
        assertThat(parent.splitBit()).as("partitioned on the bit its two slots differ in").isEqualTo(6);
        assertThat(engine.getSegment(1)).isSameAs(parent.leftChild());
        assertThat(engine.getSegment(65)).isSameAs(parent.rightChild());
        assertThat(Files.exists(dataDir.resolve("segment_65.dat")))
            .as("the right child is named after the smallest slot it owns")
            .isTrue();
        assertAllReadable(engine);
    }

    @Test
    @DisplayName("splitting the deepest segment doubles the directory, and every other slot keeps its segment")
    void deepSplitDoubles() throws Exception {
        split(engine.getSegment(0));
        Segment deepest = engine.getSegment(0);
        Segment buddy = engine.getSegment(64);

        split(deepest);

        assertThat(engine.getSegmentCount()).isEqualTo(256);
        assertThat(deepest.splitBit()).isEqualTo(7);
        assertThat(engine.getSegment(128)).isSameAs(deepest.rightChild());
        assertThat(engine.getSegment(192)).as("the untouched sibling keeps both its slots").isSameAs(buddy);
        assertThat(engine.getSegment(5)).isSameAs(engine.getSegment(133));
        assertAllReadable(engine);
    }

    @Test
    @DisplayName("forty splits under concurrent writes leave a directory of two slots per base segment")
    void manySplitsStayProportionate() throws Exception {
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(4);
        List<Thread> writers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int first = t;
            writers.add(Thread.ofPlatform().start(() -> {
                try {
                    for (int i = KEYS + first; i < 2 * KEYS; i += 4) {
                        engine.put(key(i), value(i));
                    }
                } catch (Throwable e) {
                    writerFailure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            }));
        }

        for (int slot = 0; slot < 40; slot++) {
            split(engine.getSegment(slot));
            assertThat(engine.getSegmentCount())
                .as("after splitting slot %s", slot)
                .isLessThanOrEqualTo(RUNAWAY_WIDTH);
        }
        done.await();
        for (Thread writer : writers) {
            writer.join();
        }

        assertThat(writerFailure.get()).as("a write racing a split").isNull();
        assertThat(engine.getSegmentCount()).isEqualTo(128);
        assertThat(engine.getSegments()).hasSize(104);
        assertAllReadable(engine, 2 * KEYS);

        engine.close();
        engine = new StorageEngine(dataDir);
        engine.start();
        assertThat(engine.getSegmentCount()).isEqualTo(128);
        assertAllReadable(engine, 2 * KEYS);
    }

    @Test
    @DisplayName("a directory lost after several shallow splits is rebuilt exactly from the file names")
    void shallowSplitsReconstructExactly() throws Exception {
        split(engine.getSegment(7));
        split(engine.getSegment(3));
        split(engine.getSegment(40));
        engine.close();
        Files.delete(dataDir.resolve(RoutingDirectory.FILE_NAME));

        engine = new StorageEngine(dataDir);
        engine.start();

        assertThat(engine.getSegmentCount()).isEqualTo(128);
        assertAllReadable(engine);
    }

    @Test
    @DisplayName("a split whose children reached disk before its directory did is replayed on start")
    void unrecordedShallowSplitIsReplayed() throws Exception {
        split(engine.getSegment(0));
        Path directory = dataDir.resolve(RoutingDirectory.FILE_NAME);
        byte[] beforeSplit = Files.readAllBytes(directory);
        split(engine.getSegment(1));
        engine.close();
        // A crash after the children were renamed into place and before the directory was
        // written: the width is the same either way, so nothing but the file names says so.
        Files.write(directory, beforeSplit);

        engine = new StorageEngine(dataDir);
        engine.start();

        assertThat(engine.getSegmentCount()).isEqualTo(128);
        assertThat(engine.getSegment(65)).isNotSameAs(engine.getSegment(1));
        assertThat(engine.getSegment(65).path().getFileName().toString()).isEqualTo("segment_65.dat");
        assertAllReadable(engine);
        assertThat(RoutingDirectory.read(dataDir)[65]).as("and the directory is rewritten").isEqualTo(65);
    }

    @Test
    @DisplayName("an unrecorded split that doubled the directory is replayed, doubling it again")
    void unrecordedDoublingSplitIsReplayed() throws Exception {
        split(engine.getSegment(0));
        split(engine.getSegment(1));
        Path directory = dataDir.resolve(RoutingDirectory.FILE_NAME);
        byte[] beforeSplit = Files.readAllBytes(directory);
        split(engine.getSegment(65));
        engine.close();
        Files.write(directory, beforeSplit);

        engine = new StorageEngine(dataDir);
        engine.start();

        assertThat(engine.getSegmentCount()).isEqualTo(256);
        assertThat(engine.getSegment(193).path().getFileName().toString()).isEqualTo("segment_193.dat");
        assertThat(engine.getSegment(129)).isSameAs(engine.getSegment(1));
        assertAllReadable(engine);
    }

    @Test
    @DisplayName("a write and a delete that reach a segment mid-split wait for it rather than fail")
    void mutationsWaitForASplitInProgress() throws Exception {
        Segment target = engine.getSegment(9);
        byte[] pending = keyRoutedTo(target, "pending-");
        byte[] doomed = keyRoutedTo(target, "doomed-");
        engine.put(doomed, value(0));

        target.beginSplit();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> deleted = new AtomicReference<>();
        Thread writer = Thread.ofPlatform().start(() -> {
            try {
                engine.put(pending, value(1));
                deleted.set(engine.delete(doomed));
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        Thread.sleep(50);
        assertThat(writer.isAlive()).as("the write waits while the segment is copied").isTrue();

        target.abortSplit();
        writer.join(10_000);

        assertThat(failure.get()).isNull();
        assertThat(deleted.get()).isTrue();
        assertThat(engine.get(pending).toArray(ValueLayout.JAVA_BYTE)).isEqualTo(value(1));
        assertThat(engine.get(doomed)).isNull();
    }

    @Test
    @DisplayName("an entry no segment could ever hold is refused without splitting anything")
    void oversizedEntryIsRefused() {
        int width = engine.getSegmentCount();
        long splits = engine.getSplitWorker().getSplitCount();

        assertThatThrownBy(() -> engine.put(key(-1), new byte[2 * 1024 * 1024]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("can never fit");

        assertThat(engine.getSegmentCount()).isEqualTo(width);
        assertThat(engine.getSplitWorker().getSplitCount()).isEqualTo(splits);
    }

    @Test
    @DisplayName("a split that would take the directory past its limit is refused, counted and named")
    void widthLimitIsEnforced() throws Exception {
        engine.close();
        engine = new StorageEngine(dataDir).withMaxRoutingWidth(128);
        engine.start();

        split(engine.getSegment(0));
        split(engine.getSegment(1));
        assertThat(engine.getSegmentCount()).isEqualTo(128);

        Segment deepest = engine.getSegment(0);
        assertThatThrownBy(() -> engine.routingTable.splitBitFor(deepest))
            .isInstanceOf(RoutingDirectoryFullException.class)
            .hasMessageContaining("128");

        engine.getSplitWorker().requestSplit(deepest);
        long deadline = System.currentTimeMillis() + 10_000;
        while (engine.getStatistics().getRefusedSplits() < 2) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            Thread.sleep(10);
        }
        assertThat(deepest.isSplitting() || deepest.isSplitComplete()).isFalse();
        assertThat(engine.getSegmentCount()).isEqualTo(128);
        assertAllReadable(engine);
    }

    @Test
    @DisplayName("the width limit must be a power of two the base directory fits in")
    void widthLimitIsValidated() {
        StorageEngine unstarted = new StorageEngine(dataDir);
        try {
            assertThatThrownBy(() -> unstarted.withMaxRoutingWidth(100))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> unstarted.withMaxRoutingWidth(32))
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(unstarted.withMaxRoutingWidth(1 << 16).maxRoutingWidth()).isEqualTo(1 << 16);
        } finally {
            unstarted.close();
        }
        assertThatThrownBy(() -> engine.withMaxRoutingWidth(1 << 16))
            .as("started engines keep the limit they started with")
            .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Splits a segment and waits for the split to publish.
     *
     * @param segment The segment
     * @throws InterruptedException if interrupted while waiting
     */
    private void split(Segment segment) throws InterruptedException {
        assertThat(segment).isNotNull();
        engine.getSplitWorker().requestSplit(segment);
        long deadline = System.currentTimeMillis() + 10_000;
        while (!segment.isSplitComplete()) {
            assertThat(System.currentTimeMillis())
                .as("split of %s did not publish", segment.path())
                .isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    /**
     * Finds a key the directory routes to a given segment.
     *
     * @param target The segment
     * @param prefix Distinguishes keys found for different purposes
     * @return A key routed to it
     */
    private byte[] keyRoutedTo(Segment target, String prefix) {
        for (int i = 0; ; i++) {
            byte[] candidate = (prefix + i).getBytes(StandardCharsets.UTF_8);
            if (engine.routing.segmentFor(
                    com.eonmux.excalibur.store.hash.XxHash64.hash(candidate)) == target) {
                return candidate;
            }
        }
    }

    private void assertAllReadable(StorageEngine target) {
        assertAllReadable(target, KEYS);
    }

    private static void assertAllReadable(StorageEngine target, int keys) {
        for (int i = 0; i < keys; i++) {
            MemorySegment slice = target.get(key(i));
            assertThat(slice).as("key %s", i).isNotNull();
            assertThat(slice.toArray(ValueLayout.JAVA_BYTE)).as("key %s", i).isEqualTo(value(i));
        }
    }

    private static byte[] key(int i) {
        return ("growth-key-" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(int i) {
        return ("growth-value-" + i).getBytes(StandardCharsets.UTF_8);
    }
}
