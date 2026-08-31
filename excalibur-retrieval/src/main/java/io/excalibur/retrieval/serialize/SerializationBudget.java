package io.excalibur.retrieval.serialize;

/**
 * How much context the subgraph is allowed to occupy.
 *
 * <p>A hard limit, not a target. The context window is finite and shared with the system prompt,
 * the question, the conversation and the model's own output; a subgraph that overruns does not
 * degrade gracefully, it fails the request or silently truncates whatever the provider decides to
 * drop — which is generally the end of the prompt, exactly where the instructions live.
 *
 * @param maxTokens the ceiling, in tokens as {@code counter} measures them
 * @param counter   the counter; must agree with the model that will read the result
 */
public record SerializationBudget(int maxTokens, TokenCounter counter) {

    public SerializationBudget {
        if (maxTokens < 0) {
            throw new IllegalArgumentException("maxTokens must not be negative, got " + maxTokens);
        }
        if (counter == null) {
            throw new IllegalArgumentException("counter must not be null");
        }
    }

    /** A budget measured with the tokenizer of a named model. */
    public static SerializationBudget forModel(int maxTokens, String model) {
        return new SerializationBudget(maxTokens, BpeTokenCounter.forModel(model));
    }
}
