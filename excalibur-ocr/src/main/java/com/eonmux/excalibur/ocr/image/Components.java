// Reproduces the documented output of OpenCV 5.0's cv::connectedComponentsWithStats
// (CCStatsOp in imgproc/src/connectedcomponents.cpp).
package com.eonmux.excalibur.ocr.image;

import java.util.Arrays;
import java.util.Objects;

/**
 * The result of {@link ConnectedComponents#label}: a label per pixel and, per label, its
 * bounding box and pixel count, OpenCV's {@code CC_STAT_LEFT}, {@code TOP}, {@code WIDTH},
 * {@code HEIGHT} and {@code AREA}.
 *
 * <p>Label 0 is the background and has statistics like any other. A label with no pixels -
 * the background of an all-foreground mask - reports what OpenCV reports: left -1, top
 * {@link Integer#MAX_VALUE}, width, height and area 0.</p>
 */
public final class Components {

    private final int count;
    private final int width;
    private final int height;
    private final int[] labels;
    private final int[] stats;

    private Components(int count, int width, int height, int[] labels, int[] stats) {
        this.count = count;
        this.width = width;
        this.height = height;
        this.labels = labels;
        this.stats = stats;
    }

    /**
     * Measures labelled pixels.
     *
     * @param labels A label per pixel, from 0 to {@code count - 1}; kept, not copied
     * @param width  Columns
     * @param height Rows
     * @param count  Labels including the background
     * @return The components
     */
    static Components measure(int[] labels, int width, int height, int count) {
        int[] minX = new int[count];
        int[] minY = new int[count];
        int[] maxX = new int[count];
        int[] maxY = new int[count];
        int[] area = new int[count];
        Arrays.fill(minX, Integer.MAX_VALUE);
        Arrays.fill(minY, Integer.MAX_VALUE);
        Arrays.fill(maxX, Integer.MIN_VALUE);
        Arrays.fill(maxY, Integer.MIN_VALUE);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int l = labels[y * width + x];
                minX[l] = Math.min(minX[l], x);
                minY[l] = Math.min(minY[l], y);
                maxX[l] = Math.max(maxX[l], x);
                maxY[l] = Math.max(maxY[l], y);
                area[l]++;
            }
        }
        int[] stats = new int[count * 5];
        for (int l = 0; l < count; l++) {
            boolean empty = area[l] == 0;
            stats[l * 5] = empty ? -1 : minX[l];
            stats[l * 5 + 1] = minY[l];
            stats[l * 5 + 2] = empty ? 0 : maxX[l] - minX[l] + 1;
            stats[l * 5 + 3] = empty ? 0 : maxY[l] - minY[l] + 1;
            stats[l * 5 + 4] = area[l];
        }
        return new Components(count, width, height, labels, stats);
    }

    /**
     * How many labels there are, the background included: OpenCV's return value.
     *
     * @return The label count, at least 1
     */
    public int count() {
        return count;
    }

    /**
     * The mask's columns.
     *
     * @return The width
     */
    public int width() {
        return width;
    }

    /**
     * The mask's rows.
     *
     * @return The height
     */
    public int height() {
        return height;
    }

    /**
     * The label of one pixel.
     *
     * @param x Column
     * @param y Row
     * @return Its label, 0 for background
     */
    public int label(int x, int y) {
        Objects.checkIndex(x, width);
        Objects.checkIndex(y, height);
        return labels[y * width + x];
    }

    /**
     * A copy of the label image, row by row.
     *
     * @return The labels
     */
    public int[] labels() {
        return labels.clone();
    }

    /**
     * The leftmost column of a label: {@code CC_STAT_LEFT}.
     *
     * @param label The label
     * @return Its left edge
     */
    public int left(int label) {
        return stat(label, 0);
    }

    /**
     * The top row of a label: {@code CC_STAT_TOP}.
     *
     * @param label The label
     * @return Its top edge
     */
    public int top(int label) {
        return stat(label, 1);
    }

    /**
     * The width of a label's bounding box: {@code CC_STAT_WIDTH}.
     *
     * @param label The label
     * @return Its width in pixels
     */
    public int boxWidth(int label) {
        return stat(label, 2);
    }

    /**
     * The height of a label's bounding box: {@code CC_STAT_HEIGHT}.
     *
     * @param label The label
     * @return Its height in pixels
     */
    public int boxHeight(int label) {
        return stat(label, 3);
    }

    /**
     * How many pixels carry a label: {@code CC_STAT_AREA}.
     *
     * @param label The label
     * @return Its pixel count
     */
    public int area(int label) {
        return stat(label, 4);
    }

    private int stat(int label, int column) {
        Objects.checkIndex(label, count);
        return stats[label * 5 + column];
    }

    @Override
    public String toString() {
        return "Components[" + (count - 1) + " in " + width + "x" + height + "]";
    }
}
