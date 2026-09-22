package com.eonmux.excalibur.retrieval.serialize;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Counts tokens by actually running the model's BPE tokenizer.
 *
 * <p>This is what makes the serialisation budget a real budget. Every alternative — characters,
 * words, triples — is a proxy that drifts from the true count in a way that depends on the content,
 * and the content here is unusually adversarial to those proxies.
 *
 * <p>The encoding is loaded once and shared. Loading it is not cheap and it is immutable, so one
 * instance per model per process is the right shape; instances of this class are safe to share
 * across threads.
 */
public final class BpeTokenCounter implements TokenCounter {

    private static final Logger LOGGER = LoggerFactory.getLogger(BpeTokenCounter.class);

    /** Lazy, so only the encodings actually asked for are built. */
    private static final EncodingRegistry REGISTRY = Encodings.newLazyEncodingRegistry();

    private final Encoding encoding;
    private final String name;

    private BpeTokenCounter(Encoding encoding, String name) {
        this.encoding = encoding;
        this.name = name;
    }

    /**
     * A counter for a named model.
     *
     * <p>Falls back to {@link EncodingType#CL100K_BASE} for a model jtokkit does not recognise,
     * which is the encoding most current models use, and says so at warning level. Falling back
     * silently would leave a budget that looks enforced and is not.
     */
    public static BpeTokenCounter forModel(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        return REGISTRY.getEncodingForModel(model)
            .map(encoding -> new BpeTokenCounter(encoding, model))
            .orElseGet(() -> {
                LOGGER.warn("unknown model '{}'; counting tokens with {} instead. Verify this "
                    + "matches the model actually being called, or the budget is not enforced.",
                    model, EncodingType.CL100K_BASE);
                return defaultCounter();
            });
    }

    /** A counter using {@link EncodingType#CL100K_BASE}. */
    public static BpeTokenCounter defaultCounter() {
        return new BpeTokenCounter(
            REGISTRY.getEncoding(EncodingType.CL100K_BASE), EncodingType.CL100K_BASE.getName());
    }

    /** The encoding or model this counter is measuring against. */
    public String name() {
        return name;
    }

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    @Override
    public String toString() {
        return "BpeTokenCounter[" + name + "]";
    }
}
