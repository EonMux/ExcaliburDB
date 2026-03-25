package excalibur.ingest.pipeline;

import excalibur.ingest.buffer.DocumentChunkBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IngestionOrchestrator embedding storage format.
 *
 * <p>These tests verify that:</p>
 * <ul>
 *   <li>Embeddings are stored in the correct binary format</li>
 *   <li>Stored values can be parsed by HnswIntegrationWorker.extractVector()</li>
 *   <li>The format matches the little-endian specification</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
class IngestionOrchestratorTest {

    // ==================== Embedding Format Tests ====================

    @Nested
    @DisplayName("Embedding Binary Format Tests")
    class EmbeddingFormatTests {

        @Test
        @DisplayName("encodeChunkWithTextAndEmbedding produces correct format")
        void testEncodeFormat() {
            String text = "Hello, world!";
            float[] embedding = {0.1f, 0.2f, 0.3f, 0.4f};

            byte[] encoded = DocumentChunkBuffer.encodeChunkWithTextAndEmbedding(text, embedding);

            // Verify format: [dimension:4][vector:dim*4][text:variable]
            ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);

            // Check dimension
            int dimension = buffer.getInt();
            assertEquals(4, dimension, "Dimension should be 4");

            // Check floats
            for (int i = 0; i < embedding.length; i++) {
                float value = buffer.getFloat();
                assertEquals(embedding[i], value, 0.0001f, "Float at index " + i + " should match");
            }

            // Check text
            byte[] textBytes = new byte[encoded.length - 4 - embedding.length * 4];
            buffer.get(textBytes);
            String decodedText = new String(textBytes, StandardCharsets.UTF_8);
            assertEquals(text, decodedText, "Text should match");
        }

        @Test
        @DisplayName("format is compatible with extractVector logic")
        void testFormatCompatibleWithExtractVector() {
            String text = "Test chunk content for vector search";
            float[] embedding = {0.5f, -0.3f, 0.8f, 0.1f, -0.2f};

            byte[] encoded = DocumentChunkBuffer.encodeChunkWithTextAndEmbedding(text, embedding);

            // Extract vector using the same logic as HnswIntegrationWorker.extractVector()
            float[] extracted = extractVector(encoded);

            assertNotNull(extracted, "Vector extraction should succeed");
            assertEquals(embedding.length, extracted.length, "Dimensions should match");
            for (int i = 0; i < embedding.length; i++) {
                assertEquals(embedding[i], extracted[i], 0.0001f, "Float at index " + i + " should match");
            }
        }

        @Test
        @DisplayName("format handles large dimensions")
        void testLargeDimensions() {
            String text = "Large vector test";
            int dimension = 1536; // Common embedding size (e.g., OpenAI)
            float[] embedding = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                embedding[i] = (float) Math.random();
            }

            byte[] encoded = DocumentChunkBuffer.encodeChunkWithTextAndEmbedding(text, embedding);
            float[] extracted = extractVector(encoded);

