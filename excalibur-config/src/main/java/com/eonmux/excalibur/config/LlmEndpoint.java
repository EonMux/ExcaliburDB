package com.eonmux.excalibur.config;

import java.util.Locale;
import java.util.Objects;

/**
 * One model endpoint as a node will actually call it: where, with which key, and whether at all.
 *
 * <p>A node calls models in two roles. The <b>embedding</b> endpoint turns the corpus and each
 * query into vectors; the <b>chat</b> endpoint scores relevancy, expands queries, extracts
 * relations and reranks graph answers. They are resolved separately because they are routinely
 * different servers - a hosted chat model beside an embedding model on a local GPU - and each
 * falls back to the shared {@code excalibur.llm.base-url} and {@code excalibur.llm.api-key}, so a
 * configuration that names only those keeps meaning what it always meant.</p>
 *
 * <h2>What a key decides</h2>
 * <p>Only whether the hosted default endpoint can be called. Ollama, vLLM and TGI answer without
 * a key, so a role pointed anywhere else is used whether or not one is configured; if that server
 * does want a key it answers 401, and the clients report that as a missing or rejected credential
 * rather than the node quietly deciding to have no model. The hosted default always wants one, so
 * a role left on it with no key is off, and {@link #describe()} says how to turn it on. The
 * role's enabled flag is the only other switch, and it wins over everything.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable and thread-safe.</p>
 *
 * @param role    Which calls this endpoint serves
 * @param baseUrl The API root, as configured
 * @param apiKey  The key sent as a bearer token; empty when none is configured
 * @param enabled Whether the role's enabled flag allows it to be used
 */
public record LlmEndpoint(Role role, String baseUrl, String apiKey, boolean enabled) {

    /** The version segment an API root may or may not be written with. */
    private static final String VERSION_SEGMENT = "/v1";

    /**
     * The two roles a node calls a model in, and the settings each one is configured by.
     */
    public enum Role {
        /** Relevancy scoring, query expansion, relation extraction and the graph rerank. */
        CHAT("chat", "excalibur.llm.chat-base-url", "excalibur.llm.chat-api-key",
            "excalibur.llm.chat-enabled", "relevancy scoring, query expansion, relation "
                + "extraction and the graph rerank are off"),
        /** Embedding the corpus, each query, and the graph's entities. */
        EMBEDDING("embedding", "excalibur.llm.embedding-base-url",
            "excalibur.llm.embedding-api-key", "excalibur.llm.embeddings-enabled",
            "no vectors are produced, so retrieval is lexical and graph entities carry no "
                + "vectors");

        private final String label;
        private final String baseUrlKey;
        private final String apiKeyKey;
        private final String enabledKey;
        private final String whenInactive;

        Role(String label, String baseUrlKey, String apiKeyKey, String enabledKey,
             String whenInactive) {
            this.label = label;
            this.baseUrlKey = baseUrlKey;
            this.apiKeyKey = apiKeyKey;
            this.enabledKey = enabledKey;
            this.whenInactive = whenInactive;
        }

        /** @return The role's name as it appears in messages */
        public String label() {
            return label;
        }

        /** @return The property that points this role at its own server */
        public String baseUrlKey() {
            return baseUrlKey;
        }

        /** @return The property holding this role's own key */
        public String apiKeyKey() {
            return apiKeyKey;
        }

        /** @return The property that turns this role off */
        public String enabledKey() {
            return enabledKey;
        }

        /** @return What a node loses while this role is not in use */
        public String whenInactive() {
            return whenInactive;
        }
    }

    /**
     * Whether a role is in use, and if not, why not.
     */
    public enum Status {
        /** The role is called at {@link LlmEndpoint#baseUrl()}. */
        ACTIVE,
        /** The role's enabled flag is false. */
        DISABLED,
        /** The role is on the hosted default endpoint, which needs a key, and has none. */
        MISSING_API_KEY
    }

    /**
     * Normalises the parts and requires the ones that have no absent case.
     *
     * @throws NullPointerException if the role or base URL is null
     */
    public LlmEndpoint {
        Objects.requireNonNull(role, "role cannot be null");
        Objects.requireNonNull(baseUrl, "baseUrl cannot be null");
        apiKey = apiKey == null ? "" : apiKey.trim();
    }

    /**
     * Whether a key will be sent.
     *
     * <p>Blank counts as absent: a key of spaces is a mis-set environment variable, and sending
     * it buys an authentication failure on every call instead of the clear startup message the
     * operator would otherwise have got.</p>
     *
     * @return true if a non-blank key is configured
     */
    public boolean hasApiKey() {
        return !apiKey.isEmpty();
    }

    /**
     * Whether this endpoint cannot be called without a key.
     *
     * <p>True only of the hosted default, however its root is spelled. Anything else may be a
     * server that asks for no key, and the only way to find out is to call it.</p>
     *
     * @return true if the base URL is the hosted default
     */
    public boolean requiresApiKey() {
        return normalise(baseUrl).equals(normalise(LlmConfig.DEFAULT_BASE_URL));
    }

    /**
     * Whether this role is in use, and if not, why not.
     *
     * @return The status
     */
    public Status status() {
        if (!enabled) {
            return Status.DISABLED;
        }
        if (!hasApiKey() && requiresApiKey()) {
            return Status.MISSING_API_KEY;
        }
        return Status.ACTIVE;
    }

    /**
     * Whether the node calls a model in this role.
     *
     * @return true if {@link #status()} is {@link Status#ACTIVE}
     */
    public boolean isActive() {
        return status() == Status.ACTIVE;
    }

    /**
     * One sentence for the startup log saying what this role will do and how to change it.
     *
     * <p>Never includes the key.</p>
     *
     * @return The description
     */
    public String describe() {
        String name = role.label() + " endpoint";
        return switch (status()) {
            case ACTIVE -> name + " active at " + baseUrl + (hasApiKey()
                ? ", sending an API key"
                : ", sending no API key. If this server requires one it will answer 401; set "
                    + role.apiKeyKey() + " or excalibur.llm.api-key (EXCALIBUR_LLM_API_KEY)");
            case DISABLED -> name + " off: " + role.enabledKey() + "=false - "
                + role.whenInactive();
            case MISSING_API_KEY -> name + " off: " + baseUrl + " requires an API key and none is "
                + "configured - " + role.whenInactive() + ". Set " + role.apiKeyKey()
                + " or excalibur.llm.api-key (EXCALIBUR_LLM_API_KEY), or point "
                + role.baseUrlKey() + " or excalibur.llm.base-url at a server that needs none "
                + "(Ollama, vLLM, TGI)";
        };
    }

    /**
     * Brings the spellings of one API root together: case, trailing slashes and the version
     * segment the clients append themselves.
     *
     * @param url A base URL
     * @return The comparable form
     */
    private static String normalise(String url) {
        String normalised = url.trim().toLowerCase(Locale.ROOT);
        while (normalised.endsWith("/")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        if (normalised.endsWith(VERSION_SEGMENT)) {
            normalised = normalised.substring(0, normalised.length() - VERSION_SEGMENT.length());
        }
        return normalised;
    }

    @Override
    public String toString() {
        return "LlmEndpoint{role=" + role + ", baseUrl='" + baseUrl + "', apiKey='"
            + (hasApiKey() ? "***" : "") + "', enabled=" + enabled + ", status=" + status() + '}';
    }
}
