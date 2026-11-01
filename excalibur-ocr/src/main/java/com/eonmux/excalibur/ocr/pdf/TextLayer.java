// Ported from Indra OCR 1.1.2, textlayer.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pdf;

import java.util.List;
import java.util.Objects;

/**
 * A page's text layer and the verdict on it: Indra's {@code TextLayer}.
 *
 * @param lines   Upright runs, in PDF content order
 * @param width   Page width in points, as rendered in the layer's frame
 * @param height  Page height in points, as rendered in the layer's frame
 * @param trusted Whether the layer can be read in place of OCR
 * @param reason  Why it is not trusted; empty when trusted
 * @param stats   The counts behind the verdict
 * @param rotated Runs at other quarter turns
 */
public record TextLayer(List<TextLine> lines, double width, double height, boolean trusted,
                        String reason, TextStats stats, List<TextLine> rotated) {

    public TextLayer {
        lines = List.copyOf(lines);
        rotated = List.copyOf(rotated);
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(stats, "stats must not be null");
    }

    /**
     * The same layer with other statistics, as Indra's document reader amends them.
     *
     * @param newStats The statistics
     * @return The layer
     */
    public TextLayer withStats(TextStats newStats) {
        return new TextLayer(lines, width, height, trusted, reason, newStats, rotated);
    }
}
