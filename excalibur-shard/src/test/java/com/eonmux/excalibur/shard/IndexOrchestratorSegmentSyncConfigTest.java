package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.store.StorageEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reconciliation sweep runs at the period an operator configured, not at a hard-wired one.
 *
 * <p>The sweep is the only thing that notices a segment the storage engine created behind the
 * orchestrator's back, so its period is the worst-case delay before such a segment is indexed
 * at all. Every other interval on this node is tunable and this one was not: the key did not
 * exist, so a deployment with thousands of segments - where a sweep is an expensive
 * quadratic pass - had no way to slow it down, and one that wanted faster convergence after a
 * split storm had no way to speed it up.</p>
 */
@DisplayName("Segment reconciliation sweep period from configuration")
class IndexOrchestratorSegmentSyncConfigTest {

    /**
     * Long enough that the old hard-wired 1000ms sweep fires several times inside it, short
     * enough to keep the suite quick.
     */
    private static final long OBSERVATION_MS = 3_000;

    @TempDir
    Path tempDir;

    private Arena arena;
    private StorageEngine storageEngine;
    private IndexOrchestrator orchestrator;

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.close();
        }
        if (storageEngine != null) {
            storageEngine.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    private IndexOrchestrator startedWith(String syncIntervalMs) {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.shard.segment-sync-interval-ms", syncIntervalMs);
        arena = Arena.ofShared();
        storageEngine = new StorageEngine(tempDir, arena);
        storageEngine.start();
        // The engine creates the base keyspace at start(), so there is always something for
        // the sweep to find that the orchestrator was never told about.
        assertThat(storageEngine.getSegments()).isNotEmpty();
        orchestrator = IndexOrchestrator.fromConfig(
            storageEngine.getCatalog(), storageEngine, null,
            new Config(props).shard());
        assertThat(orchestrator.getRegisteredSegmentCount()).isZero();
        orchestrator.start();
        return orchestrator;
    }

    @Test
    @DisplayName("a long configured period keeps the sweep from running on the stock schedule")
    void aLongPeriodDelaysTheSweep() {
        startedWith("60000");

        // A minute between sweeps means nothing is reconciled inside the observation window.
        // Left unwired, the sweep keeps the hard-wired one-second period and has registered
        // every segment in the directory long before this returns.
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(OBSERVATION_MS));

        assertThat(orchestrator.getRegisteredSegmentCount())
            .as("the configured 60s period must be the one the sweep runs at")
            .isZero();
    }

    @Test
    @DisplayName("a short configured period still converges on the storage directory")
    void aShortPeriodStillSweeps() {
        // The mirror of the above: a configured period must not switch the sweep off, only
        // change how often it runs.
        startedWith(String.valueOf(SegmentSyncScheduler.MIN_INTERVAL_MS));
        int inDirectory = storageEngine.getSegments().size();

        // Poll for full convergence, not for the first registration: one pass registers the
        // segments one at a time, so a sample taken mid-pass reads a partial count.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (orchestrator.getRegisteredSegmentCount() < inDirectory
            && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
        }

        assertThat(orchestrator.getRegisteredSegmentCount())
            .as("the sweep still converges the registry on the engine's directory")
            .isEqualTo(inDirectory);
    }
}
