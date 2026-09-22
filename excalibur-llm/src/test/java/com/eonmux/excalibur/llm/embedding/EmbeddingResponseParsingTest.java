package com.eonmux.excalibur.llm.embedding;

import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.client.MockLLMServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated tests for embedding response parsing edge cases.
 *
 * <h2>Domain Correctness Verification</h2>
 * <ul>
 *   <li>Float parsing: handles scientific notation, negative values, whitespace</li>
 *   <li>JSON bracket matching: handles nested brackets, strings with brackets</li>
 *   <li>Batch parsing: verifies count matching and order preservation</li>
 *   <li>Usage parsing: handles missing/malformed usage fields gracefully</li>
 *   <li>Error cases: malformed JSON produces PARSE_ERROR with descriptive messages</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Each test runs in isolation with its own mock server instance.</p>
 */
@Timeout(value = 10)
class EmbeddingResponseParsingTest {

    private MockLLMServer server;
    private LLMClientConfig config;
    private EmbeddingClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = MockLLMServer.create().start();
        config = LLMClientConfig.builder()
                .baseUrl(server.getBaseUrl())
                .embeddingModel("text-embedding-3-small")
                .embeddingDimension(1536)
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)
                .build();
        client = new EmbeddingClient(config);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    // ==================== Scientific Notation Parsing ====================

    @Test
    @DisplayName("parseEmbeddingResponse: handles scientific notation with lowercase e")
    void parseEmbeddingResponse_scientificNotationLowercase_parsesCorrectly() {
        float[] expected = new float[]{1.5e-10f, 2.3e5f, -4.7e-3f};
        // Pad to 1536 dimensions
        float[] fullExpected = padToDimension(expected, 1536);

        server.setEmbeddingHandler(createRawResponseHandler(
                buildEmbeddingJsonWithScientificNotation(fullExpected, false)));

        float[] result = client.embed("Scientific notation test");

        assertThat(result).hasSize(1536);
        assertThat(result[0]).isCloseTo(1.5e-10f, within(1e-15f));
        assertThat(result[1]).isCloseTo(2.3e5f, within(1.0f));
        assertThat(result[2]).isCloseTo(-4.7e-3f, within(1e-8f));
    }

    @Test
    @DisplayName("parseEmbeddingResponse: handles scientific notation with uppercase E")
    void parseEmbeddingResponse_scientificNotationUppercase_parsesCorrectly() {
        float[] expected = new float[]{1.5E-10f, 2.3E5f, -4.7E-3f};
        float[] fullExpected = padToDimension(expected, 1536);

        server.setEmbeddingHandler(createRawResponseHandler(
                buildEmbeddingJsonWithScientificNotation(fullExpected, true)));

        float[] result = client.embed("Scientific notation uppercase test");

        assertThat(result).hasSize(1536);
        assertThat(result[0]).isCloseTo(1.5E-10f, within(1e-15f));
        assertThat(result[1]).isCloseTo(2.3E5f, within(1.0f));
        assertThat(result[2]).isCloseTo(-4.7E-3f, within(1e-8f));
    }

    @Test
    @DisplayName("parseEmbeddingResponse: handles very small floats near zero")
    void parseEmbeddingResponse_verySmallFloats_parsesCorrectly() {
        float[] expected = new float[]{1.0e-38f, -1.0e-38f, Float.MIN_VALUE};
        float[] fullExpected = padToDimension(expected, 1536);

        server.setEmbeddingHandler(createRawResponseHandler(buildEmbeddingJson(fullExpected)));

        float[] result = client.embed("Very small floats test");

        assertThat(result[0]).isCloseTo(1.0e-38f, within(1e-42f));
        assertThat(result[1]).isCloseTo(-1.0e-38f, within(1e-42f));
        assertThat(result[2]).isEqualTo(Float.MIN_VALUE);
    }

    @Test
    @DisplayName("parseEmbeddingResponse: handles very large floats")
    void parseEmbeddingResponse_veryLargeFloats_parsesCorrectly() {
        // Use values that are large but still finite
        float[] expected = new float[]{1.0e20f, -1.0e20f, 3.14159e15f};
        float[] fullExpected = padToDimension(expected, 1536);

        server.setEmbeddingHandler(createRawResponseHandler(buildEmbeddingJson(fullExpected)));

        float[] result = client.embed("Very large floats test");

        assertThat(result[0]).isCloseTo(1.0e20f, within(1e15f));
        assertThat(result[1]).isCloseTo(-1.0e20f, within(1e15f));
        assertThat(result[2]).isCloseTo(3.14159e15f, within(1e10f));
    }

    // ==================== Negative Values ====================

    @Test
    @DisplayName("parseEmbeddingResponse: handles all negative values")
    void parseEmbeddingResponse_allNegativeValues_parsesCorrectly() {
        float[] expected = createNegativeEmbedding(1536);
        server.setEmbeddingHandler(createRawResponseHandler(buildEmbeddingJson(expected)));

        float[] result = client.embed("Negative values test");

        assertThat(result).hasSize(1536);
        for (int i = 0; i < 10; i++) {
            assertThat(result[i]).isNegative();
        }
    }

    @Test
    @DisplayName("parseEmbeddingResponse: handles mixed positive and negative values")
    void parseEmbeddingResponse_mixedPositiveNegative_parsesCorrectly() {
        float[] expected = createMixedSignEmbedding(1536);
        server.setEmbeddingHandler(createRawResponseHandler(buildEmbeddingJson(expected)));

        float[] result = client.embed("Mixed sign test");

        assertThat(result).hasSize(1536);
        // Verify pattern: positive, negative, positive, negative...
        assertThat(result[0]).isPositive();
        assertThat(result[1]).isNegative();
        assertThat(result[2]).isPositive();
    }

    @Test
    @DisplayName("parseEmbeddingResponse: handles zero values")
    void parseEmbeddingResponse_zeroValues_parsesCorrectly() {
        float[] expected = new float[1536];
        expected[0] = 0.0f;
        expected[100] = -0.0f; // Negative zero
        expected[500] = 0.0f;

        server.setEmbeddingHandler(createRawResponseHandler(buildEmbeddingJson(expected)));

        float[] result = client.embed("Zero values test");

        assertThat(result).hasSize(1536);
        assertThat(result[0]).isEqualTo(0.0f);
        assertThat(result[100]).isEqualTo(-0.0f); // Negative zero is still zero
        assertThat(result[500]).isEqualTo(0.0f);
    }

    // ==================== Whitespace Handling ====================

    @Test
    @DisplayName("parseEmbeddingResponse: handles extra whitespace in array")
    void parseEmbeddingResponse_extraWhitespace_parsesCorrectly() {
        float[] expected = createTestEmbedding(1536, 0.5f);

        // Build JSON with extra whitespace
        StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[");
        for (int i = 0; i < expected.length; i++) {
            if (i > 0) sb.append("  ,  "); // Extra whitespace around comma
            sb.append("  ").append(expected[i]).append("  "); // Extra whitespace around values
        }
        sb.append("  ],\"index\":0}],\"model\":\"test\",\"usage\":{\"total_tokens\":10}}");

        server.setEmbeddingHandler(createRawResponseHandler(sb.toString()));

        float[] result = client.embed("Whitespace test");

        assertThat(result).hasSize(1536);
        assertThat(result[0]).isEqualTo(expected[0]);
    }

    @Test
    @DisplayName("parseEmbeddingResponse: handles newlines in JSON")
    void parseEmbeddingResponse_newlinesInJson_parsesCorrectly() {
        float[] expected = createTestEmbedding(1536, 0.5f);

        // Build JSON with newlines
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"object\": \"list\",\n");
        sb.append("  \"data\": [{\n");
        sb.append("    \"object\": \"embedding\",\n");
        sb.append("    \"embedding\": [\n");
        for (int i = 0; i < expected.length; i++) {
            if (i > 0) sb.append(",\n");
            sb.append("      ").append(expected[i]);
        }
        sb.append("\n    ],\n");
        sb.append("    \"index\": 0\n");
        sb.append("  }],\n");
        sb.append("  \"model\": \"test\",\n");
        sb.append("  \"usage\": {\"total_tokens\": 10}\n");
        sb.append("}");

        server.setEmbeddingHandler(createRawResponseHandler(sb.toString()));

        float[] result = client.embed("Newlines test");

        assertThat(result).hasSize(1536);
    }

    // ==================== Bracket Matching Edge Cases ====================

    @Test
    @DisplayName("parseEmbeddingResponse: handles brackets inside string values")
    void parseEmbeddingResponse_bracketsInStrings_ignoredCorrectly() {
        float[] expected = createTestEmbedding(1536, 0.5f);

        // JSON with brackets inside string values (model name)
        String json = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[" +
                arrayToString(expected) +
                "],\"index\":0}],\"model\":\"test[model]name{with}brackets\",\"usage\":{\"total_tokens\":10}}";

        server.setEmbeddingHandler(createRawResponseHandler(json));

        float[] result = client.embed("Brackets in strings test");

        assertThat(result).hasSize(1536);
    }

    @Test
    @DisplayName("parseEmbeddingResponse: handles escaped quotes in strings")
    void parseEmbeddingResponse_escapedQuotes_handledCorrectly() {
        float[] expected = createTestEmbedding(1536, 0.5f);

        String json = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[" +
                arrayToString(expected) +
                "],\"index\":0}],\"model\":\"test\\\"model\\\"name\",\"usage\":{\"total_tokens\":10}}";

        server.setEmbeddingHandler(createRawResponseHandler(json));

        float[] result = client.embed("Escaped quotes test");

        assertThat(result).hasSize(1536);
    }

    @Test
    @DisplayName("parseEmbeddingResponse: handles nested brackets in data field")
    void parseEmbeddingResponse_nestedBrackets_handledCorrectly() {
        float[] expected = createTestEmbedding(1536, 0.5f);

        // JSON with extra nested structure that should be ignored
        String json = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[" +
                arrayToString(expected) +
                "],\"index\":0,\"metadata\":{\"nested\":[1,2,3]}}],\"model\":\"test\",\"usage\":{\"total_tokens\":10}}";

        server.setEmbeddingHandler(createRawResponseHandler(json));

        float[] result = client.embed("Nested brackets test");

        assertThat(result).hasSize(1536);
    }

    // ==================== Truncated/Malformed JSON ====================

    @Test
    @DisplayName("parseEmbeddingResponse: truncated JSON throws PARSE_ERROR")
    void parseEmbeddingResponse_truncatedJson_throwsParseError() {
        // Truncated JSON - array not closed
        String truncatedJson = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[0.1,0.2,0.3";

        server.setEmbeddingHandler(createRawResponseHandler(truncatedJson));

        LLMException ex = assertThrows(LLMException.class, () -> client.embed("Truncated test"));

        assertThat(ex.getErrorType()).isEqualTo(LLMException.ErrorType.PARSE_ERROR);
    }

    @Test
    @DisplayName("parseEmbeddingResponse: missing closing bracket throws PARSE_ERROR")
    void parseEmbeddingResponse_missingClosingBracket_throwsParseError() {
        String malformedJson = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[0.1,0.2,0.3]";

        server.setEmbeddingHandler(createRawResponseHandler(malformedJson));

        LLMException ex = assertThrows(LLMException.class, () -> client.embed("Missing bracket test"));

        assertThat(ex.getErrorType()).isEqualTo(LLMException.ErrorType.PARSE_ERROR);
    }

    @Test
    @DisplayName("parseEmbeddingResponse: invalid float value throws PARSE_ERROR")
    void parseEmbeddingResponse_invalidFloatValue_throwsParseError() {
        String invalidJson = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[0.1,not_a_number,0.3],\"index\":0}]}";

        server.setEmbeddingHandler(createRawResponseHandler(invalidJson));

        LLMException ex = assertThrows(LLMException.class, () -> client.embed("Invalid float test"));

        assertThat(ex.getErrorType()).isEqualTo(LLMException.ErrorType.PARSE_ERROR);
        assertThat(ex.getMessage()).contains("Invalid float value");
    }

    @Test
    @DisplayName("parseEmbeddingResponse: string value in array throws PARSE_ERROR")
    void parseEmbeddingResponse_stringInArray_throwsParseError() {
        String invalidJson = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[0.1,\"0.2\",0.3],\"index\":0}]}";

        server.setEmbeddingHandler(createRawResponseHandler(invalidJson));

        LLMException ex = assertThrows(LLMException.class, () -> client.embed("String in array test"));

        assertThat(ex.getErrorType()).isEqualTo(LLMException.ErrorType.PARSE_ERROR);
    }

    @Test
    @DisplayName("parseEmbeddingResponse: empty element in array throws PARSE_ERROR")
    void parseEmbeddingResponse_emptyElement_throwsParseError() {
        String invalidJson = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[0.1,,0.3],\"index\":0}]}";

        server.setEmbeddingHandler(createRawResponseHandler(invalidJson));

        LLMException ex = assertThrows(LLMException.class, () -> client.embed("Empty element test"));

        assertThat(ex.getErrorType()).isEqualTo(LLMException.ErrorType.PARSE_ERROR);
    }

    // ==================== Batch Response Parsing ====================

    @Test
    @DisplayName("parseBatchEmbeddingResponse: handles large batch")
    void parseBatchEmbeddingResponse_largeBatch_parsesAll() {
        int batchSize = 100;
        float[][] expected = new float[batchSize][];
        for (int i = 0; i < batchSize; i++) {
            expected[i] = createTestEmbedding(1536, (float) i / batchSize);
        }

        server.setEmbeddingHandler(createBatchEmbeddingHandler(expected));

        List<String> texts = java.util.stream.IntStream.range(0, batchSize)
                .mapToObj(i -> "Text " + i)
                .toList();

        List<float[]> results = client.embedBatch(texts);

        assertThat(results).hasSize(batchSize);
        for (int i = 0; i < batchSize; i++) {
            assertThat(results.get(i)).hasSize(1536);
            assertThat(results.get(i)[0]).isEqualTo(expected[i][0]);
        }
    }

    @Test
    @DisplayName("parseBatchEmbeddingResponse: a reordered response is realigned by index")
    void parseBatchEmbeddingResponse_nonSequentialIndices_realignsByIndex() {
        // This test previously asserted the opposite -- that results come back in the order the
        // response happened to list them. That contradicts embedBatch's own contract ("one per
        // input text, in the same order") and is a data corruption rather than a formatting
        // preference: element i of the returned list is paired with texts.get(i) by every
        // caller, so honouring arrival order over the declared index attaches "Third"'s vector
        // to "First". Every vector is individually valid, so nothing downstream can detect it.
        // vLLM and other servers do reorder when a batch is split across workers, which is why
        // the OpenAI schema carries an index at all.
        float[][] expected = {
                createTestEmbedding(1536, 0.1f),
                createTestEmbedding(1536, 0.2f),
                createTestEmbedding(1536, 0.3f)
        };

        // Response lists them 2, 0, 1.
        StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"data\":[");
        sb.append("{\"object\":\"embedding\",\"embedding\":[").append(arrayToString(expected[2])).append("],\"index\":2},");
        sb.append("{\"object\":\"embedding\",\"embedding\":[").append(arrayToString(expected[0])).append("],\"index\":0},");
        sb.append("{\"object\":\"embedding\",\"embedding\":[").append(arrayToString(expected[1])).append("],\"index\":1}");
        sb.append("],\"model\":\"test\",\"usage\":{\"total_tokens\":30}}");

        server.setEmbeddingHandler(createRawResponseHandler(sb.toString()));

        List<float[]> results = client.embedBatch(List.of("First", "Second", "Third"));

        assertThat(results).hasSize(3);
        assertThat(results.get(0)[0]).as("input 0 gets the vector declaring index 0").isEqualTo(0.1f);
        assertThat(results.get(1)[0]).as("input 1 gets the vector declaring index 1").isEqualTo(0.2f);
        assertThat(results.get(2)[0]).as("input 2 gets the vector declaring index 2").isEqualTo(0.3f);
    }

    @Test
    @DisplayName("parseBatchEmbeddingResponse: a response with no index falls back to arrival order")
    void parseBatchEmbeddingResponse_noIndexField_usesArrivalOrder() {
        // Not every OpenAI-compatible server emits the index. Absent it, arrival order is the
        // only signal there is, and it is the right one to use.
        float[][] expected = {
                createTestEmbedding(1536, 0.1f),
                createTestEmbedding(1536, 0.2f)
        };
        StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"data\":[");
        sb.append("{\"object\":\"embedding\",\"embedding\":[").append(arrayToString(expected[0])).append("]},");
        sb.append("{\"object\":\"embedding\",\"embedding\":[").append(arrayToString(expected[1])).append("]}");
        sb.append("],\"model\":\"test\"}");

        server.setEmbeddingHandler(createRawResponseHandler(sb.toString()));

        List<float[]> results = client.embedBatch(List.of("First", "Second"));

        assertThat(results).hasSize(2);
        assertThat(results.get(0)[0]).isEqualTo(0.1f);
        assertThat(results.get(1)[0]).isEqualTo(0.2f);
    }

    @Test
    @DisplayName("parseBatchEmbeddingResponse: a duplicated index is refused, not silently kept")
    void parseBatchEmbeddingResponse_duplicateIndex_throws() {
        float[] v = createTestEmbedding(1536, 0.5f);
        StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"data\":[");
        sb.append("{\"embedding\":[").append(arrayToString(v)).append("],\"index\":0},");
        sb.append("{\"embedding\":[").append(arrayToString(v)).append("],\"index\":0}");
        sb.append("],\"model\":\"test\"}");

        server.setEmbeddingHandler(createRawResponseHandler(sb.toString()));

        assertThatThrownBy(() -> client.embedBatch(List.of("First", "Second")))
                .isInstanceOf(LLMException.class)
                .hasMessageContaining("twice");
    }

    @Test
    @DisplayName("parseBatchEmbeddingResponse: an out-of-range index is refused")
    void parseBatchEmbeddingResponse_indexOutOfRange_throws() {
        float[] v = createTestEmbedding(1536, 0.5f);
        StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"data\":[");
        sb.append("{\"embedding\":[").append(arrayToString(v)).append("],\"index\":0},");
        sb.append("{\"embedding\":[").append(arrayToString(v)).append("],\"index\":7}");
        sb.append("],\"model\":\"test\"}");

        server.setEmbeddingHandler(createRawResponseHandler(sb.toString()));

        assertThatThrownBy(() -> client.embedBatch(List.of("First", "Second")))
                .isInstanceOf(LLMException.class)
                .hasMessageContaining("outside the batch");
    }

    @Test
    @DisplayName("parseBatchEmbeddingResponse: count mismatch throws PARSE_ERROR")
    void parseBatchEmbeddingResponse_countMismatch_throwsParseError() {
        // Request 3 embeddings but response only has 2
        float[][] expected = {
                createTestEmbedding(1536, 0.1f),
                createTestEmbedding(1536, 0.2f)
        };

        server.setEmbeddingHandler(createBatchEmbeddingHandler(expected));

        LLMException ex = assertThrows(LLMException.class,
                () -> client.embedBatch(List.of("First", "Second", "Third")));

        assertThat(ex.getErrorType()).isEqualTo(LLMException.ErrorType.PARSE_ERROR);
        assertThat(ex.getMessage()).contains("Embedding count mismatch");
        assertThat(ex.getMessage()).contains("expected 3");
        assertThat(ex.getMessage()).contains("got 2");
    }

    @Test
    @DisplayName("parseBatchEmbeddingResponse: empty data array throws PARSE_ERROR")
    void parseBatchEmbeddingResponse_emptyDataArray_throwsParseError() {
        String emptyDataJson = "{\"object\":\"list\",\"data\":[],\"model\":\"test\",\"usage\":{\"total_tokens\":0}}";

        server.setEmbeddingHandler(createRawResponseHandler(emptyDataJson));

        LLMException ex = assertThrows(LLMException.class,
                () -> client.embedBatch(List.of("Single")));

        assertThat(ex.getErrorType()).isEqualTo(LLMException.ErrorType.PARSE_ERROR);
    }

    // ==================== Usage Field Parsing ====================

    @Test
    @DisplayName("parseEmbeddingResponseWithMetadata: parses usage correctly")
    void parseEmbeddingResponseWithMetadata_validUsage_parsesCorrectly() {
        float[] expected = createTestEmbedding(1536, 0.5f);
        int totalTokens = 42;

        server.setEmbeddingHandler(createEmbeddingHandlerWithUsage(expected, totalTokens));

        EmbeddingResponse response = client.embedWithMetadata("Usage test");

        assertThat(response.totalTokens()).isEqualTo(totalTokens);
        assertThat(response.hasTokenInfo()).isTrue();
    }

    @Test
    @DisplayName("parseEmbeddingResponseWithMetadata: missing usage defaults to zero")
    void parseEmbeddingResponseWithMetadata_missingUsage_defaultsToZero() {
        float[] expected = createTestEmbedding(1536, 0.5f);

        String json = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[" +
                arrayToString(expected) +
                "],\"index\":0}],\"model\":\"test\"}";

        server.setEmbeddingHandler(createRawResponseHandler(json));

        EmbeddingResponse response = client.embedWithMetadata("Missing usage test");

        assertThat(response.totalTokens()).isEqualTo(0);
        assertThat(response.hasTokenInfo()).isFalse();
    }

    @Test
    @DisplayName("parseEmbeddingResponseWithMetadata: malformed usage defaults to zero")
    void parseEmbeddingResponseWithMetadata_malformedUsage_defaultsToZero() {
        float[] expected = createTestEmbedding(1536, 0.5f);

        // Usage field exists but total_tokens is not a number
        String json = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[" +
                arrayToString(expected) +
                "],\"index\":0}],\"model\":\"test\",\"usage\":{\"total_tokens\":\"not_a_number\"}}";

        server.setEmbeddingHandler(createRawResponseHandler(json));

        EmbeddingResponse response = client.embedWithMetadata("Malformed usage test");

        assertThat(response.totalTokens()).isEqualTo(0);
    }

    @Test
    @DisplayName("parseEmbeddingResponseWithMetadata: large token count parses correctly")
    void parseEmbeddingResponseWithMetadata_largeTokenCount_parsesCorrectly() {
        float[] expected = createTestEmbedding(1536, 0.5f);
        int largeTokenCount = 1_000_000;

        server.setEmbeddingHandler(createEmbeddingHandlerWithUsage(expected, largeTokenCount));

        EmbeddingResponse response = client.embedWithMetadata("Large token count test");

        assertThat(response.totalTokens()).isEqualTo(largeTokenCount);
    }

    // ==================== Extra Fields Handling ====================

    @Test
    @DisplayName("parseEmbeddingResponse: ignores extra fields in response")
    void parseEmbeddingResponse_extraFields_ignoredCorrectly() {
        float[] expected = createTestEmbedding(1536, 0.5f);

        String json = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[" +
                arrayToString(expected) +
                "],\"index\":0,\"extra_field\":\"should be ignored\"}]," +
                "\"model\":\"test\"," +
                "\"extra_top_level\":{\"nested\":\"object\"}," +
                "\"usage\":{\"total_tokens\":10}}";

        server.setEmbeddingHandler(createRawResponseHandler(json));

        float[] result = client.embed("Extra fields test");

        assertThat(result).hasSize(1536);
    }

    @Test
    @DisplayName("parseEmbeddingResponse: handles response with additional data entries")
    void parseEmbeddingResponse_additionalDataEntries_parsesFirstOnly() {
        float[] expected = createTestEmbedding(1536, 0.5f);
        float[] extra = createTestEmbedding(1536, 0.9f);

        // Response with multiple embeddings - should parse the first one with "embedding" field
        String json = "{\"object\":\"list\",\"data\":[" +
                "{\"object\":\"embedding\",\"embedding\":[" + arrayToString(expected) + "],\"index\":0}," +
                "{\"object\":\"embedding\",\"embedding\":[" + arrayToString(extra) + "],\"index\":1}" +
                "],\"model\":\"test\",\"usage\":{\"total_tokens\":20}}";

        server.setEmbeddingHandler(createRawResponseHandler(json));

        // Single embed should get the first embedding
        float[] result = client.embed("Multiple entries test");

        assertThat(result).hasSize(1536);
        assertThat(result[0]).isEqualTo(expected[0]);
    }

    // ==================== Domain Correctness Tests ====================

    @Test
    @DisplayName("domain correctness: all embedding values are finite")
    void domainCorrectness_allValuesFinite() {
        float[] expected = createTestEmbedding(1536, 0.5f);
        server.setEmbeddingHandler(createRawResponseHandler(buildEmbeddingJson(expected)));

        float[] result = client.embed("Finite values test");

        for (float value : result) {
            assertThat(Float.isFinite(value)).isTrue();
        }
    }

    @Test
    @DisplayName("domain correctness: embedding dimension matches configuration")
    void domainCorrectness_dimensionMatchesConfig() {
        float[] expected = createTestEmbedding(1536, 0.5f);
        server.setEmbeddingHandler(createRawResponseHandler(buildEmbeddingJson(expected)));

        float[] result = client.embed("Dimension test");

        assertThat(result.length).isEqualTo(config.getEmbeddingDimension());
    }

    @Test
    @DisplayName("domain correctness: values are in reasonable range [-100, 100]")
    void domainCorrectness_valuesInReasonableRange() {
        // Typical embedding models produce values in a reasonable range
        float[] expected = createTestEmbedding(1536, 0.5f);
        server.setEmbeddingHandler(createRawResponseHandler(buildEmbeddingJson(expected)));

        float[] result = client.embed("Range test");

        for (float value : result) {
            assertThat(value).isBetween(-100.0f, 100.0f);
        }
    }

    // ==================== Helper Methods ====================

    private float[] createTestEmbedding(int dimension, float startValue) {
        float[] embedding = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            embedding[i] = startValue + (i * 0.0001f);
        }
        return embedding;
    }

    private float[] createNegativeEmbedding(int dimension) {
        float[] embedding = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            embedding[i] = -(0.1f + (i * 0.0001f));
        }
        return embedding;
    }

    private float[] createMixedSignEmbedding(int dimension) {
        float[] embedding = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            embedding[i] = (i % 2 == 0 ? 1 : -1) * (0.1f + (i * 0.0001f));
        }
        return embedding;
    }

    private float[] padToDimension(float[] values, int targetDimension) {
        float[] result = new float[targetDimension];
        System.arraycopy(values, 0, result, 0, Math.min(values.length, targetDimension));
        // Fill remaining with pattern
        for (int i = values.length; i < targetDimension; i++) {
            result[i] = 0.001f * i;
        }
        return result;
    }

    private String buildEmbeddingJson(float[] embedding) {
        StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[");
        sb.append(arrayToString(embedding));
        sb.append("],\"index\":0}],\"model\":\"test\",\"usage\":{\"total_tokens\":10}}");
        return sb.toString();
    }

    private String buildEmbeddingJsonWithScientificNotation(float[] embedding, boolean uppercaseE) {
        StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            // Use scientific notation for first few values
            if (i < 3) {
                String format = uppercaseE ? "%.6E" : "%.6e";
                sb.append(String.format(format, embedding[i]));
            } else {
                sb.append(embedding[i]);
            }
        }
        sb.append("],\"index\":0}],\"model\":\"test\",\"usage\":{\"total_tokens\":10}}");
        return sb.toString();
    }

    private String arrayToString(float[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private HttpHandler createRawResponseHandler(String responseJson) {
        return exchange -> {
            byte[] bytes = responseJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        };
    }

    private HttpHandler createBatchEmbeddingHandler(float[][] embeddings) {
        return exchange -> {
            StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"data\":[");

            for (int i = 0; i < embeddings.length; i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"object\":\"embedding\",\"embedding\":[");
                sb.append(arrayToString(embeddings[i]));
                sb.append("],\"index\":").append(i).append("}");
            }

            sb.append("],\"model\":\"test\",\"usage\":{\"total_tokens\":")
                    .append(embeddings.length * 10).append("}}");

            byte[] bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        };
    }

    private HttpHandler createEmbeddingHandlerWithUsage(float[] embedding, int totalTokens) {
        return exchange -> {
            StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[");
            sb.append(arrayToString(embedding));
            sb.append("],\"index\":0}],\"model\":\"test\",\"usage\":{\"total_tokens\":")
                    .append(totalTokens).append("}}");

            byte[] bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        };
    }
}
