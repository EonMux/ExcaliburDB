package excalibur.llm.embedding;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable response containing an embedding vector with associated metadata.
 *
 * <p>This record encapsulates the result of an embedding API call, providing
 * the embedding vector along with useful metadata such as the model used
 * and token consumption.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This record is immutable and therefore thread-safe. The embedding array
 * is defensively copied on construction to prevent external modification.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Embedding array: non-null, length in [1, 4096], all values finite</li>
 *   <li>Model name: non-null, non-empty string</li>
 *   <li>Total tokens: non-negative integer (0 if unknown)</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * EmbeddingClient client = new EmbeddingClient(config);
 * EmbeddingResponse response = client.embedWithMetadata("Hello, world!");
 *
 * float[] embedding = response.embedding();
 * String model = response.model();
 * int tokens = response.totalTokens();
 *
 * System.out.println("Generated " + embedding.length + "-dim embedding using " + model);
 * System.out.println("Consumed " + tokens + " tokens");
 * }</pre>
 *
 * @param embedding The embedding vector (non-null, length in [1, 4096], all values finite)
 * @param model The model name used to generate the embedding (non-null, non-empty)
 * @param totalTokens Total tokens consumed by the request (non-negative, 0 if unknown)
 */
public record EmbeddingResponse(
    float[] embedding,
    String model,
    int totalTokens
) {

    /**
     * Compact constructor that validates domain constraints.
     *
     * <h3>Domain Correctness Validation</h3>
     * <ul>
     *   <li>Embedding must be non-null with length in [1, 4096]</li>
     *   <li>All embedding values must be finite (not NaN or Infinite)</li>
     *   <li>Model must be non-null and non-empty</li>
     *   <li>Total tokens must be non-negative</li>
     * </ul>
     *
     * @throws NullPointerException if embedding or model is null
     * @throws IllegalArgumentException if domain constraints are violated
     */
    public EmbeddingResponse {
        // Validate embedding
        Objects.requireNonNull(embedding, "Embedding cannot be null");

        if (embedding.length < 1 || embedding.length > 4096) {
            throw new IllegalArgumentException(
                    "Embedding dimension must be in range [1, 4096], got: " + embedding.length);
        }

        // Check all values are finite
        for (int i = 0; i < embedding.length; i++) {
            if (Float.isNaN(embedding[i])) {
                throw new IllegalArgumentException("Embedding contains NaN at index " + i);
            }
            if (Float.isInfinite(embedding[i])) {
                throw new IllegalArgumentException("Embedding contains Infinite at index " + i);
            }
        }

        // Validate model
        Objects.requireNonNull(model, "Model cannot be null");
        if (model.isEmpty()) {
            throw new IllegalArgumentException("Model cannot be empty");
        }

        // Validate total tokens
        if (totalTokens < 0) {
            throw new IllegalArgumentException("Total tokens must be non-negative, got: " + totalTokens);
        }

        // Defensive copy to maintain immutability
        embedding = embedding.clone();
    }

    /**
     * Returns a copy of the embedding vector.
     *
     * <p>A copy is returned to maintain immutability. Changes to the returned
     * array will not affect this EmbeddingResponse.</p>
     *
     * @return Copy of the embedding vector
     */
    public float[] embedding() {
        return embedding.clone();
    }

    /**
     * Returns the dimension of the embedding vector.
     *
     * @return Embedding dimension (always in range [1, 4096])
     */
    public int dimension() {
        return embedding.length;
    }

    /**
     * Checks if token usage information is available.
     *
     * @return true if totalTokens is greater than 0
     */
    public boolean hasTokenInfo() {
        return totalTokens > 0;
    }

    @Override
    public String toString() {
        return "EmbeddingResponse{" +
                "dimension=" + embedding.length +
                ", model='" + model + '\'' +
                ", totalTokens=" + totalTokens +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EmbeddingResponse that = (EmbeddingResponse) o;
        return totalTokens == that.totalTokens &&
               Arrays.equals(embedding, that.embedding) &&
               Objects.equals(model, that.model);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(model, totalTokens);
        result = 31 * result + Arrays.hashCode(embedding);
        return result;
    }
}
