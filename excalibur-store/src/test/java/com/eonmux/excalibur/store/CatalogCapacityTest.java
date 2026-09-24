package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.catalog.CatalogFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A split the segment catalog has no row for is refused before it touches anything.
 *
 * <p>Split publication moves the right child onto its canonical name and the left child over
 * the parent's file, and only then registers the children's rows. A catalog that cannot take
 * the right child's row found out after the moves, with the parent's file already replaced, and
 * the worker then returned that parent to service. So the split is refused while the parent is
 * untouched - when it is planned, which is where a writer hears of it, and again, holding a
 * reservation, before publication moves anything.</p>
 *
 * <p>The catalog is filled with reservations for ids nothing writes rather than with tens of
 * thousands of real segments: to a split, a reserved row is as taken as a written one.</p>
 */
@DisplayName("Catalog capacity and splits")
class CatalogCapacityTest {

    private static final int KEYS = 2000;

    /** Routing indices far past any directory these tests grow, used to fill the catalog. */
    private static final long FILLER_BASE = 1L << 24;

    @TempDir
    Path tempDir;

    private Path dataDir;
    private StorageEngine engine;
    private long[] fillers;

    @BeforeEach
    void setUp() throws Exception {
        dataDir = Files.createDirectories(tempDir.resolve("capacity"));
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

    /** Reserves every catalog row the store has not used. */
    private void fillCatalog() {
        int rows = engine.catalog.size();
        fillers = LongStream.range(FILLER_BASE, FILLER_BASE + CatalogFile.MAX_SEGMENTS - rows)
            .toArray();
        assertThat(engine.catalog.reserveRows(fillers)).isTrue();
    }

    @Test
    @DisplayName("a split with no catalog row is refused when planned, counted and named")
    void aSplitIsRefusedWhenPlanned() throws Exception {
        fillCatalog();
        Segment parent = engine.getSegment(0);
        long refusedBefore = engine.getStatistics().getRefusedSplits();

        assertThatThrownBy(() -> engine.routingTable.splitBitFor(parent))
            .isInstanceOf(CatalogFullException.class)
            .isInstanceOf(SegmentAtCapacityException.class)
            .hasMessageContaining(String.valueOf(CatalogFile.MAX_SEGMENTS))
            .hasMessageContaining(parent.path().toString());
        assertThatThrownBy(() -> engine.routingTable.splitBitIfRouted(parent))
            .isInstanceOf(CatalogFullException.class);

        engine.getSplitWorker().requestSplit(parent);
        long deadline = System.currentTimeMillis() + 10_000;
        while (engine.getStatistics().getRefusedSplits() < refusedBefore + 3) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            Thread.sleep(10);
        }
        assertThat(parent.isSplitting() || parent.isSplitComplete()).isFalse();
        assertThat(engine.getSegment(0)).isSameAs(parent);
        assertAllReadable();

        engine.catalog.releaseRows(fillers);
        assertThat(engine.routingTable.splitBitFor(parent))
            .as("a freed row lets the split go ahead")
            .isNotNegative();
    }

    @Test
    @DisplayName("a publication the catalog cannot take moves no file")
    void aRefusedPublicationMovesNothing() throws Exception {
        Segment parent = engine.getSegment(0);
        int routedBefore = engine.getSegmentCount();
        Set<String> filesBefore = canonicalSegmentFiles();
        Path left = Path.of(parent.path() + ".left");
        Path right = Path.of(parent.path() + ".right");
        long capacity = Segment.capacityForSlots(parent.slotCount());
        Segment leftChild = new Segment(left, capacity, engine.arena, parent.loadFactorThreshold());
        Segment rightChild = new Segment(right, capacity, engine.arena, parent.loadFactorThreshold());
        try {
            fillCatalog();

            assertThatThrownBy(() -> engine.splits.handleSplitComplete(parent, leftChild, rightChild))
                .isInstanceOf(CatalogFullException.class);

            assertThat(leftChild.path()).isEqualTo(left);
            assertThat(rightChild.path()).isEqualTo(right);
            assertThat(left).exists();
            assertThat(right).exists();
            assertThat(parent.isClosed()).isFalse();
            assertThat(engine.getSegment(0)).isSameAs(parent);
            assertThat(engine.getSegmentCount()).isEqualTo(routedBefore);
            assertThat(canonicalSegmentFiles())
                .as("no child was moved onto a canonical name")
                .isEqualTo(filesBefore);
            assertAllReadable();
        } finally {
            leftChild.close();
            rightChild.close();
            Files.deleteIfExists(left);
            Files.deleteIfExists(right);
        }
    }

    @Test
    @DisplayName("a reindex segment the catalog cannot take is not kept in memory")
    void aRefusedCreateLeavesNoRecord() {
        fillCatalog();
        int before = engine.catalog.size();

        assertThatThrownBy(() -> engine.catalog.create("source-a", dataDir.resolve("new.dat")))
            .isInstanceOf(UncheckedIOException.class)
            .rootCause()
            .hasMessageContaining("none free");
        assertThat(engine.catalog.size()).isEqualTo(before);
        assertThat(engine.catalog.getSegmentsBySource("source-a")).isEmpty();
    }

    private Set<String> canonicalSegmentFiles() throws IOException {
        try (Stream<Path> files = Files.list(dataDir)) {
            return files.map(path -> path.getFileName().toString())
                .filter(name -> name.matches("segment_\\d+\\.dat"))
                .collect(Collectors.toSet());
        }
    }

    private void assertAllReadable() {
        for (int i = 0; i < KEYS; i++) {
            MemorySegment slice = engine.get(key(i));
            assertThat(slice).as("key %s", i).isNotNull();
            assertThat(slice.toArray(ValueLayout.JAVA_BYTE)).as("key %s", i).isEqualTo(value(i));
        }
    }

    private static byte[] key(int i) {
        return ("capacity-key-" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(int i) {
        return ("capacity-value-" + i).getBytes(StandardCharsets.UTF_8);
    }
}
