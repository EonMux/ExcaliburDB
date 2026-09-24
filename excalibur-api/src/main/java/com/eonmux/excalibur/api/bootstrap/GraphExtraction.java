package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.LlmEndpoint;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether ingestion on this node extracts facts for the graph, and if not, why not.
 *
 * <p>A graph is built from the facts ingestion staged, and ingestion stages facts only when a
 * chat model extracts them. Two settings have to agree for that:
 * {@code excalibur.graph.extraction-enabled} asks for it, and the chat endpoint
 * ({@link com.eonmux.excalibur.config.LlmConfig#chatEndpoint()}) has to be in use. With the first
 * and not the second the
 * node used to ingest normally, stage nothing, and answer every build with an empty result - an
 * operator who set the flag had nothing to tell them it was being ignored. This is what the node
 * reports instead: in its startup log, in the answer to {@code POST /v1/graph/build}, and in
 * {@code GET /v1/graph/build/status}.</p>
 *
 * <p>Decided once, at startup: from configuration, and from whether ingestion could actually be
 * wired to deliver documents to the extractor. Neither setting changes while the node runs, and
 * a pipeline that would not build at startup is not rebuilt later.</p>
 */
public enum GraphExtraction {

    /** Extraction was asked for and has a model to call: published documents become facts. */
    ACTIVE("Ingestion extracts facts from every published document, and nothing it has "
        + "ingested produced one. Ingest documents into this namespace before building - a "
        + "source registered under another namespace stages into that namespace's graph."),

    /** Extraction was not asked for, so ingestion stages nothing and a build has nothing to do. */
    DISABLED("Relation extraction is off on this node (excalibur.graph.extraction-enabled is "
        + "false), so ingestion stages no facts. Enable it, with a chat model configured, and "
        + "re-ingest."),

    /**
     * Extraction was asked for, but the chat endpoint is the hosted default and has no key, so
     * the request is not honoured.
     *
     * <p>Worth a warning because nobody chose it. Every other setting on the node works, which
     * is exactly why it goes unnoticed until a build publishes nothing.</p>
     */
    MISSING_API_KEY("excalibur.graph.extraction-enabled is true, but the chat endpoint requires "
        + "an API key and none is configured, so extraction is inactive and ingestion stages no "
        + "facts. Set excalibur.llm.chat-api-key or excalibur.llm.api-key "
        + "(EXCALIBUR_LLM_API_KEY), or point excalibur.llm.chat-base-url at a server that needs "
        + "none, restart the node, and re-ingest."),

    /**
     * Extraction was asked for, but chat calls were switched off with
     * {@code excalibur.llm.chat-enabled=false}.
     *
     * <p>Two settings that contradict each other, so it is warned about like a missing key: the
     * operator who set one has no reason to remember the other.</p>
     */
    CHAT_DISABLED("excalibur.graph.extraction-enabled is true, but excalibur.llm.chat-enabled is "
        + "false, so no chat model is called, extraction is inactive and ingestion stages no "
        + "facts. Enable chat, restart the node, and re-ingest."),

    /**
     * Extraction was asked for and a model can be called, but ingestion could not be set up to
     * hand it documents - the pipeline failed to build, or built without an orchestrator.
     *
     * <p>Worth a warning for the same reason as {@link #MISSING_API_KEY}: both settings say
     * extraction runs, so nothing in the configuration explains why no fact is ever staged. The
     * cause is in the startup log, which is where this sends the operator.</p>
     */
    UNAVAILABLE("excalibur.graph.extraction-enabled is true and a chat model is configured, "
        + "but the ingestion pipeline could not be set up on this node, so no document reaches "
        + "the extractor and ingestion stages no facts. The startup log says why; fix that, "
        + "restart the node, and re-ingest.");

    private final String nothingStaged;

    GraphExtraction(String nothingStaged) {
        this.nothingStaged = nothingStaged;
    }

    /**
     * Decides the state from the two settings and whether ingestion was wired to the extractor.
     *
     * @param extractionEnabled Whether {@code excalibur.graph.extraction-enabled} is set
     * @param chat              Whether the chat endpoint is in use, and if not, why not
     * @param ingestionWired    Whether ingestion was set up to hand published documents to the
     *                          extractor
     * @return The state
     */
    public static GraphExtraction of(boolean extractionEnabled, LlmEndpoint.Status chat,
                                     boolean ingestionWired) {
        Objects.requireNonNull(chat, "chat cannot be null");
        if (!extractionEnabled) {
            return DISABLED;
        }
        return switch (chat) {
            case MISSING_API_KEY -> MISSING_API_KEY;
            case DISABLED -> CHAT_DISABLED;
            case ACTIVE -> ingestionWired ? ACTIVE : UNAVAILABLE;
        };
    }

    /**
     * The state as a response spells it.
     *
     * @return The lower-case name
     */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Why a build found nothing staged, given this state.
     *
     * @return A sentence an operator can act on
     */
    public String whyNothingStaged() {
        return nothingStaged;
    }

    /**
     * What a caller asking for a build should be warned about before it runs.
     *
     * <p>Only the states nobody chose have one. A build on a node with extraction off is a build
     * of whatever was staged before, which may be exactly what the operator wants.</p>
     *
     * @return The warning, or empty
     */
    public Optional<String> warning() {
        return this == MISSING_API_KEY || this == CHAT_DISABLED || this == UNAVAILABLE
            ? Optional.of(nothingStaged)
            : Optional.empty();
    }
}