            assertNotNull(extracted, "Vector extraction should succeed for large dimensions");
            assertEquals(dimension, extracted.length, "Dimensions should match");
        }

        @Test
        @DisplayName("format handles empty text")
        void testEmptyText() {
            String text = "";
            float[] embedding = {1.0f, 0.0f, -1.0f};

            byte[] encoded = DocumentChunkBuffer.encodeChunkWithTextAndEmbedding(text, embedding);
            float[] extracted = extractVector(encoded);

            assertNotNull(extracted, "Vector extraction should succeed with empty text");
            assertEquals(3, extracted.length, "Dimensions should be 3");
        }

        @Test
        @DisplayName("format handles UTF-8 text correctly")
        void testUtf8Text() {
            String text = "Hello, 世界! Привет мир! 🌍";
            float[] embedding = {0.1f, 0.2f};

            byte[] encoded = DocumentChunkBuffer.encodeChunkWithTextAndEmbedding(text, embedding);

            // Verify text can be decoded back
            String decodedText = DocumentChunkBuffer.decodeText(encoded);
            assertEquals(text, decodedText, "UTF-8 text should round-trip correctly");
        }

        @Test
        @DisplayName("format uses little-endian byte order")
        void testLittleEndianByteOrder() {
            // Test that dimension is stored as little-endian
            float[] embedding = {1.0f};
            byte[] encoded = DocumentChunkBuffer.encodeChunkWithTextAndEmbedding("test", embedding);

            // Dimension 1 as little-endian: 01 00 00 00
            assertEquals(0x01, encoded[0] & 0xFF, "First byte should be 0x01 (little-endian)");
            assertEquals(0x00, encoded[1] & 0xFF, "Second byte should be 0x00");
            assertEquals(0x00, encoded[2] & 0xFF, "Third byte should be 0x00");
            assertEquals(0x00, encoded[3] & 0xFF, "Fourth byte should be 0x00");
        }

        @Test
        @DisplayName("extractVector rejects invalid data")
        void testExtractVectorRejectsInvalidData() {
            // Test null input
            assertNull(extractVector(null), "Should reject null");

            // Test too short input
            assertNull(extractVector(new byte[3]), "Should reject bytes shorter than 4");

            // Test invalid dimension (0)
            byte[] zeroDim = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array();
            assertNull(extractVector(zeroDim), "Should reject dimension 0");

            // Test invalid dimension (negative)
            byte[] negDim = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(-1).array();
            assertNull(extractVector(negDim), "Should reject negative dimension");

            // Test dimension too large
            byte[] largeDim = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(70000).array();
            assertNull(extractVector(largeDim), "Should reject dimension > 65536");

            // Test not enough bytes for vector
            byte[] shortData = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(100)  // dimension = 100
                .array();
            assertNull(extractVector(shortData), "Should reject when not enough bytes for vector");
        }

        @Test
        @DisplayName("round-trip encoding preserves all values")
        void testRoundTripEncoding() {
            String originalText = "This is a test document with multiple words and sentences.";
            float[] originalEmbedding = {
                0.123f, -0.456f, 0.789f, 0.001f, -0.002f,
                Float.MAX_VALUE, Float.MIN_VALUE, 0.0f, -0.0f, 1.0f
            };

            byte[] encoded = DocumentChunkBuffer.encodeChunkWithTextAndEmbedding(originalText, originalEmbedding);

            // Decode embedding
            float[] decodedEmbedding = extractVector(encoded);
            assertNotNull(decodedEmbedding, "Embedding extraction should succeed");

            // Decode text
            String decodedText = DocumentChunkBuffer.decodeText(encoded);
            assertNotNull(decodedText, "Text extraction should succeed");

            // Verify
            assertEquals(originalText, decodedText, "Text should match after round-trip");
            assertEquals(originalEmbedding.length, decodedEmbedding.length, "Embedding dimensions should match");
            for (int i = 0; i < originalEmbedding.length; i++) {
                assertEquals(originalEmbedding[i], decodedEmbedding[i], 0.0001f,
                    "Embedding value at index " + i + " should match");
            }
        }

        @Test
        @DisplayName("IngestionOrchestrator uses DocumentChunkBuffer encoding")
        void testIngestionOrchestratorUsesCorrectEncoding() {
            // This test verifies that the encoding method used by IngestionOrchestrator
            // (DocumentChunkBuffer.encodeChunkWithTextAndEmbedding) produces the correct format
            // for HnswIntegrationWorker.extractVector()

            String text = "Sample chunk text";
            float[] embedding = {0.1f, 0.2f, 0.3f, 0.4f};

            // This is what IngestionOrchestrator.runIngestion() now calls
            byte[] valueBytes = DocumentChunkBuffer.encodeChunkWithTextAndEmbedding(text, embedding);

            // This is what HnswIntegrationWorker.extractVector() expects
            float[] extracted = extractVector(valueBytes);

            assertNotNull(extracted, "Vector extraction should succeed from IngestionOrchestrator output");
            assertArrayEquals(embedding, extracted, 0.0001f,
                "Extracted embedding should match original embedding");
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Extracts a float vector from entry value bytes.
     * This is the exact same logic as HnswIntegrationWorker.extractVector().
     *
     * <p>Format: [dimension:4 bytes LE][float values...]</p>
     *
     * @param value The entry value bytes
     * @return The float array, or null if extraction fails
     */
    private static float[] extractVector(byte[] value) {
        if (value == null || value.length < 4) {
            return null;
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
            int dimension = buffer.getInt();

            if (dimension <= 0 || dimension > 65536) {
                return null; // Invalid dimension
            }

            int expectedBytes = 4 + dimension * 4;
            if (value.length < expectedBytes) {
                return null; // Not enough bytes
            }

            float[] vector = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                vector[i] = buffer.getFloat();
            }

            return vector;

        } catch (Exception e) {
            return null;
        }
    }
}
