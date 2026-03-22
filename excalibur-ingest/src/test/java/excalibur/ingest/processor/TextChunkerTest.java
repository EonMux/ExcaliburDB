package excalibur.ingest.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("TextChunker Tests")
class TextChunkerTest {

    private static final String DOC_ID = "test-doc";
    private static final int DEFAULT_TARGET_TOKENS = 512;
    private static final int DEFAULT_OVERLAP_TOKENS = 64;
    private static final int CHARS_PER_TOKEN = 4;
    private static final int TARGET_CHARS = DEFAULT_TARGET_TOKENS * CHARS_PER_TOKEN;
    private static final int OVERLAP_CHARS = DEFAULT_OVERLAP_TOKENS * CHARS_PER_TOKEN;

    @Nested
    @DisplayName("Constructor Validation")
    class ConstructorTests {

        @Test
        @DisplayName("Should create with default configuration")
        void shouldCreateWithDefaults() {
            TextChunker chunker = new TextChunker();
            assertThat(chunker.getTargetTokens()).isEqualTo(512);
            assertThat(chunker.getOverlapTokens()).isEqualTo(64);
            assertThat(chunker.getTargetChars()).isEqualTo(2048);
            assertThat(chunker.getOverlapChars()).isEqualTo(256);
        }

        @Test
        @DisplayName("Should create with custom configuration")
        void shouldCreateWithCustomConfig() {
            TextChunker chunker = new TextChunker(256, 32);
            assertThat(chunker.getTargetTokens()).isEqualTo(256);
            assertThat(chunker.getOverlapTokens()).isEqualTo(32);
            assertThat(chunker.getTargetChars()).isEqualTo(1024);
            assertThat(chunker.getOverlapChars()).isEqualTo(128);
        }

