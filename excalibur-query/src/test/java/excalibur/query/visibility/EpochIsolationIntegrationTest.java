package excalibur.query.visibility;

import io.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
            // Setup: Register nodes at different epochs
            validator.registerNode(1L, 5L);   // Visible at epoch 5
            validator.registerNode(2L, 8L);   // Visible at epoch 8
            validator.registerNode(3L, 12L);  // Visible at epoch 12 (AFTER query epoch)
            validator.registerNode(4L, 15L);  // Visible at epoch 15 (AFTER query epoch)

            // Simulate query at epoch 10
            long queryEpoch = 10L;

            // Create results from all nodes
            List<HnswSearchResult> allResults = new ArrayList<>();
            allResults.add(new HnswSearchResult(1L, 0.1f));
            allResults.add(new HnswSearchResult(2L, 0.2f));
            allResults.add(new HnswSearchResult(3L, 0.3f));
            allResults.add(new HnswSearchResult(4L, 0.4f));

            // Filter results to only include nodes visible at query epoch
            List<HnswSearchResult> filtered = validator.filterResults(allResults, queryEpoch);

            // Verify: Only nodes 1 and 2 should be visible at epoch 10
            assertThat(filtered).hasSize(2);
            assertThat(filtered.stream().map(HnswSearchResult::nodeId))
                .containsExactlyInAnyOrder(1L, 2L);

            // Validate no epoch isolation violations
            EpochIsolationValidator.ValidationResult validation =
                validator.validateResults(filtered, queryEpoch);
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

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch writersDone = new CountDownLatch(numWriterThreads);
            AtomicInteger currentEpoch = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(numWriterThreads + numReaderThreads);

            // Writer threads: Register nodes at increasing epochs
            for (int t = 0; t < numWriterThreads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < nodesPerWriter; i++) {
                            long nodeId = threadId * 1000L + i;
                            int epoch = currentEpoch.incrementAndGet();
                            validator.registerNode(nodeId, epoch);
                            Thread.yield();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        writersDone.countDown();
                    }
                });
            }

            // Reader threads: Query at various epochs and verify isolation
            for (int t = 0; t < numReaderThreads; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        while (writersDone.getCount() > 0) {
                            // Capture a query epoch
                            int queryEpoch = currentEpoch.get();

                            // Create results from some nodes
                            List<HnswSearchResult> results = new ArrayList<>();
                            for (int i = 0; i < 10; i++) {
                                results.add(new HnswSearchResult(i * 100L, i * 0.1f));
                            }

                            // Filter and validate
                            List<HnswSearchResult> filtered = validator.filterResults(results, queryEpoch);
                            EpochIsolationValidator.ValidationResult validation =
                                validator.validateResults(filtered, queryEpoch);

                            totalReads.incrementAndGet();
                            if (!validation.valid()) {
                                violationCount.addAndGet(validation.violations());
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

            // Verify no violations
            assertThat(violationCount.get())
                .as("No epoch isolation violations should occur during concurrent operations")
                .isEqualTo(0);

            assertThat(totalReads.get())
                .as("Should have performed reads during concurrent operations")
                .isGreaterThan(0);

            System.out.println("Concurrency test: " + totalReads.get() + " reads, " +
                violationCount.get() + " violations");
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

            validator.registerNode(nodeId, visibleEpoch);

            // Not visible before
            assertThat(validator.isNodeVisible(nodeId, 99L)).isFalse();

            // Visible at exact epoch
            assertThat(validator.isNodeVisible(nodeId, 100L)).isTrue();

            // Visible after
            assertThat(validator.isNodeVisible(nodeId, 101L)).isTrue();
            assertThat(validator.isNodeVisible(nodeId, 1000L)).isTrue();
        }

        /**
         * Test: Verify epoch isolation at epoch 0 boundary.
         */
        @Test
        @DisplayName("epoch zero boundary works correctly")
        void epochZeroBoundary() {
            validator.registerNode(1L, 0L);
            validator.registerNode(2L, 1L);

            // Query at epoch 0 should only see node 1
            List<HnswSearchResult> results = List.of(
                new HnswSearchResult(1L, 0.1f),
                new HnswSearchResult(2L, 0.2f)
            );

            List<HnswSearchResult> filtered0 = validator.filterResults(results, 0L);
            assertThat(filtered0).hasSize(1);
            assertThat(filtered0.get(0).nodeId()).isEqualTo(1L);

            // Query at epoch 1 should see both nodes
            List<HnswSearchResult> filtered1 = validator.filterResults(results, 1L);
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
                validator.registerNode(i, i * 5L);
            }

            // Create results mixing epochs
            List<HnswSearchResult> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                results.add(new HnswSearchResult(i, i * 0.05f));
            }

            // Query at epoch 50 should only see nodes 0-10 (epochs 0, 5, 10, ..., 50)
            long queryEpoch = 50L;
            List<HnswSearchResult> filtered = validator.filterResults(results, queryEpoch);

            // Nodes 0-10 have epochs 0, 5, 10, ..., 50 (all <= 50)
            // Nodes 11-19 have epochs 55, 60, ..., 95 (all > 50)
            assertThat(filtered).hasSize(11);

            // Validate the filtered results
            EpochIsolationValidator.ValidationResult validation =
                validator.validateResults(filtered, queryEpoch);
            assertThat(validation.valid()).isTrue();

            // Validate the unfiltered results (should have violations)
            EpochIsolationValidator.ValidationResult unfilteredValidation =
                validator.validateResults(results, queryEpoch);
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
                validator.registerNode(i, 10L);
            }

            // Create results sorted by distance
            List<HnswSearchResult> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                results.add(new HnswSearchResult(i, i * 0.1f)); // distances: 0.0, 0.1, 0.2, ...
            }

            // Filter at epoch 15 (all should be visible)
            List<HnswSearchResult> filtered = validator.filterResults(results, 15L);

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
                validator.registerNode(i, i % 2 == 0 ? 5L : 15L);
            }

            // Create results sorted by distance
            List<HnswSearchResult> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                results.add(new HnswSearchResult(i, i * 0.1f));
            }

            // Query at epoch 10 (only even nodes visible)
            List<HnswSearchResult> filtered = validator.filterResults(results, 10L);

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

            validator.registerNodes(Set.of(1L, 2L, 3L), 5L);
            validator.registerNodes(Set.of(4L, 5L, 6L), 10L);
            validator.registerNodes(Set.of(7L, 8L, 9L), 15L);

            // Query at epoch 12 should see documents 1 and 2 only
            List<HnswSearchResult> results = new ArrayList<>();
            for (int i = 1; i <= 9; i++) {
                results.add(new HnswSearchResult(i, i * 0.1f));
            }

            List<HnswSearchResult> filtered = validator.filterResults(results, 12L);

            // Should see all 6 nodes from documents 1 and 2
            assertThat(filtered).hasSize(6);
            assertThat(filtered.stream().map(HnswSearchResult::nodeId))
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L);

            // Validate no violations
            EpochIsolationValidator.ValidationResult validation =
                validator.validateResults(filtered, 12L);
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
                validator.validateResults(List.of(), 10L);

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
                validator.validateResults(null, 10L);

            assertThat(validation.valid()).isTrue();
        }

        /**
         * Test: Unregistered nodes should be visible (backward compatibility).
         */
        @Test
        @DisplayName("unregistered nodes are visible for backward compatibility")
        void unregisteredNodesAreVisible() {
            // Don't register node 999
            assertThat(validator.isNodeVisible(999L, 0L)).isTrue();
            assertThat(validator.isNodeVisible(999L, 1000L)).isTrue();

            // Results with unregistered nodes should be included
            List<HnswSearchResult> results = List.of(new HnswSearchResult(999L, 0.5f));
            List<HnswSearchResult> filtered = validator.filterResults(results, 10L);

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

            validator.registerNode(1L, largeEpoch);

            assertThat(validator.isNodeVisible(1L, largeEpoch)).isTrue();
            assertThat(validator.isNodeVisible(1L, largeEpoch + 1)).isTrue();
            assertThat(validator.isNodeVisible(1L, largeEpoch - 1)).isFalse();
        }
    }
}
