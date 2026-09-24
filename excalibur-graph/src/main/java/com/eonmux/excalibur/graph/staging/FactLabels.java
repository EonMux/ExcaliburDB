package com.eonmux.excalibur.graph.staging;

import java.util.Locale;

/**
 * The one definition of what an entity or relation label may be before it is staged.
 *
 * <p>Two things stage facts: the extractor, reading labels out of a model's reply, and
 * {@code POST /v1/graph/facts}, reading them out of a request. They must agree, or the same
 * relation asserted by a person and extracted from a document lands under two spellings, and the
 * graph can no longer be traversed by it; and a label one of them would refuse could reach the
 * interner through the other.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless.</p>
 *
 * @author ExcaliburDB
 */
public final class FactLabels {

    /**
     * Longest label, in characters, after cleaning.
     *
     * <p>Far below what the interner itself accepts, {@link LabelInterner#MAX_LABEL_BYTES}: a
     * name is a few words, and something longer is a sentence the extractor mistook for one - or,
     * through the API, a request trying to store prose where a name belongs.</p>
     */
    public static final int MAX_LABEL_CHARS = 200;

    private FactLabels() {
    }

    /**
     * An entity label the graph can use.
     *
     * <p>Only surrounding white space is removed. Case is kept, because it is part of a name:
     * "Apple" the company and "apple" the fruit are different entities.</p>
     *
     * <p>The placeholder an erasure leaves in place of a name is refused, because it is still
     * interned under the erased id: a fact staged under it would give that entity facts again.
     * See {@link LabelInterner#isErasedLabel}.</p>
     *
     * @param label The label as given
     * @return The cleaned label, or null when it is absent, blank, too long or an erasure's
     *         placeholder
     */
    public static String entity(String label) {
        if (label == null) {
            return null;
        }
        String cleaned = label.strip();
        if (cleaned.isEmpty() || cleaned.length() > MAX_LABEL_CHARS
            || LabelInterner.isErasedLabel(cleaned)) {
            return null;
        }
        return cleaned;
    }

    /**
     * A relation label, lower-cased with spaces as underscores, so that "Worked With" and
     * "worked_with" are one relation.
     *
     * <p>Relations are a controlled vocabulary in everything but name: a graph in which the same
     * relationship appears under three spellings cannot be traversed by relation at all.</p>
     *
     * @param label The label as given
     * @return The cleaned label, or null when it is absent, blank or too long
     */
    public static String relation(String label) {
        if (label == null) {
            return null;
        }
        String cleaned = label.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (cleaned.isEmpty() || cleaned.length() > MAX_LABEL_CHARS) {
            return null;
        }
        return cleaned;
    }
}
