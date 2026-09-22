package com.eonmux.excalibur.query.visibility;

import com.eonmux.excalibur.shard.visibility.EpochIsolationValidator;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for epoch isolation in query results.
 *
 * <h2>Test Coverage</h2>
 * <p>These tests verify that:</p>
 * <ul>
 *   <li>Queries never see data from epochs newer than their captured epoch</li>
 *   <li>No torn reads occur during concurrent ingestion and querying</li>
 *   <li>Result filtering correctly respects epoch boundaries</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Filtered results only contain nodes visible at query epoch</li>
 *   <li>Concurrent operations don't cause visibility violations</li>
 *   <li>Validation detects any epoch isolation breaches</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
class EpochIsolationIntegrationTest {

    /** Node ids are unique only within one graph, so every record names the graph it is in. */
    private static final long SEGMENT = 0L;

    private EpochIsolationValidator validator;

    @BeforeEach
    void setUp() {
        validator = new EpochIsolationValidator();
    }

    @Nested
    @DisplayName("No Torn Reads")
    class NoTornReads {

        /**
         * Test: Verify no torn reads during simulated concurrent ingestion.
         *
         * <p>This test simulates the scenario where:</p>
         * <ol>
         *   <li>A query captures epoch 10</li>
         *   <li>Data is ingested at epochs 11, 12, 13</li>
         *   <li>The query should NOT see any data from epochs > 10</li>
         * </ol>
         */
        @Test
        @DisplayName("query at epoch E does not see data from epoch E+1")
        void queryDoesNotSeeFutureEpochData() {
            // Setup: Register batches at different epochs, scoped by segment exactly as
            // the HNSW integration worker does
            long segmentId = 0L;
            validator.registerNodeRange(segmentId, 1L, 5L);   // Visible at epoch 5
            validator.registerNodeRange(segmentId, 2L, 8L);   // Visible at epoch 8
            validator.registerNodeRange(segmentId, 3L, 12L);  // Visible at epoch 12 (AFTER query)
            validator.registerNodeRange(segmentId, 4L, 15L);  // Visible at epoch 15 (AFTER query)

            // Simulate query at epoch 10
            long queryEpoch = 10L;

            // Create results from all nodes
            List<HnswSearchResult> allResults = new ArrayList<>();
            allResults.add(new HnswSearchResult(1L, 0.1f));
            allResults.add(new HnswSearchResult(2L, 0.2f));
            allResults.add(new HnswSearchResult(3L, 0.3f));
            allResults.add(new HnswSearchResult(4L, 0.4f));

            // Filter results to only include nodes visible at query epoch
            List<HnswSearchResult> filtered =
                validator.filterResults(segmentId, allResults, queryEpoch);

            // Verify: Only nodes 1 and 2 should be visible at epoch 10
            assertThat(filtered).hasSize(2);
            assertThat(filtered.stream().map(HnswSearchResult::nodeId))
                .containsExactlyInAnyOrder(1L, 2L);

            // Validate no epoch isolation violations
            EpochIsolationValidator.ValidationResult validation =
                validator.validateResults(segmentId, filtered, queryEpoch);
            assertThat(validation.valid()).isTrue();
            assertThat(validation.violations()).isEqualTo(0);
        }

