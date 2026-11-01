package com.eonmux.excalibur.ocr.lm;

import com.eonmux.excalibur.ocr.testing.Models;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Indra OCR's language models for tests, each read once per test JVM: they are tens of megabytes
 * and several test classes compare against them.
 */
final class LanguageModels {

    /** The fixtures' language model names, in the order {@code beam.npz} numbers them. */
    static final List<String> NAMES = List.of("base", "world", "cjk");

    private static final Map<String, NgramLM> LOADED = new ConcurrentHashMap<>();

    private LanguageModels() {
        throw new AssertionError("Static methods only");
    }

    /**
     * A language model by its fixture name.
     *
     * @param name {@code base} (lm.npz), {@code world} or {@code cjk} (lm_&lt;name&gt;.npz)
     * @return The model
     */
    static NgramLM get(String name) {
        return LOADED.computeIfAbsent(name, n -> {
            String file = n.equals("base") ? "lm.npz" : "lm_" + n + ".npz";
            try {
                return NgramLM.load(Models.dir().resolve(file));
            } catch (IOException e) {
                throw new UncheckedIOException("Reading " + file + " failed", e);
            }
        });
    }
}
