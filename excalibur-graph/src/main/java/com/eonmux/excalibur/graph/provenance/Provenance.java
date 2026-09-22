package com.eonmux.excalibur.graph.provenance;

import java.util.Locale;

/**
 * Where one edge came from and how much to trust it.
 *
 * <p>This is a view over four independent columns, materialised only when a caller asks about a
 * specific edge — traversal never builds one. It exists so that the answer to "why does the system
 * believe this" is a stored fact rather than a reconstruction, which is what makes an answer
 * auditable and a bad extractor's output revocable.
 *
 * @param sourceType       what kind of thing the edge was extracted from
 * @param sourceId         which specific document, statement or turn
 * @param confidence       extractor confidence, 0-100
 * @param extractorVersion which extractor build produced it
 */
public record Provenance(
    SourceType sourceType,
    long sourceId,
    int confidence,
    int extractorVersion
) {

    /** Highest confidence an extractor can report. Confidence is quantised to fit one byte. */
    public static final int MAX_CONFIDENCE = 100;

    /** Provenance for an edge whose origin was never recorded. */
    public static final Provenance UNKNOWN =
        new Provenance(SourceType.UNKNOWN, 0L, 0, 0);

    public Provenance {
        if (sourceType == null) {
            throw new IllegalArgumentException("sourceType must not be null");
        }
        if (confidence < 0 || confidence > MAX_CONFIDENCE) {
            throw new IllegalArgumentException(
                "confidence must be 0.." + MAX_CONFIDENCE + ", got " + confidence);
        }
        if (extractorVersion < Short.MIN_VALUE || extractorVersion > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                "extractorVersion must fit in a short, got " + extractorVersion);
        }
    }

    /**
     * Provenance from a confidence expressed as a fraction.
     *
     * <p>Rounds to the nearest whole percent, which is the resolution the column stores. A caller
     * that hands in 0.9995 gets 100, not a value that silently became 99 through truncation.
     */
    public static Provenance of(SourceType sourceType, long sourceId,
                                double confidence, int extractorVersion) {
        if (Double.isNaN(confidence)) {
            throw new IllegalArgumentException("confidence must not be NaN");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException(
                "confidence fraction must be 0.0..1.0, got " + confidence);
        }
        return new Provenance(sourceType, sourceId,
            (int) Math.round(confidence * MAX_CONFIDENCE), extractorVersion);
    }

    /** Confidence as a fraction of 1. */
    public double confidenceFraction() {
        return confidence / (double) MAX_CONFIDENCE;
    }

    /** The confidence byte as stored. */
    public byte confidenceByte() {
        return (byte) confidence;
    }

    /** The extractor version as stored. */
    public short extractorVersionShort() {
        return (short) extractorVersion;
    }

    /**
     * A compact rendering for serialisation into a model's context.
     *
     * <p>Provenance that never reaches the model cannot influence what it says, so this is the
     * point of storing it: the model should be able to distinguish a curated claim from a sentence
     * an extractor guessed at.
     */
    public String toContextString() {
        return sourceType.name().toLowerCase(Locale.ROOT)
            + "#" + sourceId
            + " conf=" + confidence
            + " ext=v" + extractorVersion;
    }
}
