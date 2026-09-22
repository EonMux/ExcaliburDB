package com.eonmux.excalibur.llm.embedding;

import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.LLMException.ErrorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads embedding vectors and their metadata out of an OpenAI-shaped response.
 *
 * <p>Split from the client for the same reason the chat side is: making a request and
 * understanding the answer fail for different reasons and are worth reading separately, and
 * the parsing is the half worth handing a response body directly in a test.</p>
 *
 * <p>The scan is hand-written because this module carries no JSON dependency. Everything it
 * cannot account for is therefore raised as a parse error rather than guessed at - a vector
 * assembled from a misread response would be a silently wrong answer to every query that came
 * near it, which is worse than a failed ingest.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable, holding only the configuration it validates dimensions against.</p>
 */
public final class EmbeddingResponseParser {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingResponseParser.class);

    /** Smallest vector that could mean anything. */
    private static final int MIN_EMBEDDING_DIMENSION = 1;

    /** Largest vector any model in use produces; beyond this the response is misread, not large. */
    private static final int MAX_EMBEDDING_DIMENSION = 4096;

    private final LLMClientConfig config;

    /**
     * Creates a parser that validates vectors against the configured model.
     *
     * @param config Client configuration, never null
     */
    public EmbeddingResponseParser(LLMClientConfig config) {
        this.config = Objects.requireNonNull(config, "Configuration cannot be null");
    }

    /**
     * Returns the single embedding vector carried by a response.
     *
     * @param responseBody The response body from the embeddings endpoint
     * @return The vector, never null
     * @throws LLMException if the body carries no readable embedding
     */
    public float[] parseEmbeddingResponse(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            throw new LLMException("Empty response body from embedding API", ErrorType.PARSE_ERROR);
        }

        // Find "embedding" array in response
        String embeddingKey = "\"embedding\"";
        int embeddingIndex = responseBody.indexOf(embeddingKey);

        if (embeddingIndex == -1) {
            LOG.error("Response does not contain 'embedding' field: {}", truncate(responseBody, 500));
            throw new LLMException("Response does not contain 'embedding' field", ErrorType.PARSE_ERROR);
        }

        int arrayStart = responseBody.indexOf('[', embeddingIndex);
        if (arrayStart == -1) {
            throw new LLMException("Could not find embedding array start", ErrorType.PARSE_ERROR);
        }

        int arrayEnd = findMatchingBracket(responseBody, arrayStart);
        if (arrayEnd == -1) {
            throw new LLMException("Could not find embedding array end", ErrorType.PARSE_ERROR);
        }

        String arrayContent = responseBody.substring(arrayStart + 1, arrayEnd);
        return parseFloatArray(arrayContent);
    }

    /**
     * Returns the embedding vectors carried by a batch response, in the order they were asked for.
     *
     * <p>Order comes from each element's declared {@code index} rather than from its position in
     * the array, because the endpoint is under no obligation to return them in order and a
     * vector attributed to the wrong text is a silently wrong answer for as long as it is
     * stored.</p>
     *
     * @param responseBody  The response body from the embeddings endpoint
     * @param expectedCount How many texts were sent
     * @return One vector per input text, in input order
     * @throws LLMException if the body cannot be read or does not account for every text
     */
    public List<float[]> parseBatchEmbeddingResponse(String responseBody, int expectedCount) {
        if (responseBody == null || responseBody.isEmpty()) {
            throw new LLMException("Empty response body from embedding API", ErrorType.PARSE_ERROR);
        }

        // Find "data" array
        String dataKey = "\"data\"";
        int dataIndex = responseBody.indexOf(dataKey);
        if (dataIndex == -1) {
            throw new LLMException("Response does not contain 'data' field", ErrorType.PARSE_ERROR);
        }

        // Parse all embedding objects from the data array
        List<float[]> embeddings = new ArrayList<>(expectedCount);

        int pos = responseBody.indexOf('[', dataIndex);
        if (pos == -1) {
            throw new LLMException("Could not find data array start", ErrorType.PARSE_ERROR);
        }

        int dataEnd = findMatchingBracket(responseBody, pos);
        if (dataEnd == -1) {
            throw new LLMException("Could not find data array end", ErrorType.PARSE_ERROR);
        }

        String dataArray = responseBody.substring(pos + 1, dataEnd);

        // Parse each embedding object, placing it at the index the response gives it rather
        // than at the position it happened to be written in.
        //
        // The OpenAI batch schema carries an "index" per element precisely because the order
        // of the data array is not promised, and vLLM and other servers do reorder when a
        // batch is split across workers. Trusting arrival order costs nothing when the server
        // is well behaved and silently attaches every vector to the wrong input when it is
        // not -- a corruption with no symptom, since every vector is individually valid and
        // only the pairing is wrong.
        List<float[]> byIndex = new ArrayList<>(java.util.Collections.nCopies(expectedCount, null));
        boolean sawIndex = false;
        int parsed = 0;
        int currentPos = 0;
        while (currentPos < dataArray.length()) {
            int embIdx = dataArray.indexOf("\"embedding\"", currentPos);
            if (embIdx == -1) break;

            int arrStart = dataArray.indexOf('[', embIdx);
            if (arrStart == -1) break;

            int arrEnd = findMatchingBracket(dataArray, arrStart);
            if (arrEnd == -1) break;

            String arrContent = dataArray.substring(arrStart + 1, arrEnd);
            float[] vector = parseFloatArray(arrContent);

            int slot = parsed;
            Integer declared = indexNear(dataArray, embIdx, arrEnd);
            if (declared != null) {
                sawIndex = true;
                if (declared < 0 || declared >= expectedCount) {
                    throw new LLMException(
                            "Embedding index " + declared + " is outside the batch of "
                            + expectedCount, ErrorType.PARSE_ERROR);
                }
                slot = declared;
            }
            if (byIndex.get(slot) != null) {
                throw new LLMException(
                        "Embedding index " + slot + " returned twice in one batch",
                        ErrorType.PARSE_ERROR);
            }
            byIndex.set(slot, vector);
            parsed++;
            currentPos = arrEnd + 1;
        }

        if (parsed == expectedCount) {
            for (int i = 0; i < expectedCount; i++) {
                if (byIndex.get(i) == null) {
                    throw new LLMException(
                            "Batch response left index " + i + " unfilled", ErrorType.PARSE_ERROR);
                }
            }
            embeddings.addAll(byIndex);
        } else {
            // Fall through to the count check below, which reports the mismatch.
            for (float[] v : byIndex) {
                if (v != null) {
                    embeddings.add(v);
                }
            }
        }
        if (!sawIndex) {
            LOG.debug("Batch response carried no per-element index; relied on response order");
        }

        if (embeddings.size() != expectedCount) {
            throw new LLMException(
                    "Embedding count mismatch: expected " + expectedCount +
                    ", got " + embeddings.size(), ErrorType.PARSE_ERROR);
        }

        return embeddings;
    }

    /**
     * Reads the {@code "index"} of the batch element whose embedding array ends at
     * {@code arrayEnd}, or null when the element does not declare one.
     *
     * <p>Scoped to the element rather than searched globally: an embedding array is a list of
     * numbers, so a naive search for the next {@code "index"} would happily find the one
     * belonging to the following element.</p>
     */
    private static Integer indexNear(String dataArray, int embeddingKeyPos, int arrayEnd) {
        int objectStart = dataArray.lastIndexOf('{', embeddingKeyPos);
        int searchFrom = objectStart == -1 ? 0 : objectStart;
        int objectEnd = dataArray.indexOf('}', arrayEnd);
        int searchTo = objectEnd == -1 ? dataArray.length() : objectEnd;

        int key = dataArray.indexOf("\"index\"", searchFrom);
        if (key == -1 || key > searchTo) {
            return null;
        }
        int colon = dataArray.indexOf(':', key);
        if (colon == -1 || colon > searchTo) {
            return null;
        }
        int i = colon + 1;
        while (i < searchTo && Character.isWhitespace(dataArray.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < searchTo && Character.isDigit(dataArray.charAt(i))) {
            i++;
        }
        if (start == i) {
            return null;
        }
        try {
            return Integer.valueOf(dataArray.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns an embedding together with the token usage and model the provider reported.
     *
     * @param responseBody The response body from the embeddings endpoint
     * @return The vector and the metadata around it
     * @throws LLMException if the body carries no readable embedding
     */
    public EmbeddingResponse parseEmbeddingResponseWithMetadata(String responseBody) {
        float[] embedding = parseEmbeddingResponse(responseBody);
        validateEmbedding(embedding);

        // Parse usage if available
        int totalTokens = 0;
        String usageKey = "\"usage\"";
        int usageIdx = responseBody.indexOf(usageKey);
        if (usageIdx != -1) {
            String totalTokensKey = "\"total_tokens\"";
            int tokensIdx = responseBody.indexOf(totalTokensKey, usageIdx);
            if (tokensIdx != -1) {
                int colonIdx = responseBody.indexOf(':', tokensIdx);
                if (colonIdx != -1) {
                    int start = colonIdx + 1;
                    while (start < responseBody.length() && !Character.isDigit(responseBody.charAt(start))) {
                        start++;
                    }
                    int end = start;
                    while (end < responseBody.length() && Character.isDigit(responseBody.charAt(end))) {
                        end++;
                    }
                    if (start < end) {
                        try {
                            totalTokens = Integer.parseInt(responseBody.substring(start, end));
                        } catch (NumberFormatException e) {
                            // Keep default of 0
                        }
                    }
                }
            }
        }

        return new EmbeddingResponse(embedding, config.getEmbeddingModel(), totalTokens);
    }

    private int findMatchingBracket(String json, int startPos) {
        int depth = 1;
        boolean inString = false;
        boolean escape = false;

        for (int i = startPos + 1; i < json.length(); i++) {
            char c = json.charAt(i);

            if (escape) {
                escape = false;
                continue;
            }

            if (c == '\\' && inString) {
                escape = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }

    private float[] parseFloatArray(String arrayContent) {
        String[] parts = arrayContent.split(",");
        if (parts.length == 0 || (parts.length == 1 && parts[0].trim().isEmpty())) {
            throw new LLMException("Empty embedding array", ErrorType.PARSE_ERROR);
        }

        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) {
                throw new LLMException("Empty element in embedding array at index " + i, ErrorType.PARSE_ERROR);
            }
            try {
                result[i] = Float.parseFloat(part);
            } catch (NumberFormatException e) {
                throw new LLMException(
                        "Invalid float value in embedding array at index " + i + ": " + part,
                        ErrorType.PARSE_ERROR, e);
            }
        }

        return result;
    }

    /**
     * Rejects a vector that cannot be what the configured model produces.
     *
     * <p>Dimension and finiteness are both checked. A NaN reaching the index poisons every
     * distance computed against it, and a vector of the wrong width silently makes a whole
     * corpus unsearchable against later queries, so both are worth failing an ingest over.</p>
     *
     * @param embedding The vector to check
     * @throws LLMException if the vector is null, wrongly sized, or holds a non-finite value
     */
    public void validateEmbedding(float[] embedding) {
        if (embedding == null) {
            throw new LLMException("Embedding is null", ErrorType.PARSE_ERROR);
        }

        int length = embedding.length;
        if (length < MIN_EMBEDDING_DIMENSION || length > MAX_EMBEDDING_DIMENSION) {
            throw new LLMException(
                    "Embedding dimension out of range [" + MIN_EMBEDDING_DIMENSION + ", " +
                    MAX_EMBEDDING_DIMENSION + "]: " + length, ErrorType.PARSE_ERROR);
        }

        for (int i = 0; i < length; i++) {
            float value = embedding[i];
            if (Float.isNaN(value)) {
                throw new LLMException("Embedding contains NaN at index " + i, ErrorType.PARSE_ERROR);
            }
            if (Float.isInfinite(value)) {
                throw new LLMException("Embedding contains Infinite at index " + i, ErrorType.PARSE_ERROR);
            }
        }

        // Enforce that embedding dimension matches configured dimensionality
        // Domain correctness: embeddings must be float arrays of configured dimensionality
        int configuredDim = config.getEmbeddingDimension();
        if (length != configuredDim) {
            throw new LLMException(
                    "Embedding dimension (" + length + ") does not match configured dimensionality (" +
                    configuredDim + "). " +
                    "Ensure the embedding model '" + config.getEmbeddingModel() + "' produces " +
                    configuredDim + "-dimensional vectors, or update excalibur.llm.embeddings-dimensions.",
                    ErrorType.PARSE_ERROR);
        }
    }

    /**
     * Truncates a string so a log line stays readable.
     *
     * @param s         The string, which may be null
     * @param maxLength The most characters to keep
     * @return The truncated string
     */
    private String truncate(String s, int maxLength) {
        if (s == null) {
            return "null";
        }
        if (s.length() <= maxLength) {
            return s;
        }
        return s.substring(0, maxLength) + "...";
    }
}