        @Test
        @DisplayName("Should reject zero target tokens")
        void shouldRejectZeroTargetTokens() {
            assertThatThrownBy(() -> new TextChunker(0, 64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target tokens must be positive");
        }

        @Test
        @DisplayName("Should reject negative target tokens")
        void shouldRejectNegativeTargetTokens() {
            assertThatThrownBy(() -> new TextChunker(-1, 64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target tokens must be positive");
        }

        @Test
        @DisplayName("Should reject negative overlap tokens")
        void shouldRejectNegativeOverlapTokens() {
            assertThatThrownBy(() -> new TextChunker(512, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Overlap tokens must be non-negative");
        }

        @Test
        @DisplayName("Should reject overlap >= target tokens")
        void shouldRejectOverlapGreaterOrEqualToTarget() {
            assertThatThrownBy(() -> new TextChunker(512, 512))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Overlap tokens must be less than target tokens");

            assertThatThrownBy(() -> new TextChunker(512, 600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Overlap tokens must be less than target tokens");
        }

        @Test
        @DisplayName("Should allow zero overlap")
        void shouldAllowZeroOverlap() {
            TextChunker chunker = new TextChunker(512, 0);
            assertThat(chunker.getOverlapTokens()).isEqualTo(0);
            assertThat(chunker.getOverlapChars()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Input Validation")
    class InputValidationTests {

        @Test
        @DisplayName("Should reject null document ID")
        void shouldRejectNullDocumentId() {
            TextChunker chunker = new TextChunker();
            assertThatThrownBy(() -> chunker.chunk(null, "some text"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Document ID must not be null");
        }

        @Test
        @DisplayName("Should reject empty document ID")
        void shouldRejectEmptyDocumentId() {
            TextChunker chunker = new TextChunker();
            assertThatThrownBy(() -> chunker.chunk("", "some text"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Document ID must not be empty");
        }

        @Test
        @DisplayName("Should reject null text")
        void shouldRejectNullText() {
            TextChunker chunker = new TextChunker();
            assertThatThrownBy(() -> chunker.chunk(DOC_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Text must not be null");
        }

        @Test
        @DisplayName("Should handle empty text")
        void shouldHandleEmptyText() {
            TextChunker chunker = new TextChunker();
            List<TextChunk> chunks = chunker.chunk(DOC_ID, "");
            assertThat(chunks).isEmpty();
        }

        @Test
        @DisplayName("Should handle whitespace-only text")
        void shouldHandleWhitespaceOnlyText() {
            TextChunker chunker = new TextChunker();
            List<TextChunk> chunks = chunker.chunk(DOC_ID, "   \n\t  ");
            assertThat(chunks).isEmpty();
        }
    }

    @Nested
    @DisplayName("Basic Chunking")
    class BasicChunkingTests {

        @Test
        @DisplayName("Should create single chunk for short text")
        void shouldCreateSingleChunkForShortText() {
            TextChunker chunker = new TextChunker();
            String text = "This is a short text that fits in one chunk.";
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            assertThat(chunks).hasSize(1);
            TextChunk chunk = chunks.get(0);
            assertThat(chunk.getDocumentId()).isEqualTo(DOC_ID);
            assertThat(chunk.getText()).isEqualTo(text);
            assertThat(chunk.getChunkIndex()).isEqualTo(0);
            assertThat(chunk.getStartOffset()).isEqualTo(0);
            assertThat(chunk.getEndOffset()).isEqualTo(text.length());
        }

        @Test
        @DisplayName("Should create multiple chunks for long text")
        void shouldCreateMultipleChunksForLongText() {
            TextChunker chunker = new TextChunker();
            String text = generateText(TARGET_CHARS * 3);
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            assertThat(chunks).hasSizeGreaterThan(1);
            assertThat(chunks.get(0).getChunkIndex()).isEqualTo(0);
            assertThat(chunks.get(1).getChunkIndex()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should preserve chunk order")
        void shouldPreserveChunkOrder() {
            TextChunker chunker = new TextChunker();
            String text = generateText(TARGET_CHARS * 2);
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            for (int i = 0; i < chunks.size(); i++) {
                assertThat(chunks.get(i).getChunkIndex()).isEqualTo(i);
            }
        }

        @Test
        @DisplayName("Should have correct start/end offsets")
        void shouldHaveCorrectOffsets() {
            TextChunker chunker = new TextChunker();
            String text = generateText(TARGET_CHARS * 2);
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            for (TextChunk chunk : chunks) {
                assertThat(chunk.getStartOffset()).isLessThan(chunk.getEndOffset());
                assertThat(chunk.getEndOffset()).isLessThanOrEqualTo(text.length());
                assertThat(text.substring(chunk.getStartOffset(), chunk.getEndOffset())).isEqualTo(chunk.getText());
            }
        }
    }

    @Nested
    @DisplayName("Token Count Validation")
    class TokenCountTests {

        @Test
        @DisplayName("Chunks should be within 512 tokens ±10%")
        void chunksShouldBeWithinTargetRange() {
            TextChunker chunker = new TextChunker();
            String text = generateText(TARGET_CHARS * 5);
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            int minTokens = (int) (DEFAULT_TARGET_TOKENS * 0.90);
            int maxTokens = (int) (DEFAULT_TARGET_TOKENS * 1.10);

            for (TextChunk chunk : chunks) {
                if (chunk.getChunkIndex() < chunks.size() - 1) {
                    assertThat(chunk.getTokenCount())
                        .as("Chunk %d token count should be between %d and %d, got %d",
                            chunk.getChunkIndex(), minTokens, maxTokens, chunk.getTokenCount())
                        .isBetween(minTokens, maxTokens);
                }
            }
        }

        @Test
        @DisplayName("Last chunk can be smaller than target")
        void lastChunkCanBeSmaller() {
            TextChunker chunker = new TextChunker();
            String text = generateText(TARGET_CHARS + TARGET_CHARS / 2);
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            assertThat(chunks).hasSizeGreaterThan(1);
            TextChunk lastChunk = chunks.get(chunks.size() - 1);
            assertThat(lastChunk.getTokenCount()).isLessThanOrEqualTo(DEFAULT_TARGET_TOKENS);
        }

        @Test
        @DisplayName("Token count should match character count / 4")
        void tokenCountShouldMatchCharCount() {
            TextChunker chunker = new TextChunker();
            String text = "A".repeat(100);
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            assertThat(chunks).hasSize(1);
            int expectedTokens = (100 + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
            assertThat(chunks.get(0).getTokenCount()).isEqualTo(expectedTokens);
        }
    }

    @Nested
    @DisplayName("Overlap Validation")
    class OverlapTests {

        @Test
        @DisplayName("Consecutive chunks should have overlap")
        void consecutiveChunksShouldHaveOverlap() {
            TextChunker chunker = new TextChunker();
            String text = generateText(TARGET_CHARS * 3);
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            for (int i = 1; i < chunks.size(); i++) {
                TextChunk prev = chunks.get(i - 1);
                TextChunk curr = chunks.get(i);

                int actualOverlap = prev.getEndOffset() - curr.getStartOffset();
                int minExpectedOverlap = (int) (OVERLAP_CHARS * 0.8);
                
                assertThat(actualOverlap)
                    .as("Overlap between chunk %d and %d should be at least %d chars, got %d",
                        i - 1, i, minExpectedOverlap, actualOverlap)
                    .isGreaterThanOrEqualTo(minExpectedOverlap);
            }
        }

        @Test
        @DisplayName("Overlap content should match")
        void overlapContentShouldMatch() {
            TextChunker chunker = new TextChunker();
            String text = generateText(TARGET_CHARS * 3);
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            for (int i = 1; i < chunks.size(); i++) {
                TextChunk prev = chunks.get(i - 1);
                TextChunk curr = chunks.get(i);

                int overlapStart = curr.getStartOffset();
                int overlapEnd = prev.getEndOffset();
                
                if (overlapStart < overlapEnd) {
                    String overlapInPrev = prev.getText().substring(prev.getText().length() - (overlapEnd - overlapStart));
                    String overlapInCurr = curr.getText().substring(0, overlapEnd - overlapStart);
                    
                    assertThat(overlapInPrev)
                        .as("Overlap content between consecutive chunks should match")
                        .isEqualTo(overlapInCurr);
                }
            }
        }

        @Test
        @DisplayName("No overlap when overlap is zero")
        void noOverlapWhenZero() {
            TextChunker chunker = new TextChunker(512, 0);
            String text = generateText(TARGET_CHARS * 2);
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            for (int i = 1; i < chunks.size(); i++) {
                TextChunk prev = chunks.get(i - 1);
                TextChunk curr = chunks.get(i);
                
                assertThat(curr.getStartOffset())
                    .as("With zero overlap, next chunk should start at or after previous end")
                    .isGreaterThanOrEqualTo(prev.getEndOffset());
            }
        }
    }

    @Nested
    @DisplayName("Sentence Boundary Awareness")
    class SentenceBoundaryTests {

        @Test
        @DisplayName("Should prefer sentence boundaries")
        void shouldPreferSentenceBoundaries() {
            TextChunker chunker = new TextChunker();
            
            StringBuilder sb = new StringBuilder();
            sb.append("A".repeat(TARGET_CHARS - 100));
            sb.append(". This is a new sentence. ");
            sb.append("B".repeat(200));
            String text = sb.toString();

            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            for (TextChunk chunk : chunks) {
                if (chunk.getChunkIndex() < chunks.size() - 1) {
                    String chunkText = chunk.getText();
                    assertThat(chunkText)
                        .as("Non-final chunks should end at sentence boundary when possible")
                        .matches(".*[.!?]\\s*$");
                }
            }
        }

        @Test
        @DisplayName("Should recognize period as sentence boundary")
        void shouldRecognizePeriod() {
            TextChunker chunker = new TextChunker();
            String text = "First sentence. Second sentence. Third sentence.";
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);
            
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("Should recognize exclamation as sentence boundary")
        void shouldRecognizeExclamation() {
            TextChunker chunker = new TextChunker();
            String text = "First sentence! Second sentence! Third sentence!";
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);
            
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("Should recognize question mark as sentence boundary")
        void shouldRecognizeQuestionMark() {
            TextChunker chunker = new TextChunker();
            String text = "First sentence? Second sentence? Third sentence?";
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);
            
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("Should recognize punctuation at end of string")
        void shouldRecognizePunctuationAtEndOfString() {
            TextChunker chunker = new TextChunker(100, 10);
            String text = "This is a sentence.";
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);
            
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).getText()).isEqualTo("This is a sentence.");
        }

        @Test
        @DisplayName("Should recognize punctuation followed by newline")
        void shouldRecognizePunctuationFollowedByNewline() {
            TextChunker chunker = new TextChunker(100, 10);
            String text = "First sentence.\nSecond sentence.\nThird sentence.";
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);
            
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("Should recognize punctuation followed by tab")
        void shouldRecognizePunctuationFollowedByTab() {
            TextChunker chunker = new TextChunker(100, 10);
            String text = "First sentence.\tSecond sentence.\tThird sentence.";
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);
            
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("Should NOT treat period without space as sentence boundary")
        void shouldNotTreatPeriodWithoutSpaceAsBoundary() {
            TextChunker chunker = new TextChunker(100, 10);
            String text = "Hello.World.This.Is.Connected";
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);
            
            assertThat(chunks).hasSize(1);
        }

        @Test
        @DisplayName("Should handle mixed sentence endings")
        void shouldHandleMixedSentenceEndings() {
            TextChunker chunker = new TextChunker(100, 10);
            String text = "Statement. Question? Exclamation! Another statement.";
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);
            
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("Should handle multiple punctuation marks")
        void shouldHandleMultiplePunctuationMarks() {
            TextChunker chunker = new TextChunker(100, 10);
            String text = "Really?! Yes... No!!! Why???";
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);
            
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("Should split long text at sentence boundaries when possible")
        void shouldSplitAtSentenceBoundariesWhenPossible() {
            TextChunker chunker = new TextChunker(50, 5);
            
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                sb.append("This is sentence number ").append(i).append(". ");
            }
            String text = sb.toString().trim();
            
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);
            
            assertThat(chunks.size()).isGreaterThan(1);
            
            for (int i = 0; i < chunks.size() - 1; i++) {
                String chunkText = chunks.get(i).getText();
                assertThat(chunkText)
                    .as("Non-final chunks should end at sentence boundary")
                    .matches(".*[.!?]\\s*$");
            }
        }
    }

    @Nested
    @DisplayName("Word Boundary Awareness")
    class WordBoundaryTests {

        @Test
        @DisplayName("Should not split mid-word")
        void shouldNotSplitMidWord() {
            TextChunker chunker = new TextChunker();
            String text = generateText(TARGET_CHARS * 2);
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            for (TextChunk chunk : chunks) {
                String chunkText = chunk.getText();
                
                if (!chunkText.isEmpty()) {
                    assertThat(chunkText.charAt(chunkText.length() - 1))
                        .as("Chunk should not end in middle of word")
                        .matches(c -> Character.isWhitespace(c) || 
                                      c == '.' || c == '!' || c == '?' ||
                                      c == ',' || c == ';' || c == ':');
                }
                
                if (!chunkText.isEmpty() && chunk.getChunkIndex() > 0) {
                    assertThat(chunkText.charAt(0))
                        .as("Non-first chunk should not start in middle of word")
                        .isNotEqualTo(' ');
                }
            }
        }

        @Test
        @DisplayName("Should handle text without spaces gracefully")
        void shouldHandleTextWithoutSpaces() {
            TextChunker chunker = new TextChunker(100, 10);
            String text = "a".repeat(1000);
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            assertThat(chunks).isNotEmpty();
            assertThat(chunks.stream().mapToInt(TextChunk::getTokenCount).sum()).isPositive();
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle text exactly at target size")
        void shouldHandleTextAtTargetSize() {
            TextChunker chunker = new TextChunker();
            String text = "x ".repeat(TARGET_CHARS / 2).strip();
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            assertThat(chunks).hasSize(1);
        }

        @Test
        @DisplayName("Should handle text just over target size")
        void shouldHandleTextJustOverTargetSize() {
            TextChunker chunker = new TextChunker();
            String text = generateText(TARGET_CHARS + 100);
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            assertThat(chunks).hasSizeGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Should handle newlines")
        void shouldHandleNewlines() {
            TextChunker chunker = new TextChunker();
            String text = "Line one.\n\nLine two.\n\nLine three.";
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            assertThat(chunks).isNotEmpty();
            assertThat(chunks.get(0).getText()).contains("Line one");
        }

        @Test
        @DisplayName("Should handle tabs")
        void shouldHandleTabs() {
            TextChunker chunker = new TextChunker();
            String text = "Column1\tColumn2\tColumn3";
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            assertThat(chunks).hasSize(1);
        }

        @Test
        @DisplayName("Should handle Unicode characters")
        void shouldHandleUnicode() {
            TextChunker chunker = new TextChunker();
            String text = "Hello 世界! This is Unicode: 😀 emoji.";
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).getText()).contains("世界");
        }

        @Test
        @DisplayName("Should handle very small chunks")
        void shouldHandleSmallChunks() {
            TextChunker chunker = new TextChunker(10, 2);
            String text = "Short.";
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).getText()).isEqualTo("Short.");
        }

        @Test
        @DisplayName("Should strip trailing whitespace from input")
        void shouldStripTrailingWhitespace() {
            TextChunker chunker = new TextChunker();
            String text = "Content here.   \n\n  ";
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).getText()).isEqualTo("Content here.");
        }
    }

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("Chunk token counts should be sensible")
        void chunkTokenCountsShouldBeSensible() {
            TextChunker chunker = new TextChunker();
            String text = generateText(TARGET_CHARS * 5);
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            for (TextChunk chunk : chunks) {
                assertThat(chunk.getTokenCount())
                    .as("Token count should be positive")
                    .isPositive();
                assertThat(chunk.getTokenCount())
                    .as("Token count should be reasonable (< 1000 for default config)")
                    .isLessThan(1000);
            }
        }

        @Test
        @DisplayName("Start offsets should be strictly increasing")
        void startOffsetsShouldBeStrictlyIncreasing() {
            TextChunker chunker = new TextChunker();
            String text = generateText(TARGET_CHARS * 3);
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            int lastStart = -1;
            for (TextChunk chunk : chunks) {
                assertThat(chunk.getStartOffset())
                    .as("Start offset should be strictly increasing")
                    .isGreaterThan(lastStart);
                lastStart = chunk.getStartOffset();
            }
        }

        @Test
        @DisplayName("All chunks should have same document ID")
        void allChunksShouldHaveSameDocumentId() {
            TextChunker chunker = new TextChunker();
            String text = generateText(TARGET_CHARS * 2);
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            assertThat(chunks).allMatch(chunk -> DOC_ID.equals(chunk.getDocumentId()));
        }

        @Test
        @DisplayName("Chunks should cover entire text without gaps (accounting for overlap)")
        void chunksShouldCoverEntireText() {
            TextChunker chunker = new TextChunker();
            String text = generateText(TARGET_CHARS * 2);
            List<TextChunk> chunks = chunker.chunk(DOC_ID, text);

            int lastEnd = 0;
            for (TextChunk chunk : chunks) {
                assertThat(chunk.getStartOffset())
                    .as("No gaps between chunks")
                    .isLessThanOrEqualTo(lastEnd);
                lastEnd = Math.max(lastEnd, chunk.getEndOffset());
            }
            
            assertThat(lastEnd).isEqualTo(text.stripTrailing().length());
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Should be thread-safe for concurrent chunking")
        void shouldBeThreadSafe() throws InterruptedException {
            TextChunker chunker = new TextChunker();
            String text = generateText(TARGET_CHARS * 2);
            
            int numThreads = 10;
            Thread[] threads = new Thread[numThreads];
            List<Exception> exceptions = new java.util.concurrent.CopyOnWriteArrayList<>();
            List<List<TextChunk>> results = new java.util.concurrent.CopyOnWriteArrayList<>();

            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                threads[i] = new Thread(() -> {
                    try {
                        List<TextChunk> chunks = chunker.chunk("doc-" + threadId, text);
                        results.add(chunks);
                    } catch (Exception e) {
                        exceptions.add(e);
                    }
                });
            }

            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join();

            assertThat(exceptions).isEmpty();
            assertThat(results).hasSize(numThreads);
            
            for (List<TextChunk> chunks : results) {
                assertThat(chunks).hasSizeGreaterThan(1);
            }
        }
    }

    private String generateText(int approxChars) {
        StringBuilder sb = new StringBuilder();
        String sentence = "This is a sample sentence for testing text chunking functionality. ";
        
        while (sb.length() < approxChars) {
            sb.append(sentence);
        }
        
        return sb.toString().trim();
    }
}
