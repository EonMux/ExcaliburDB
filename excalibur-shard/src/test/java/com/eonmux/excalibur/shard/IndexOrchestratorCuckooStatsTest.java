package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.shard.worker.CuckooMetricsWorker;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The membership filters a node reports on are the ones ingestion writes to.
 *
 * <p>Ingestion records a segment's terms in its level of the hierarchical filter. Segments reach
 * the orchestrator without a filter of their own - boot registers them with none, and the
 * reconciliation sweep carries forward whatever the last registration held - so unless the
 * registration resolves the level, the metrics worker samples nothing and the false-positive
 * rates on {@code /v1/stats} read zero on a node whose filters are filling up.</p>
 */
@DisplayName("Cuckoo filter figures reported by the orchestrator")
class IndexOrchestratorCuckooStatsTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private StorageEngine storageEngine;
    private IndexOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        storageEngine = new StorageEngine(tempDir, arena);
        storageEngine.start();
        orchestrator = new IndexOrchestrator(storageEngine.getCatalog(), storageEngine);
    }

    @AfterEach
    void tearDown() {
        orchestrator.close();
        storageEngine.close();
        arena.close();
    }

    private Map.Entry<Long, Segment> firstSegment() {
        return storageEngine.getSegments().entrySet().iterator().next();
    }

    /**
     * Waits out the term filter rebuilds that registering a segment schedules.
     *
     * <p>A rebuild empties a segment's level and refills it from the entries the segment
     * stores. These tests file terms straight into the filter with nothing stored behind them,
     * so a rebuild still running when they do would rightly empty the level again - and what the
     * test then samples depends on which thread got there first.</p>
     */
    private void awaitRebuilds() {
        try {
            assertThat(orchestrator.awaitTermFilterRebuilds(20, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for term filter rebuilds", e);
        }
    }

    private void record(HierarchicalCuckooFilter hierarchy, long segmentId, int terms) {
        awaitRebuilds();
        for (int i = 0; i < terms; i++) {
            hierarchy.insert(("term-" + i).getBytes(StandardCharsets.UTF_8),
                String.valueOf(segmentId));
        }
    }

    @Test
    @DisplayName("nothing is reported while no filter holds anything")
    void emptyFiltersReportNoRate() {
        orchestrator.setHierarchicalCuckooFilter(new HierarchicalCuckooFilter(100_000));
        Map.Entry<Long, Segment> segment = firstSegment();
        orchestrator.registerSegment(segment.getKey(), segment.getValue(), null, null, null);

        assertThat(orchestrator.getCuckooFpRates()).isEqualTo(CuckooMetricsWorker.FpRates.NONE);
    }

    @Test
    @DisplayName("a segment registered without a filter is sampled through its hierarchy level")
    void registrationResolvesTheHierarchyLevel() {
        HierarchicalCuckooFilter hierarchy = new HierarchicalCuckooFilter(100_000);
        orchestrator.setHierarchicalCuckooFilter(hierarchy);
        Map.Entry<Long, Segment> segment = firstSegment();

        orchestrator.registerSegment(segment.getKey(), segment.getValue(), null, null, null);
        record(hierarchy, segment.getKey(), 500);

        CuckooFilter level = hierarchy.getSegmentFilter(String.valueOf(segment.getKey()));
        CuckooMetricsWorker.FpRates rates = orchestrator.getCuckooFpRates();
        assertThat(rates.filters()).isEqualTo(1);
        assertThat(rates.average()).isPositive().isCloseTo(level.estimateFpRate(), within(1e-12));
        assertThat(rates.max()).isEqualTo(rates.average());
    }

    @Test
    @DisplayName("segments registered before the hierarchy arrived are sampled once it does")
    void backFillRepointsExistingRegistrations() {
        Map.Entry<Long, Segment> segment = firstSegment();
        orchestrator.registerSegment(segment.getKey(), segment.getValue(), null, null, null);
        HierarchicalCuckooFilter hierarchy = new HierarchicalCuckooFilter(100_000);

        orchestrator.setHierarchicalCuckooFilter(hierarchy);
        record(hierarchy, segment.getKey(), 200);

        assertThat(orchestrator.getCuckooFpRates().filters()).isEqualTo(1);
        assertThat(orchestrator.getCuckooFpRates().average()).isPositive();
    }

    @Test
    @DisplayName("the average and the worst are taken over the segments that hold anything")
    void averageAndMaxAcrossSegments() {
        HierarchicalCuckooFilter hierarchy = new HierarchicalCuckooFilter(100_000);
        orchestrator.setHierarchicalCuckooFilter(hierarchy);
        orchestrator.syncSegmentsFromStorage();
        long[] ids = storageEngine.getSegments().keySet().stream()
            .mapToLong(Long::longValue).sorted().limit(3).toArray();
        assertThat(ids).hasSize(3);
        record(hierarchy, ids[0], 100);
        record(hierarchy, ids[1], 2_000);

        double light = hierarchy.getSegmentFilter(String.valueOf(ids[0])).estimateFpRate();
        double heavy = hierarchy.getSegmentFilter(String.valueOf(ids[1])).estimateFpRate();
        CuckooMetricsWorker.FpRates rates = orchestrator.getCuckooFpRates();

        assertThat(heavy).isGreaterThan(light);
        assertThat(rates.filters()).as("the empty third segment is left out").isEqualTo(2);
        assertThat(rates.average()).isCloseTo((light + heavy) / 2, within(1e-12));
        assertThat(rates.max()).isCloseTo(heavy, within(1e-12));
    }

    @Test
    @DisplayName("a re-registered id keeps the level holding the terms it recorded")
    void reRegistrationKeepsTheLevel() {
        HierarchicalCuckooFilter hierarchy = new HierarchicalCuckooFilter(100_000);
        orchestrator.setHierarchicalCuckooFilter(hierarchy);
        Map.Entry<Long, Segment> segment = firstSegment();
        orchestrator.registerSegment(segment.getKey(), segment.getValue(), null, null, null);
        record(hierarchy, segment.getKey(), 300);
        CuckooFilter level = hierarchy.getSegmentFilter(String.valueOf(segment.getKey()));

        CuckooFilter unrelated = CuckooFilter.create(10_000, 13, 42L);
        orchestrator.registerSegment(segment.getKey(), segment.getValue(), null, unrelated, null);

        assertThat(hierarchy.getSegmentFilter(String.valueOf(segment.getKey()))).isSameAs(level);
        assertThat(orchestrator.getCuckooFpRates().average())
            .isCloseTo(level.estimateFpRate(), within(1e-12));
    }
}
