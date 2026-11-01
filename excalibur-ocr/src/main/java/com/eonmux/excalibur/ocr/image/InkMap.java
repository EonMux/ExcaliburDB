package com.eonmux.excalibur.ocr.image;

import java.util.Arrays;
import java.util.Objects;

/**
 * A single-precision image in which 0 is paper and 1 is ink: what the OCR models read.
 *
 * <p>Immutable. Rows top to bottom, as a numpy {@code float32} array of shape {@code [H, W]}.</p>
 */
public final class InkMap {

    private final int width;
    private final int height;
    private final float[] values;

    private InkMap(int width, int height, float[] values) {
        this.width = width;
        this.height = height;
        this.values = values;
    }

    /**
     * An ink map over a copy of the given values.
     *
     * @param width  Columns, at least 1
     * @param height Rows, at least 1
     * @param values {@code width * height} values, row by row
     * @return The ink map
     * @throws IllegalArgumentException if a dimension is not positive or the value count differs
     */
    public static InkMap of(int width, int height, float[] values) {
        Objects.requireNonNull(values, "values must not be null");
        return adopt(width, height, values.clone());
    }

    /**
     * Takes ownership of an array no one else holds, for code that built it itself.
     *
     * @param width  Columns, at least 1
     * @param height Rows, at least 1
     * @param values The values, not to be modified after this call
     * @return The ink map
     */
    public static InkMap adopt(int width, int height, float[] values) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("an ink map is at least 1x1, got " + width + "x"
                + height);
        }
        if ((long) width * height != values.length) {
            throw new IllegalArgumentException(width + "x" + height + " needs "
                + ((long) width * height) + " values, got " + values.length);
        }
        return new InkMap(width, height, values);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * One value.
     *
     * @param x Column
     * @param y Row
     * @return The value there
     */
    public float get(int x, int y) {
        Objects.checkIndex(x, width);
        Objects.checkIndex(y, height);
        return values[y * width + x];
    }

    /**
     * A copy of the values, row by row.
     *
     * @return The values
     */
    public float[] values() {
        return values.clone();
    }

    /**
     * Copies row {@code y} into a destination, for building a model's input batch.
     *
     * @param y      The row
     * @param dest   Where to write
     * @param offset Where in {@code dest} the row starts
     */
    public void copyRow(int y, float[] dest, int offset) {
        Objects.checkIndex(y, height);
        System.arraycopy(values, y * width, dest, offset, width);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof InkMap other && width == other.width && height == other.height
            && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * width + height) + Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return "InkMap[" + width + "x" + height + "]";
    }
}
