// Reproduces OpenCV's documented interpolation modes (cv::InterpolationFlags) for 8-bit images.
package com.eonmux.excalibur.ocr.image;

/**
 * How {@link Resize} computes a pixel of the resized image: the two modes Indra OCR uses.
 */
public enum Interpolation {

    /**
     * Bilinear, OpenCV's {@code INTER_LINEAR}: each pixel blends the two nearest source pixels
     * in each direction, in 11-bit fixed point. An exact halving in both directions is done as
     * {@link #AREA} instead, as OpenCV does.
     */
    LINEAR,

    /**
     * Pixel-area averaging, OpenCV's {@code INTER_AREA}: when shrinking, each pixel is the mean
     * of the source area it covers. When enlarging in either direction, OpenCV emulates it with
     * a bilinear variant whose weights snap to the source pixel grid.
     */
    AREA
}
