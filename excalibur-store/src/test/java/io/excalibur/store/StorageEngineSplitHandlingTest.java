package io.excalibur.store;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for StorageEngine split handling and segment management.
 * Covers methods with low coverage: single-arg constructor, replaceSegment,
 * HNSW initializer methods, and lifecycle state transitions.
 */
@DisplayName("StorageEngine Split Handling Tests")
class StorageEngineSplitHandlingTest {

    @TempDir
    Path tempDir;

    private Path dataDir;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = tempDir.resolve("storage-split-test");
        Files.createDirectories(dataDir);
    }

    @Nested
    @DisplayName("Single-Argument Constructor")
    class SingleArgumentConstructorTests {

        @Test
        @DisplayName("Should create StorageEngine with Path only")
        void shouldCreateStorageEngineWithPathOnly() throws IOException {
            StorageEngine engine = new StorageEngine(dataDir);
            try {
                assertThat(engine).isNotNull();
                assertThat(engine.dataDir()).isEqualTo(dataDir);
                engine.start();
                assertThat(engine.isClosed()).isFalse();
            } finally {
                engine.close();
            }
        }

        @Test
        @DisplayName("Should use shared arena when created with Path only")
        void shouldUseSharedArenaWhenCreatedWithPathOnly() throws IOException {
            StorageEngine engine = new StorageEngine(dataDir);
            try {
                assertThat(engine.arena()).isNotNull();
            } finally {
                engine.close();
            }
        }

        @Test
        @DisplayName("Should perform put/get operations with Path-only constructor")
        void shouldPerformPutGetOperationsWithPathOnlyConstructor() throws IOException {
            StorageEngine engine = new StorageEngine(dataDir);
            try {
                engine.start();
                byte[] key = "test-key".getBytes();
                byte[] value = "test-value".getBytes();

                engine.put(key, value);
                MemorySegment result = engine.get(key);

                assertThat(result).isNotNull();
            } finally {
                engine.close();
            }
        }

        @Test
        @DisplayName("Should handle multiple operations with Path-only constructor")
        void shouldHandleMultipleOperationsWithPathOnlyConstructor() throws IOException {
            StorageEngine engine = new StorageEngine(dataDir);
            try {
                engine.start();

                // Put multiple entries
                for (int i = 0; i < 50; i++) {
                    engine.put(("key" + i).getBytes(), ("value" + i).getBytes());
                }

                // Verify all entries
                for (int i = 0; i < 50; i++) {
                    MemorySegment result = engine.get(("key" + i).getBytes());
                    assertThat(result).isNotNull();
                }

                // Delete some entries
                for (int i = 0; i < 25; i++) {
                    boolean deleted = engine.delete(("key" + i).getBytes());
                    assertThat(deleted).isTrue();
                }

                // Verify deleted entries are gone
                for (int i = 0; i < 25; i++) {
                    MemorySegment result = engine.get(("key" + i).getBytes());
                    assertThat(result).isNull();
                }

                // Verify remaining entries still exist
                for (int i = 25; i < 50; i++) {
                    MemorySegment result = engine.get(("key" + i).getBytes());
                    assertThat(result).isNotNull();
                }
            } finally {
                engine.close();
            }
        }
    }

    @Nested
    @DisplayName("HNSW Initializer")
    class HnswInitializerTests {

        @Test
        @DisplayName("Should set HNSW initializer")
        void shouldSetHnswInitializer() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena);
                try {
                    engine.start();

                    // Create a basic HnswInitializer (2-arg constructor)
                    HnswInitializer initializer = new HnswInitializer(
                        io.excalibur.vector.hnsw.HnswConfigFactory.defaults(),
                        io.excalibur.vector.distance.CosineSimilarity.getInstance()
                    );

                    assertThatCode(() -> engine.setHnswInitializer(initializer))
                        .doesNotThrowAnyException();
                } finally {
                    engine.close();
                }
            }
        }

        @Test
        @DisplayName("Should get HNSW initializer after setting")
        void shouldGetHnswInitializerAfterSetting() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena);
                try {
                    engine.start();

                    HnswInitializer initializer = new HnswInitializer(
                        io.excalibur.vector.hnsw.HnswConfigFactory.defaults(),
                        io.excalibur.vector.distance.CosineSimilarity.getInstance()
                    );
                    engine.setHnswInitializer(initializer);

                    HnswInitializer retrieved = engine.getHnswInitializer();

                    assertThat(retrieved).isNotNull();
                    assertThat(retrieved).isSameAs(initializer);
                } finally {
                    engine.close();
                }
            }
        }

        @Test
        @DisplayName("Should return null HNSW initializer when not set")
        void shouldReturnNullHnswInitializerWhenNotSet() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena);
                try {
                    engine.start();

                    HnswInitializer retrieved = engine.getHnswInitializer();

                    assertThat(retrieved).isNull();
                } finally {
                    engine.close();
                }
            }
        }
    }

    @Nested
    @DisplayName("Segment Replacement")
    class SegmentReplacementTests {

        @Test
        @DisplayName("Should replace segment atomically when old segment matches")
        void shouldReplaceSegmentAtomicallyWhenOldSegmentMatches() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena, 4);
                try {
                    engine.start();

                    // Get a segment
                    long segmentIndex = 0;
                    Segment oldSegment = engine.getSegment(segmentIndex);
                    assertThat(oldSegment).isNotNull();

                    // Create a new segment file path
                    Path newSegmentPath = dataDir.resolve("segment_" + segmentIndex + "_new.dat");

                    // Create a new segment
                    Segment newSegment = new Segment(newSegmentPath, 1_000_000L, arena);

                    // Replace should succeed since we have the correct old segment
                    boolean replaced = engine.replaceSegment(segmentIndex, oldSegment, newSegment);

                    assertThat(replaced).isTrue();
                } finally {
                    engine.close();
                }
            }
        }

        @Test
        @DisplayName("Should fail replacement if old segment mismatched")
        void shouldFailReplacementIfOldSegmentMismatched() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena, 4);
                try {
                    engine.start();

                    long segmentIndex = 0;

                    // Create a fake "old" segment that's not in the registry
                    Path fakeOldPath = dataDir.resolve("fake_old_segment.dat");
                    Segment fakeOldSegment = new Segment(fakeOldPath, 1_000_000L, arena);

                    Path newPath = dataDir.resolve("new_segment.dat");
                    Segment newSegment = new Segment(newPath, 1_000_000L, arena);

                    // Replace should fail because fakeOldSegment != actual registered segment
                    boolean replaced = engine.replaceSegment(segmentIndex, fakeOldSegment, newSegment);

                    assertThat(replaced).isFalse();
                } finally {
                    engine.close();
                }
            }
        }

        @Test
        @DisplayName("Should get segment after successful replacement")
        void shouldGetSegmentAfterSuccessfulReplacement() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena, 4);
                try {
                    engine.start();

                    long segmentIndex = 0;
                    Segment oldSegment = engine.getSegment(segmentIndex);

                    // Put some data in the old segment
                    byte[] key = "test-key".getBytes();
                    byte[] value = "test-value".getBytes();
                    engine.put(key, value);

                    // Create and replace with new segment
                    Path newSegmentPath = dataDir.resolve("segment_" + segmentIndex + "_replacement.dat");
                    Segment newSegment = new Segment(newSegmentPath, 1_000_000L, arena);

                    boolean replaced = engine.replaceSegment(segmentIndex, oldSegment, newSegment);
                    assertThat(replaced).isTrue();

                    // Verify we can get the new segment
                    Segment retrievedSegment = engine.getSegment(segmentIndex);
                    assertThat(retrievedSegment).isSameAs(newSegment);
                } finally {
                    engine.close();
                }
            }
        }
    }

    @Nested
    @DisplayName("Segment Pre-Allocation")
    class SegmentPreAllocationTests {

        @Test
        @DisplayName("Should pre-allocate segment at index")
        void shouldPreAllocateSegmentAtIndex() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena, 4);
                try {
                    engine.start();

                    int initialCount = engine.getSegmentCount();

                    // Pre-allocate a segment at a much higher index (beyond current count)
                    // This should add exactly one new segment to the registry
                    int newIndex = initialCount + 10;
                    engine.preAllocateSegment(newIndex);

                    // Physical segment count (in the map) should increase by 1.
                    // Note: getSegmentCount() returns the routing count (power-of-2),
                    // which doesn't change when adding segments at arbitrary indices.
                    assertThat(engine.getSegments().size()).isEqualTo(initialCount + 1);

                    // The new segment should be accessible
                    assertThat(engine.getSegment(newIndex)).isNotNull();
                } finally {
                    engine.close();
                }
            }
        }

        @Test
        @DisplayName("Should allow pre-allocation of existing segment index")
        void shouldAllowPreAllocationOfExistingSegmentIndex() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena, 4);
                try {
                    engine.start();

                    // Pre-allocate index 0 (should already exist)
                    assertThatCode(() -> engine.preAllocateSegment(0))
                        .doesNotThrowAnyException();
                } finally {
                    engine.close();
                }
            }
        }

        @Test
        @DisplayName("Should be able to get pre-allocated segment")
        void shouldBeAbleToGetPreAllocatedSegment() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena, 4);
                try {
                    engine.start();

                    // Pre-allocate a segment at index 20
                    int newSegmentIndex = 20;
                    engine.preAllocateSegment(newSegmentIndex);

                    // Should be able to get the segment
                    Segment segment = engine.getSegment(newSegmentIndex);
                    assertThat(segment).isNotNull();
                } finally {
                    engine.close();
                }
            }
        }
    }

    @Nested
    @DisplayName("Tombstone Segment")
    class TombstoneSegmentTests {

        @Test
        @DisplayName("Should tombstone segment")
        void shouldTombstoneSegment() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena, 4);
                try {
                    engine.start();

                    // Put some data first
                    engine.put("key1".getBytes(), "value1".getBytes());

                    // Tombstone the segment
                    assertThatCode(() -> engine.tombstoneSegment(0))
                        .doesNotThrowAnyException();

                    // Segment should be in TOMBSTONED state
                    LifecycleState state = engine.getSegmentLifecycleState(0);
                    assertThat(state).isEqualTo(LifecycleState.TOMBSTONED);
                } finally {
                    engine.close();
                }
            }
        }

        @Test
        @DisplayName("Tombstoned segment should not be queryable")
        void tombstonedSegmentShouldNotBeQueryable() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena, 4);
                try {
                    engine.start();

                    // Put data
                    engine.put("key1".getBytes(), "value1".getBytes());

                    // Get initial queryable segments count
                    int initialQueryable = engine.getQueryableSegments().size();

                    // Tombstone segment 0
                    engine.tombstoneSegment(0);

                    // Queryable segments should decrease
                    int newQueryable = engine.getQueryableSegments().size();
                    assertThat(newQueryable).isLessThan(initialQueryable);
                } finally {
                    engine.close();
                }
            }
        }
    }

    @Nested
    @DisplayName("Split Worker Access")
    class SplitWorkerAccessTests {

        @Test
        @DisplayName("Should get split worker after start")
        void shouldGetSplitWorkerAfterStart() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena);
                try {
                    engine.start();

                    SplitWorker worker = engine.getSplitWorker();

                    assertThat(worker).isNotNull();
                } finally {
                    engine.close();
                }
            }
        }
    }

    @Nested
    @DisplayName("Segment Lifecycle Completion")
    class SegmentLifecycleCompletionTests {

        @Test
        @DisplayName("Should complete segment indexing")
        void shouldCompleteSegmentIndexing() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena, 4);
                try {
                    engine.start();

                    assertThatCode(() -> engine.completeSegmentIndexing(0))
                        .doesNotThrowAnyException();
                } finally {
                    engine.close();
                }
            }
        }

        @Test
        @DisplayName("Should complete segment warmup")
        void shouldCompleteSegmentWarmup() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena, 4);
                try {
                    engine.start();

                    assertThatCode(() -> engine.completeSegmentWarmup(0))
                        .doesNotThrowAnyException();
                } finally {
                    engine.close();
                }
            }
        }

        @Test
        @DisplayName("Should begin segment compaction")
        void shouldBeginSegmentCompaction() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena, 4);
                try {
                    engine.start();

                    // First transition to ACTIVE state (COMPACTING can only be reached from ACTIVE)
                    engine.transitionSegmentState(0, LifecycleState.ACTIVE);

                    // Now we can begin compaction
                    assertThatCode(() -> engine.beginSegmentCompaction(0))
                        .doesNotThrowAnyException();
                } finally {
                    engine.close();
                }
            }
        }

        @Test
        @DisplayName("Should complete segment compaction")
        void shouldCompleteSegmentCompaction() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena, 4);
                try {
                    engine.start();

                    assertThatCode(() -> engine.completeSegmentCompaction(0))
                        .doesNotThrowAnyException();
                } finally {
                    engine.close();
                }
            }
        }
    }

    @Nested
    @DisplayName("Lifecycle State Transitions")
    class LifecycleStateTransitionTests {

        @Test
        @DisplayName("Should transition segment to INDEXING state")
        void shouldTransitionSegmentToIndexingState() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena, 4);
                try {
                    engine.start();

                    engine.transitionSegmentState(0, LifecycleState.INDEXING);

                    LifecycleState state = engine.getSegmentLifecycleState(0);
                    assertThat(state).isEqualTo(LifecycleState.INDEXING);
                } finally {
                    engine.close();
                }
            }
        }

        @Test
        @DisplayName("Should transition segment through lifecycle states")
        void shouldTransitionSegmentThroughLifecycleStates() throws IOException {
            try (Arena arena = Arena.ofShared()) {
                StorageEngine engine = new StorageEngine(dataDir, arena, 4);
                try {
                    engine.start();

                    long segmentId = 0;

                    // INDEXING
                    engine.transitionSegmentState(segmentId, LifecycleState.INDEXING);
                    assertThat(engine.getSegmentLifecycleState(segmentId)).isEqualTo(LifecycleState.INDEXING);

                    // WARMING
                    engine.transitionSegmentState(segmentId, LifecycleState.WARMING);
                    assertThat(engine.getSegmentLifecycleState(segmentId)).isEqualTo(LifecycleState.WARMING);

                    // ACTIVE
                    engine.transitionSegmentState(segmentId, LifecycleState.ACTIVE);
                    assertThat(engine.getSegmentLifecycleState(segmentId)).isEqualTo(LifecycleState.ACTIVE);

                    // COMPACTING
                    engine.transitionSegmentState(segmentId, LifecycleState.COMPACTING);
                    assertThat(engine.getSegmentLifecycleState(segmentId)).isEqualTo(LifecycleState.COMPACTING);

                    // Back to ACTIVE
                    engine.transitionSegmentState(segmentId, LifecycleState.ACTIVE);
                    assertThat(engine.getSegmentLifecycleState(segmentId)).isEqualTo(LifecycleState.ACTIVE);
                } finally {
                    engine.close();
                }
            }
        }
    }
}
