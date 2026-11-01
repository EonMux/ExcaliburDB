// Reproduces OpenCV's documented border modes (cv::BorderTypes) used by warpAffine.
package com.eonmux.excalibur.ocr.image;

/**
 * What {@link WarpAffine} reads for a source pixel outside the image.
 */
public enum BorderMode {

    /** Black, value 0: OpenCV's {@code BORDER_CONSTANT} with its default border value. */
    CONSTANT,

    /** The nearest edge pixel: OpenCV's {@code BORDER_REPLICATE}. */
    REPLICATE
}