        /**
         * Test: Verify epoch isolation during high-concurrency simulation.
         */
        @Test
        @DisplayName("no torn reads under high concurrency")
        void noTornReadsUnderHighConcurrency() throws Exception {
            int numWriterThreads = 4;
            int numReaderThreads = 4;
            int nodesPerWriter = 100;

            AtomicInteger violationCount = new AtomicInteger(0);
            AtomicInteger totalReads = new AtomicInteger(0);
            AtomicInteger resultsRead = new AtomicInteger(0);

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch writersDone = new CountDownLatch(numWriterThreads);
            AtomicInteger currentEpoch = new AtomicInteger(0);

            // Per segment: how many node IDs have been registered, published only after
            // the registration itself. A node below this watermark has its covering
            // checkpoint fixed forever, because every later checkpoint has a higher ID -
            // which is exactly how a graph allocates node IDs.
            AtomicIntegerArray registeredUpTo = new AtomicIntegerArray(numWriterThreads);

            ExecutorService executor = Executors.newFixedThreadPool(numWriterThreads + numReaderThreads);

            // Writer threads: each owns a segment and registers ascending node IDs at
            // ascending epochs, registering BEFORE the node exists, as the integration
            // worker does.
            for (int t = 0; t < numWriterThreads; t++) {
                final int segmentId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < nodesPerWriter; i++) {
                            int epoch = currentEpoch.incrementAndGet();
                            validator.registerNodeRange(segmentId, i, epoch);
                            registeredUpTo.set(segmentId, i + 1);
                            Thread.yield();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        writersDone.countDown();
                    }
                });
            }

            // Reader threads: capture an epoch, then read the nodes that already existed
            // when it was captured. Every one of them was registered at an epoch at or
            // below the captured one, so all must survive the filter and none may be
            // reported as a violation.
            for (int t = 0; t < numReaderThreads; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        while (writersDone.getCount() > 0) {
                            for (int segmentId = 0; segmentId < numWriterThreads; segmentId++) {
                                int existingNodes = registeredUpTo.get(segmentId);
                                int queryEpoch = currentEpoch.get();
                                if (existingNodes == 0) {
                                    continue;
                                }

                                List<HnswSearchResult> results = new ArrayList<>();
                                for (int i = 0; i < existingNodes; i++) {
                                    results.add(new HnswSearchResult(i, i * 0.001f));
                                }

                                List<HnswSearchResult> filtered =
                                    validator.filterResults(segmentId, results, queryEpoch);
                                EpochIsolationValidator.ValidationResult validation =
                                    validator.validateResults(segmentId, filtered, queryEpoch);

                                totalReads.incrementAndGet();
                                resultsRead.addAndGet(filtered.size());
                                if (filtered.size() != results.size()) {
                                    // A node that existed at the captured epoch was hidden
                                    violationCount.addAndGet(results.size() - filtered.size());
                                }
                                if (!validation.valid()) {
                                    violationCount.addAndGet(validation.violations());
                                }
                            }
                            Thread.yield();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            // Start all threads
            startLatch.countDown();

            // Wait for completion
            writersDone.await(30, TimeUnit.SECONDS);
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            assertThat(violationCount.get())
                .as("No epoch isolation violations should occur during concurrent operations")
                .isEqualTo(0);

            assertThat(totalReads.get())
                .as("Should have performed reads during concurrent operations")
                .isGreaterThan(0);

            assertThat(resultsRead.get())
                .as("Reads should have returned the nodes that existed at their epoch")
                .isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("Epoch Boundary Validation")
    class EpochBoundaryValidation {

        /**
         * Test: Verify exact epoch boundary behavior.
         *
         * <p>A node visible at epoch E should be:</p>
         * <ul>
         *   <li>NOT visible at epoch E-1</li>
         *   <li>Visible at epoch E</li>
         *   <li>Visible at epoch E+1</li>
         * </ul>
         */
        @Test
        @DisplayName("epoch boundary is exact")
        void epochBoundaryIsExact() {
            long nodeId = 12345L;
            long visibleEpoch = 100L;

            validator.registerNodeRange(SEGMENT, nodeId, visibleEpoch);

            // Not visible before
            assertThat(validator.isNodeVisible(SEGMENT, nodeId, 99L)).isFalse();

            // Visible at exact epoch
            assertThat(validator.isNodeVisible(SEGMENT, nodeId, 100L)).isTrue();

            // Visible after
            assertThat(validator.isNodeVisible(SEGMENT, nodeId, 101L)).isTrue();
            assertThat(validator.isNodeVisible(SEGMENT, nodeId, 1000L)).isTrue();
        }

        /**
         * Test: Verify epoch isolation at epoch 0 boundary.
         */
        @Test
        @DisplayName("epoch zero boundary works correctly")
        void epochZeroBoundary() {
            validator.registerNodeRange(SEGMENT, 1L, 0L);
            validator.registerNodeRange(SEGMENT, 2L, 1L);

            // Query at epoch 0 should only see node 1
            List<HnswSearchResult> results = List.of(
                new HnswSearchResult(1L, 0.1f),
                new HnswSearchResult(2L, 0.2f)
            );

            List<HnswSearchResult> filtered0 = validator.filterResults(SEGMENT, results, 0L);
            assertThat(filtered0).hasSize(1);
            assertThat(filtered0.get(0).nodeId()).isEqualTo(1L);

            // Query at epoch 1 should see both nodes
            List<HnswSearchResult> filtered1 = validator.filterResults(SEGMENT, results, 1L);
            assertThat(filtered1).hasSize(2);
        }

        /**
         * Test: Validate results from multiple epochs.
         */
        @Test
        @DisplayName("validation detects cross-epoch violations")
        void validationDetectsCrossEpochViolations() {
            // Register nodes at different epochs
            for (int i = 0; i < 20; i++) {
                validator.registerNodeRange(SEGMENT, i, i * 5L);
            }

            // Create results mixing epochs
            List<HnswSearchResult> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                results.add(new HnswSearchResult(i, i * 0.05f));
            }

            // Query at epoch 50 should only see nodes 0-10 (epochs 0, 5, 10, ..., 50)
            long queryEpoch = 50L;
            List<HnswSearchResult> filtered = validator.filterResults(SEGMENT, results, queryEpoch);

            // Nodes 0-10 have epochs 0, 5, 10, ..., 50 (all <= 50)
            // Nodes 11-19 have epochs 55, 60, ..., 95 (all > 50)
            assertThat(filtered).hasSize(11);

            // Validate the filtered results
            EpochIsolationValidator.ValidationResult validation =
                validator.validateResults(SEGMENT, filtered, queryEpoch);
            assertThat(validation.valid()).isTrue();

            // Validate the unfiltered results (should have violations)
            EpochIsolationValidator.ValidationResult unfilteredValidation =
                validator.validateResults(SEGMENT, results, queryEpoch);
            assertThat(unfilteredValidation.valid()).isFalse();
            assertThat(unfilteredValidation.violations()).isEqualTo(9); // nodes 11-19
        }
    }

    @Nested
    @DisplayName("Result Ordering Preservation")
    class ResultOrderingPreservation {

        /**
         * Test: Verify that filtering preserves original result ordering.
         *
         * <p>HNSW results are sorted by distance (closest first). Filtering
         * should maintain this ordering.</p>
         */
        @Test
        @DisplayName("filtering preserves distance ordering")
        void filteringPreservesDistanceOrdering() {
            // Register all nodes at the same epoch
            for (int i = 0; i < 10; i++) {
                validator.registerNodeRange(SEGMENT, i, 10L);
            }

            // Create results sorted by distance
            List<HnswSearchResult> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                results.add(new HnswSearchResult(i, i * 0.1f)); // distances: 0.0, 0.1, 0.2, ...
            }

            // Filter at epoch 15 (all should be visible)
            List<HnswSearchResult> filtered = validator.filterResults(SEGMENT, results, 15L);

            // Verify ordering is preserved
            assertThat(filtered).hasSize(10);
            for (int i = 0; i < 10; i++) {
                assertThat(filtered.get(i).nodeId()).isEqualTo(i);
                assertThat(filtered.get(i).distance()).isEqualTo(i * 0.1f);
            }
        }

        /**
         * Test: Verify ordering is preserved when some results are filtered out.
         */
        @Test
        @DisplayName("ordering preserved when results filtered")
        void orderingPreservedWhenFiltered() {
            // Register nodes with alternating visibility
            for (int i = 0; i < 10; i++) {
                // Even nodes visible at epoch 5, odd nodes visible at epoch 15
                validator.registerNodeRange(SEGMENT, i, i % 2 == 0 ? 5L : 15L);
            }

            // Create results sorted by distance
            List<HnswSearchResult> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                results.add(new HnswSearchResult(i, i * 0.1f));
            }

            // Query at epoch 10 (only even nodes visible)
            List<HnswSearchResult> filtered = validator.filterResults(SEGMENT, results, 10L);

            // Should have 5 results (nodes 0, 2, 4, 6, 8)
            assertThat(filtered).hasSize(5);

            // Verify ordering is preserved
            assertThat(filtered.get(0).nodeId()).isEqualTo(0);
            assertThat(filtered.get(1).nodeId()).isEqualTo(2);
            assertThat(filtered.get(2).nodeId()).isEqualTo(4);
            assertThat(filtered.get(3).nodeId()).isEqualTo(6);
            assertThat(filtered.get(4).nodeId()).isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("Integration with Document Visibility")
    class DocumentVisibilityIntegration {

        /**
         * Test: Verify EpochIsolationValidator integrates with document-level visibility.
         */
        @Test
        @DisplayName("integrates with document visibility tracking")
        void integratesWithDocumentVisibility() {
            // Scenario: 3 documents, each with multiple chunks
            // Document 1: nodes 1, 2, 3 - visible at epoch 5
            // Document 2: nodes 4, 5, 6 - visible at epoch 10
            // Document 3: nodes 7, 8, 9 - visible at epoch 15

            // Each document's chunks occupy a contiguous ascending block of node IDs, so
            // each is one checkpoint keyed on the block's first node ID.
            long segmentId = 0L;
            validator.registerNodeRange(segmentId, 1L, 5L);
            validator.registerNodeRange(segmentId, 4L, 10L);
            validator.registerNodeRange(segmentId, 7L, 15L);

            // Query at epoch 12 should see documents 1 and 2 only
            List<HnswSearchResult> results = new ArrayList<>();
            for (int i = 1; i <= 9; i++) {
                results.add(new HnswSearchResult(i, i * 0.1f));
            }

            List<HnswSearchResult> filtered = validator.filterResults(segmentId, results, 12L);

            // Should see all 6 nodes from documents 1 and 2
            assertThat(filtered).hasSize(6);
            assertThat(filtered.stream().map(HnswSearchResult::nodeId))
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L);

            // Validate no violations
            EpochIsolationValidator.ValidationResult validation =
                validator.validateResults(segmentId, filtered, 12L);
            assertThat(validation.valid()).isTrue();
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        /**
         * Test: Empty results should validate as valid.
         */
        @Test
        @DisplayName("empty results validate as valid")
        void emptyResultsValidateAsValid() {
            EpochIsolationValidator.ValidationResult validation =
                validator.validateResults(SEGMENT, List.of(), 10L);

            assertThat(validation.valid()).isTrue();
            assertThat(validation.totalChecked()).isEqualTo(0);
            assertThat(validation.violations()).isEqualTo(0);
        }

        /**
         * Test: Null results should validate as valid.
         */
        @Test
        @DisplayName("null results validate as valid")
        void nullResultsValidateAsValid() {
            EpochIsolationValidator.ValidationResult validation =
                validator.validateResults(SEGMENT, null, 10L);

            assertThat(validation.valid()).isTrue();
        }

        /**
         * Test: Unregistered nodes should be visible (backward compatibility).
         */
        @Test
        @DisplayName("unregistered nodes are visible for backward compatibility")
        void unregisteredNodesAreVisible() {
            // Don't register node 999
            assertThat(validator.isNodeVisible(SEGMENT, 999L, 0L)).isTrue();
            assertThat(validator.isNodeVisible(SEGMENT, 999L, 1000L)).isTrue();

            // Results with unregistered nodes should be included
            List<HnswSearchResult> results = List.of(new HnswSearchResult(999L, 0.5f));
            List<HnswSearchResult> filtered = validator.filterResults(SEGMENT, results, 10L);

            assertThat(filtered).hasSize(1);
            assertThat(filtered.get(0).nodeId()).isEqualTo(999L);
        }

        /**
         * Test: Large epoch values should work correctly.
         */
        @Test
        @DisplayName("handles large epoch values")
        void handlesLargeEpochValues() {
            long largeEpoch = Long.MAX_VALUE - 100;

            validator.registerNodeRange(SEGMENT, 1L, largeEpoch);

            assertThat(validator.isNodeVisible(SEGMENT, 1L, largeEpoch)).isTrue();
            assertThat(validator.isNodeVisible(SEGMENT, 1L, largeEpoch + 1)).isTrue();
            assertThat(validator.isNodeVisible(SEGMENT, 1L, largeEpoch - 1)).isFalse();
        }
    }
}
