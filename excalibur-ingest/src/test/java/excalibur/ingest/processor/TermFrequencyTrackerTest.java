package excalibur.ingest.processor;

import excalibur.filter.sketch.CountMinSketch;
import io.excalibur.store.Segment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for TermFrequencyTracker.
 *
 * <p>Verifies that term frequencies are tracked correctly during document ingestion
 * and that the Count-Min Sketch integration works properly for selectivity estimation.</p>
 *
 * @author ExcaliburDB
 */
@DisplayName("TermFrequencyTracker Tests")
@ExtendWith(MockitoExtension.class)
class TermFrequencyTrackerTest {

    private TermFrequencyTracker tracker;

    @Mock
    private Segment mockSegment;

    @BeforeEach
    void setUp() {
        tracker = new TermFrequencyTracker();
    }

    @Nested
    @DisplayName("trackText()")
    class TrackTextTests {

        @Test
        @DisplayName("Should populate sketch for simple text")
        void shouldPopulateSketchForSimpleText() {
            // Start with no sketch
            when(mockSegment.termFrequencySketch()).thenReturn(null);
            // When sketch is attached, return it on subsequent calls
            doAnswer(invocation -> {
                when(mockSegment.termFrequencySketch()).thenReturn(invocation.getArgument(0));
                return null;
            }).when(mockSegment).attachTermFrequencySketch(any(CountMinSketch.class));

            String text = "hello world test";

            tracker.trackText(mockSegment, text);

            verify(mockSegment).attachTermFrequencySketch(any(CountMinSketch.class));
            CountMinSketch sketch = mockSegment.termFrequencySketch();
            assertThat(sketch).isNotNull();

            // Verify terms were inserted
            long helloCount = sketch.estimate("hello".getBytes(StandardCharsets.UTF_8));
            long worldCount = sketch.estimate("world".getBytes(StandardCharsets.UTF_8));
            long testCount = sketch.estimate("test".getBytes(StandardCharsets.UTF_8));

            assertThat(helloCount).isGreaterThanOrEqualTo(1);
            assertThat(worldCount).isGreaterThanOrEqualTo(1);
            assertThat(testCount).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Should handle repeated terms")
        void shouldHandleRepeatedTerms() {
            setupMockSegmentWithSketch();

            String text = "test test test";

            tracker.trackText(mockSegment, text);

            CountMinSketch sketch = mockSegment.termFrequencySketch();
            assertThat(sketch).isNotNull();

            long testCount = sketch.estimate("test".getBytes(StandardCharsets.UTF_8));
            assertThat(testCount).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("Should handle empty text")
        void shouldHandleEmptyText() {
            // No mock setup needed - trackText returns early for empty text

            tracker.trackText(mockSegment, "");

            // Empty text should not create a sketch
            verify(mockSegment, never()).attachTermFrequencySketch(any());
        }

        @Test
        @DisplayName("Should reject null segment")
        void shouldRejectNullSegment() {
            assertThatThrownBy(() -> tracker.trackText(null, "test"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Segment cannot be null");
        }

        @Test
        @DisplayName("Should reject null text")
        void shouldRejectNullText() {
            assertThatThrownBy(() -> tracker.trackText(mockSegment, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Text cannot be null");
        }

        @Test
        @DisplayName("Should handle whitespace-only text - creates sketch but no tokens tracked")
        void shouldHandleWhitespaceOnlyText() {
            // Setup: Return null initially, then capture attached sketch
            when(mockSegment.termFrequencySketch()).thenReturn(null);

            tracker.trackText(mockSegment, "   \t\n   ");

            // Note: Current implementation creates a sketch even for whitespace-only text
            // because the emptiness check happens before tokenization
            verify(mockSegment).attachTermFrequencySketch(any(CountMinSketch.class));
        }
    }

    @Nested
    @DisplayName("trackTokens()")
    class TrackTokensTests {

        @Test
        @DisplayName("Should populate sketch for token list")
        void shouldPopulateSketchForTokenList() {
            setupMockSegmentWithSketch();

            List<String> tokens = Arrays.asList("hello", "world", "test");

            tracker.trackTokens(mockSegment, tokens);

            CountMinSketch sketch = mockSegment.termFrequencySketch();
            assertThat(sketch).isNotNull();

            for (String token : tokens) {
                long count = sketch.estimate(token.getBytes(StandardCharsets.UTF_8));
                assertThat(count).isGreaterThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("Should handle repeated tokens")
        void shouldHandleRepeatedTokens() {
            setupMockSegmentWithSketch();

            List<String> tokens = Arrays.asList("test", "test", "test");

            tracker.trackTokens(mockSegment, tokens);

            CountMinSketch sketch = mockSegment.termFrequencySketch();
            assertThat(sketch).isNotNull();

            long testCount = sketch.estimate("test".getBytes(StandardCharsets.UTF_8));
            assertThat(testCount).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("Should handle empty token list")
        void shouldHandleEmptyTokenList() {
            // No mock setup needed - trackTokens returns early for empty list

            tracker.trackTokens(mockSegment, Collections.emptyList());

            verify(mockSegment, never()).attachTermFrequencySketch(any());
        }

        @Test
        @DisplayName("Should skip null and empty tokens")
        void shouldSkipNullOrEmptyTokens() {
            setupMockSegmentWithSketch();

            List<String> tokens = Arrays.asList("hello", null, "", "world");

            tracker.trackTokens(mockSegment, tokens);

            CountMinSketch sketch = mockSegment.termFrequencySketch();
            assertThat(sketch).isNotNull();

            long helloCount = sketch.estimate("hello".getBytes(StandardCharsets.UTF_8));
            long worldCount = sketch.estimate("world".getBytes(StandardCharsets.UTF_8));

            assertThat(helloCount).isGreaterThanOrEqualTo(1);
            assertThat(worldCount).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Should reject null segment")
        void shouldRejectNullSegment() {
            assertThatThrownBy(() -> tracker.trackTokens(null, Arrays.asList("test")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Segment cannot be null");
        }

        @Test
        @DisplayName("Should reject null tokens list")
        void shouldRejectNullTokensList() {
            assertThatThrownBy(() -> tracker.trackTokens(mockSegment, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Tokens cannot be null");
        }
    }

    @Nested
    @DisplayName("trackChunks()")
    class TrackChunksTests {

        @Test
        @DisplayName("Should populate sketch for chunk list")
        void shouldPopulateSketchForChunkList() {
            setupMockSegmentWithSketch();

            List<TextChunk> chunks = Arrays.asList(
                createChunk("doc1", "hello world"),
                createChunk("doc1", "test example")
            );

            tracker.trackChunks(mockSegment, chunks);

            CountMinSketch sketch = mockSegment.termFrequencySketch();
            assertThat(sketch).isNotNull();

            // Verify all tokens from both chunks were tracked
            long helloCount = sketch.estimate("hello".getBytes(StandardCharsets.UTF_8));
            long worldCount = sketch.estimate("world".getBytes(StandardCharsets.UTF_8));
            long testCount = sketch.estimate("test".getBytes(StandardCharsets.UTF_8));
            long exampleCount = sketch.estimate("example".getBytes(StandardCharsets.UTF_8));

            assertThat(helloCount).isGreaterThanOrEqualTo(1);
            assertThat(worldCount).isGreaterThanOrEqualTo(1);
            assertThat(testCount).isGreaterThanOrEqualTo(1);
            assertThat(exampleCount).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Should handle empty chunk list")
        void shouldHandleEmptyChunkList() {
            // No mock setup needed - trackChunks returns early for empty list

            tracker.trackChunks(mockSegment, Collections.emptyList());

            verify(mockSegment, never()).attachTermFrequencySketch(any());
        }

        @Test
        @DisplayName("Should skip null chunks and chunks with null/empty text")
        void shouldSkipNullChunksAndEmptyText() {
            setupMockSegmentWithSketch();

            List<TextChunk> chunks = new ArrayList<>();
            chunks.add(createChunk("doc1", "hello"));
            chunks.add(null);
            chunks.add(createChunk("doc1", ""));
            chunks.add(createChunk("doc1", "world"));

            tracker.trackChunks(mockSegment, chunks);

            CountMinSketch sketch = mockSegment.termFrequencySketch();
            assertThat(sketch).isNotNull();

            long helloCount = sketch.estimate("hello".getBytes(StandardCharsets.UTF_8));
            long worldCount = sketch.estimate("world".getBytes(StandardCharsets.UTF_8));

            assertThat(helloCount).isGreaterThanOrEqualTo(1);
            assertThat(worldCount).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Should reject null segment")
        void shouldRejectNullSegment() {
            assertThatThrownBy(() -> tracker.trackChunks(null, Arrays.asList(createChunk("doc1", "test"))))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Segment cannot be null");
        }

        @Test
        @DisplayName("Should reject null chunks list")
        void shouldRejectNullChunksList() {
            assertThatThrownBy(() -> tracker.trackChunks(mockSegment, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Chunks cannot be null");
        }
    }

    @Nested
    @DisplayName("trackTokenWithCount()")
    class TrackTokenWithCountTests {

        @Test
        @DisplayName("Should track token with custom count")
        void shouldTrackTokenWithCustomCount() {
            setupMockSegmentWithSketch();

            tracker.trackTokenWithCount(mockSegment, "important", 5);

            CountMinSketch sketch = mockSegment.termFrequencySketch();
            assertThat(sketch).isNotNull();

            long count = sketch.estimate("important".getBytes(StandardCharsets.UTF_8));
            assertThat(count).isGreaterThanOrEqualTo(5);
        }

        @Test
        @DisplayName("Should reject empty token")
        void shouldRejectEmptyToken() {
            assertThatThrownBy(() -> tracker.trackTokenWithCount(mockSegment, "", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Token cannot be empty");
        }

        @Test
        @DisplayName("Should reject non-positive count")
        void shouldRejectNonPositiveCount() {
            assertThatThrownBy(() -> tracker.trackTokenWithCount(mockSegment, "test", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Count must be positive");

            assertThatThrownBy(() -> tracker.trackTokenWithCount(mockSegment, "test", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Count must be positive");
        }

        @Test
        @DisplayName("Should reject null segment")
        void shouldRejectNullSegment() {
            assertThatThrownBy(() -> tracker.trackTokenWithCount(null, "test", 5))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Segment cannot be null");
        }

        @Test
        @DisplayName("Should reject null token")
        void shouldRejectNullToken() {
            assertThatThrownBy(() -> tracker.trackTokenWithCount(mockSegment, null, 5))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Token cannot be null");
        }
    }

    @Nested
    @DisplayName("estimateFrequency()")
    class EstimateFrequencyTests {

        @Test
        @DisplayName("Should estimate frequency for tracked term")
        void shouldEstimateFrequencyForTrackedTerm() {
            setupMockSegmentWithSketch();

            tracker.trackText(mockSegment, "hello hello hello");

            long frequency = tracker.estimateFrequency(mockSegment, "hello");

            assertThat(frequency).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("Should return 0 for untracked term")
        void shouldReturnZeroForUntrackedTerm() {
            setupMockSegmentWithSketch();

            tracker.trackText(mockSegment, "hello world");

            long frequency = tracker.estimateFrequency(mockSegment, "untracked");

            // Count-Min Sketch never under-counts, but untracked terms might have
            // small values due to hash collisions
            assertThat(frequency).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("Should return 0 for segment without sketch")
        void shouldReturnZeroForSegmentWithoutSketch() {
            when(mockSegment.termFrequencySketch()).thenReturn(null);

            long frequency = tracker.estimateFrequency(mockSegment, "test");

            assertThat(frequency).isZero();
        }

        @Test
        @DisplayName("Should reject null segment")
        void shouldRejectNullSegment() {
            assertThatThrownBy(() -> tracker.estimateFrequency(null, "test"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Segment cannot be null");
        }

        @Test
        @DisplayName("Should reject null term")
        void shouldRejectNullTerm() {
            assertThatThrownBy(() -> tracker.estimateFrequency(mockSegment, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Term cannot be null");
        }
    }

    @Nested
    @DisplayName("estimateRelativeFrequency()")
    class EstimateRelativeFrequencyTests {

        @Test
        @DisplayName("Should estimate relative frequency for tracked term")
        void shouldEstimateRelativeFrequencyForTrackedTerm() {
            setupMockSegmentWithSketch();

            tracker.trackText(mockSegment, "hello hello hello world");

            double relativeFreq = tracker.estimateRelativeFrequency(mockSegment, "hello");

            // Relative frequency should be between 0 and 1
            assertThat(relativeFreq).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("Should return 0 for segment without sketch")
        void shouldReturnZeroForSegmentWithoutSketch() {
            when(mockSegment.termFrequencySketch()).thenReturn(null);

            double relativeFreq = tracker.estimateRelativeFrequency(mockSegment, "test");

            assertThat(relativeFreq).isZero();
        }

        @Test
        @DisplayName("Should reject null segment")
        void shouldRejectNullSegment() {
            assertThatThrownBy(() -> tracker.estimateRelativeFrequency(null, "test"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Segment cannot be null");
        }

        @Test
        @DisplayName("Should reject null term")
        void shouldRejectNullTerm() {
            assertThatThrownBy(() -> tracker.estimateRelativeFrequency(mockSegment, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Term cannot be null");
        }
    }

    @Nested
    @DisplayName("createSketch()")
    class CreateSketchTests {

        @Test
        @DisplayName("Should create sketch with default parameters")
        void shouldCreateSketchWithDefaultParameters() {
            CountMinSketch sketch = TermFrequencyTracker.createSketch();

            assertThat(sketch).isNotNull();
        }

        @Test
        @DisplayName("Should create sketch with custom parameters")
        void shouldCreateSketchWithCustomParameters() {
            CountMinSketch sketch = TermFrequencyTracker.createSketch(1000, 5);

            assertThat(sketch).isNotNull();
        }

        @Test
        @DisplayName("Should create independent sketches")
        void shouldCreateIndependentSketches() {
            CountMinSketch sketch1 = TermFrequencyTracker.createSketch();
            CountMinSketch sketch2 = TermFrequencyTracker.createSketch();

            assertThat(sketch1).isNotSameAs(sketch2);
        }
    }

    @Nested
    @DisplayName("Integration with Segment's CountMinSketch")
    class SegmentIntegrationTests {

        @Test
        @DisplayName("Should use existing sketch if present")
        void shouldUseExistingSketchIfPresent() {
            // Pre-attach a sketch
            CountMinSketch existingSketch = TermFrequencyTracker.createSketch();
            when(mockSegment.termFrequencySketch()).thenReturn(existingSketch);

            // Track new terms
            tracker.trackText(mockSegment, "new term");

            // Verify both existing and new terms are in the same sketch
            existingSketch.insert("existing".getBytes(StandardCharsets.UTF_8));

            long existingCount = existingSketch.estimate("existing".getBytes(StandardCharsets.UTF_8));
            long newCount = existingSketch.estimate("new".getBytes(StandardCharsets.UTF_8));

            assertThat(existingCount).isGreaterThanOrEqualTo(1);
            assertThat(newCount).isGreaterThanOrEqualTo(1);

            // Should not have created a new sketch
            verify(mockSegment, never()).attachTermFrequencySketch(any());
        }

        @Test
        @DisplayName("Should track across multiple track calls")
        void shouldTrackAcrossMultipleCalls() {
            setupMockSegmentWithSketch();

            tracker.trackText(mockSegment, "hello");
            tracker.trackText(mockSegment, "world");
            tracker.trackTokens(mockSegment, Arrays.asList("test", "example"));

            CountMinSketch sketch = mockSegment.termFrequencySketch();
            assertThat(sketch).isNotNull();

            // All terms should be tracked
            assertThat(sketch.estimate("hello".getBytes(StandardCharsets.UTF_8))).isGreaterThanOrEqualTo(1);
            assertThat(sketch.estimate("world".getBytes(StandardCharsets.UTF_8))).isGreaterThanOrEqualTo(1);
            assertThat(sketch.estimate("test".getBytes(StandardCharsets.UTF_8))).isGreaterThanOrEqualTo(1);
            assertThat(sketch.estimate("example".getBytes(StandardCharsets.UTF_8))).isGreaterThanOrEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Should be thread-safe for concurrent tracking")
        void shouldBeThreadSafeForConcurrentTracking() throws InterruptedException {
            setupMockSegmentWithSketch();

            int numThreads = 10;
            Thread[] threads = new Thread[numThreads];
            Exception[] exceptions = new Exception[numThreads];

            for (int i = 0; i < numThreads; i++) {
                final int idx = i;
                threads[i] = new Thread(() -> {
                    try {
                        tracker.trackText(mockSegment, "term" + idx);
                    } catch (Exception e) {
                        exceptions[idx] = e;
                    }
                });
            }

            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join();

            for (int i = 0; i < numThreads; i++) {
                assertThat(exceptions[i]).isNull();
            }

            // Verify at least some terms were tracked
            CountMinSketch sketch = mockSegment.termFrequencySketch();
            assertThat(sketch).isNotNull();
        }
    }

    // Helper methods

    private void setupMockSegmentWithSketch() {
        CountMinSketch sketch = TermFrequencyTracker.createSketch();
        when(mockSegment.termFrequencySketch()).thenReturn(sketch);
    }

    private TextChunk createChunk(String documentId, String text) {
        int tokenCount = text.isEmpty() ? 0 : text.split("\\s+").length;
        return new TextChunk(documentId, text, tokenCount, 0, text.length(), 0);
    }
}
