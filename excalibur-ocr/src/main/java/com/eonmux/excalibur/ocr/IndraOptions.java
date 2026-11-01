// Ported from Indra OCR 1.1.2, __init__.py and cli.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr;

import java.util.Objects;

/**
 * How documents are read: the options of Indra's command line, with its defaults.
 *
 * @param mode          Where a page's text is read from
 * @param dpi           The resolution pages are rendered at for OCR
 * @param math          Whether lines holding math are read as LaTeX, which takes the math models
 * @param keepFurniture Whether page headers and footers are kept in the markdown
 * @param imageOcr      Whether text inside pictures on text-layer pages is OCRed
 * @param threads       How many pages are read at once; 0 for every core this process may use
 */
public record IndraOptions(ReadMode mode, double dpi, boolean math, boolean keepFurniture,
                           boolean imageOcr, int threads) {

    /** Indra's default resolution. */
    public static final double DEFAULT_DPI = 200.0;

    /**
     * @throws IllegalArgumentException if the resolution is not between 72 and 600 or the
     *                                  thread count is negative
     */
    public IndraOptions {
        Objects.requireNonNull(mode, "mode must not be null");
        if (!(dpi >= 72 && dpi <= 600)) {
            throw new IllegalArgumentException("dpi must be between 72 and 600, got " + dpi);
        }
        if (threads < 0) {
            throw new IllegalArgumentException("threads must be 0 or more, got " + threads);
        }
    }

    /**
     * Indra's defaults: auto mode at 200 dpi, no math, furniture dropped, pictures OCRed, every
     * core.
     *
     * @return The defaults
     */
    public static IndraOptions defaults() {
        return new IndraOptions(ReadMode.AUTO, DEFAULT_DPI, false, false, true, 0);
    }

    /**
     * The number of pages read at once.
     *
     * @return {@link #threads}, or the processors this process may use when it is 0
     */
    public int effectiveThreads() {
        return threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
    }

    /**
     * The options that change what is written, as one line; the thread count is left out,
     * since it changes only how fast.
     *
     * @return For example {@code mode=auto dpi=200 math=off furniture=drop image-ocr=on}
     */
    public String describe() {
        String resolution = dpi == Math.rint(dpi) ? String.valueOf((long) dpi)
            : String.valueOf(dpi);
        return "mode=" + mode.label() + " dpi=" + resolution + " math=" + (math ? "on" : "off")
            + " furniture=" + (keepFurniture ? "keep" : "drop")
            + " image-ocr=" + (imageOcr ? "on" : "off");
    }
}
