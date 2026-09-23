package com.eonmux.excalibur.api.bootstrap;

import java.util.Locale;
import java.util.Optional;

/**
 * Whether ingestion on this node extracts facts for the graph, and if not, why not.
 *
 * <p>A graph is built from the facts ingestion staged, and ingestion stages facts only when a
 * chat model extracts them. Two settings have to agree for that:
 * {@code excalibur.graph.extraction-enabled} asks for it, and {@code excalibur.llm.api-key} (or
 * {@code EXCALIBUR_LLM_API_KEY}) makes a model reachable. With the first and not the second the
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
        + "false), so ingestion stages no facts. Enable it, with excalibur.llm.api-key set, and "
        + "re-ingest."),

    /**
     * Extraction was asked for, but no model can be called, so the request is not honoured.
     *
     * <p>Worth a warning because nobody chose it. Every other setting on the node works, which
     * is exactly why it goes unnoticed until a build publishes nothing.</p>
     */
    MISSING_API_KEY("excalibur.graph.extraction-enabled is true, but no LLM API key is "
        + "configured, so extraction is inactive and ingestion stages no facts. Set "
        + "excalibur.llm.api-key, or EXCALIBUR_LLM_API_KEY, restart the node, and re-ingest."),

    /**
     * Extraction was asked for and a model can be called, but ingestion could not be set up to
     * hand it documents - the pipeline failed to build, or built without an orchestrator.
     *
     * <p>Worth a warning for the same reason as {@link #MISSING_API_KEY}: both settings say
     * extraction runs, so nothing in the configuration explains why no fact is ever staged. The
     * cause is in the startup log, which is where this sends the operator.</p>
     */
    UNAVAILABLE("excalibur.graph.extraction-enabled is true and an LLM API key is configured, "
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
     * @param chatModel         Whether a chat model can be called, which is what a key means
     * @param ingestionWired    Whether ingestion was set up to hand published documents to the
     *                          extractor
     * @return The state
     */
    public static GraphExtraction of(boolean extractionEnabled, boolean chatModel,
                                     boolean ingestionWired) {
        if (!extractionEnabled) {
            return DISABLED;
        }
        if (!chatModel) {
            return MISSING_API_KEY;
        }
        return ingestionWired ? ACTIVE : UNAVAILABLE;
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
        return this == MISSING_API_KEY || this == UNAVAILABLE
            ? Optional.of(nothingStaged)
            : Optional.empty();
    }
}
