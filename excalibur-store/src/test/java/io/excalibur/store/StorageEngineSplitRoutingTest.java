package io.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the routing contract around a segment split.
 *
 * <p>These pin down three properties that are individually invisible but together
 * decide whether a split loses data:</p>
 * <ol>
 *   <li>Entries are partitioned on the hash bit that the widened routing mask newly
 *       consults, so both children are addressable.</li>
 *   <li>Doubling the routing table gives every unsplit segment a buddy pointer, so
 *       widening the mask does not orphan the keys of segments that did not split.</li>
 *   <li>The children end up at canonical {@code segment_<index>.dat} names, so segment
 *       discovery finds them again after a restart.</li>
 * </ol>
 */
@DisplayName("StorageEngine Split Routing Tests")
class StorageEngineSplitRoutingTest {

    private static final int KEY_COUNT = 400;

    @TempDir
    Path tempDir;

    private Path dataDir;
    private StorageEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = tempDir.resolve("split-routing");
        Files.createDirectories(dataDir);
        engine = new StorageEngine(dataDir);
        engine.start();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @DisplayName("Every key stays readable after a split, and the children are discoverable")
    void everyKeyStaysReadableAcrossASplit() throws Exception {
        Map<String, String> written = writeKeys();

        int routingBefore = engine.getSegmentCount();
        int splitBit = Integer.numberOfTrailingZeros(routingBefore);
        Segment parent = findPopulatedSegment(routingBefore);

        engine.getSplitWorker().requestSplit(parent, splitBit);
        awaitSplit(parent);

        assertThat(engine.getSegmentCount())
            .as("publishing a split doubles the routing table")
            .isEqualTo(routingBefore * 2);

        assertAllReadable(written);
        assertNoTemporaryFiles();
    }

    @Test
    @DisplayName("Every key survives a restart after a split")
    void everyKeySurvivesRestartAfterSplit() throws Exception {
        Map<String, String> written = writeKeys();

        int routingBefore = engine.getSegmentCount();
        Segment parent = findPopulatedSegment(routingBefore);
        engine.getSplitWorker().requestSplit(parent, Integer.numberOfTrailingZeros(routingBefore));
        awaitSplit(parent);

        engine.flush();
        engine.close();

        engine = new StorageEngine(dataDir);
        engine.start();

        assertAllReadable(written);
    }

    /**
     * Writes a fixed set of keys and returns what was written.
     *
     * @return Key to value mapping
     */
    private Map<String, String> writeKeys() {
        Map<String, String> written = new LinkedHashMap<>();
        for (int i = 0; i < KEY_COUNT; i++) {
            String key = "split-routing-key-" + i;
            String value = "split-routing-value-" + i;
            engine.put(key.getBytes(), value.getBytes());
            written.put(key, value);
        }
        return written;
    }

    /**
     * Returns a segment holding at least one entry.
     *
     * @param routingCount Current routing table width
     * @return A populated segment
     */
    private Segment findPopulatedSegment(int routingCount) {
        for (int i = 0; i < routingCount; i++) {
            Segment segment = engine.getSegment(i);
            if (segment != null && segment.entryCount() > 0) {
                return segment;
            }
        }
        throw new AssertionError("No populated segment to split");
    }

    /**
     * Waits until the split of a segment has been published.
     *
     * @param parent The segment being split
     * @throws InterruptedException if interrupted while waiting
     */
    private void awaitSplit(Segment parent) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!parent.isSplitComplete()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Split did not complete within the timeout");
            }
            Thread.sleep(10);
        }
    }

    /**
     * Asserts every written key resolves to its own value.
     *
     * @param written Key to value mapping
     */
    private void assertAllReadable(Map<String, String> written) {
        written.forEach((key, value) -> {
            MemorySegment slice = engine.get(key.getBytes());
            assertThat(slice).as("key %s must still resolve after the split", key).isNotNull();
            assertThat(slice.toArray(ValueLayout.JAVA_BYTE))
                .as("key %s must resolve to its own value", key)
                .isEqualTo(value.getBytes());
        });
    }

    /**
     * Asserts that no build-time scratch file survived the split.
     *
     * @throws IOException if the data directory cannot be listed
     */
    private void assertNoTemporaryFiles() throws IOException {
        try (Stream<Path> files = Files.list(dataDir)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                .as("split children must be renamed into segment_<index>.dat")
                .noneMatch(name -> name.endsWith(".left") || name.endsWith(".right")
                    || name.endsWith(".compact") || name.endsWith(".dat.dat"));
        }
    }
}
