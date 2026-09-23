package com.eonmux.excalibur.bench.retrieval;

import com.eonmux.excalibur.config.IngestConfig;

import java.util.List;
import java.util.Locale;

/**
 * Which lexical engine indexed the corpus for one pass of the comparison.
 *
 * <p>The identifiers are the node's own {@code excalibur.ingest.lexical-engine} values, so the
 * engine the harness indexes into directly and the one the evaluation node is configured with
 * are the same engine by construction rather than by two spellings kept in step.
 */
enum LexicalEngine {
    NATIVE(IngestConfig.LEXICAL_ENGINE_NATIVE, "chunk-index"),
    LUCENE(IngestConfig.LEXICAL_ENGINE_LUCENE, "lucene-chunks");

    private final String id;
    private final String directory;

    LexicalEngine(String id, String directory) {
        this.id = id;
        this.directory = directory;
    }

    /** The engine's name, as a node's configuration spells it. */
    String id() {
        return id;
    }

    /** Where under the index root the harness's own index for this engine is written. */
    String directory() {
        return directory;
    }

    /** Where under the index root the evaluation node for this engine keeps its data. */
    String nodeDirectory() {
        return "node-" + id;
    }

    /**
     * The engines a run was asked for: one by name, or {@code both}.
     *
     * @throws IllegalArgumentException if the argument names neither
     */
    static List<LexicalEngine> parse(String argument) {
        String value = argument.trim().toLowerCase(Locale.ROOT);
        if ("both".equals(value)) {
            return List.of(NATIVE, LUCENE);
        }
        for (LexicalEngine engine : values()) {
            if (engine.id.equals(value)) {
                return List.of(engine);
            }
        }
        throw new IllegalArgumentException(
            "unknown engine '" + argument + "'; expected native, lucene or both");
    }
}
