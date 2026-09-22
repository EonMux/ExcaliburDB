package com.eonmux.excalibur.store.catalog;

import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.SegmentCatalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Every entry point that touches the catalog mapping holds the catalog's monitor.
 *
 * <p>The record count and the mapping handle are plain fields, rewritten by
 * {@code writeSegment} and by {@code close} while other threads read them. A reader that does
 * not take the same monitor can see a count that has been raised for a record not yet written,
 * or a mapping that {@code close} has already released. This is the file a node's catalog is
 * rebuilt from after a crash, so a torn read here is the difference between recovering a node
 * and rebuilding it.</p>
 *
 * <p>The property is checked the way it is actually relied on rather than by inspecting
 * modifiers: the test thread holds the catalog's monitor, and a second thread calling the entry
 * point must block until it is released. A method that answers while the monitor is held is a
 * method that could have answered mid-write.</p>
 */
@DisplayName("Catalog file reads hold the catalog monitor")
class CatalogFileMonitorTest {

    /** How long a call is given to reach the monitor before the test gives up on it. */
    private static final long BLOCK_TIMEOUT_MS = 5_000;

    /** How long a parked call must stay parked before the exclusion is believed. */
    private static final long SETTLE_MS = 100;

    @TempDir
    Path tempDir;

    /** A call into the catalog that is expected to be excluded by the monitor. */
    @FunctionalInterface
    private interface CatalogCall {
        void run(CatalogFile catalog) throws IOException;
    }

    @Test
    @DisplayName("readSegment waits for a writer to release the monitor")
    void readSegmentWaitsForTheMonitor() throws Exception {
        assertExcludedByMonitor(catalog -> catalog.readSegment(1L));
    }

    @Test
    @DisplayName("getSegmentCount waits for a writer to release the monitor")
    void getSegmentCountWaitsForTheMonitor() throws Exception {
        assertExcludedByMonitor(CatalogFile::getSegmentCount);
    }

    @Test
    @DisplayName("isClosed waits for a writer to release the monitor")
    void isClosedWaitsForTheMonitor() throws Exception {
        assertExcludedByMonitor(CatalogFile::isClosed);
    }

    /**
     * Asserts that the given call cannot complete while another thread holds the catalog's
     * monitor, and that it completes once the monitor is released.
     *
     * @param call The catalog entry point under test
     * @throws Exception if the test thread is interrupted or the catalog cannot be opened
     */
    private void assertExcludedByMonitor(CatalogCall call) throws Exception {
        try (CatalogFile catalog = new CatalogFile(tempDir.resolve("catalog.dat"))) {
            catalog.writeSegment(live(1L));

            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                try {
                    call.run(catalog);
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    finished.countDown();
                }
            }, "catalog-monitor-probe");

            synchronized (catalog) {
                caller.start();
                awaitBlockedOrFinished(caller, finished);
                // A second grace period after the thread parks: a thread can be BLOCKED for a
                // moment on something other than this monitor - class initialisation, say - and
                // that must not be mistaken for the exclusion under test.
                assertThat(finished.await(SETTLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("the call answered while another thread held the catalog monitor, "
                        + "so it could equally have answered in the middle of a write")
                    .isFalse();
            }

            assertThat(finished.await(BLOCK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS))
                .as("the call did not complete after the monitor was released")
                .isTrue();
            caller.join(BLOCK_TIMEOUT_MS);
            assertThat(failure.get()).isNull();
        }
    }

    /**
     * Waits until the probe thread is either parked on the monitor or has finished.
     *
     * @param caller   The probe thread
     * @param finished Counted down when the probe returns
     * @throws InterruptedException if the test thread is interrupted
     */
    private void awaitBlockedOrFinished(Thread caller, CountDownLatch finished)
            throws InterruptedException {
        long deadline = System.nanoTime() + BLOCK_TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (finished.getCount() == 0 || caller.getState() == Thread.State.BLOCKED) {
                return;
            }
            Thread.sleep(1);
        }
        fail("the probe thread neither blocked on the catalog monitor nor completed");
    }

    /**
     * Builds an ACTIVE segment record the catalog will accept.
     *
     * @param id The segment id
     * @return Metadata for a live segment
     */
    private SegmentCatalog.SegmentMetadata live(long id) {
        SegmentCatalog.SegmentMetadata metadata = new SegmentCatalog.SegmentMetadata(id);
        metadata.setVolumePath(tempDir.resolve("segment_" + id + ".dat"));
        metadata.setEntryCount((int) id);
        metadata.setEntryState(SegmentCatalog.ENTRY_STATE_ACTIVE);
        metadata.restoreLifecycleState(LifecycleState.ACTIVE);
        return metadata;
    }
}
