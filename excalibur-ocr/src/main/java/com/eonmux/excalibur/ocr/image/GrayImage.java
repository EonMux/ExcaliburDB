package com.eonmux.excalibur.ocr.image;

import java.util.Arrays;
import java.util.Objects;

/**
 * An 8-bit grayscale image, rows top to bottom, 0 black and 255 white.
 *
 * <p>The page and line images the OCR pipeline works on. Immutable: every operation returns a new
 * image, so one page can be read by several threads at once.</p>
 */
public final class GrayImage {

    private final int width;
    private final int height;
    private final byte[] pixels;

    private GrayImage(int width, int height, byte[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    /**
     * An image over a copy of the given pixels.
     *
     * @param width  Columns, at least 1
     * @param height Rows, at least 1
     * @param pixels {@code width * height} values, row by row
     * @return The image
     * @throws IllegalArgumentException if a dimension is not positive or the pixel count differs
     */
    public static GrayImage of(int width, int height, byte[] pixels) {
        Objects.requireNonNull(pixels, "pixels must not be null");
        requireSize(width, height, pixels.length);
        return new GrayImage(width, height, pixels.clone());
    }

    /**
     * An image of one value.
     *
     * @param width  Columns, at least 1
     * @param height Rows, at least 1
     * @param value  The gray level, 0 to 255
     * @return The image
     */
    public static GrayImage filled(int width, int height, int value) {
        requireSize(width, height, (long) width * height);
        byte[] pixels = new byte[width * height];
        Arrays.fill(pixels, (byte) value);
        return new GrayImage(width, height, pixels);
    }

    /**
     * Takes ownership of an array no one else holds, for operations that built it themselves.
     *
     * @param width  Columns
     * @param height Rows
     * @param pixels The pixels, not to be modified after this call
     * @return The image
     */
    static GrayImage adopt(int width, int height, byte[] pixels) {
        requireSize(width, height, pixels.length);
        return new GrayImage(width, height, pixels);
    }

    private static void requireSize(int width, int height, long length) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("an image is at least 1x1, got " + width + "x"
                + height);
        }
        if ((long) width * height != length) {
            throw new IllegalArgumentException(width + "x" + height + " needs "
                + ((long) width * height) + " pixels, got " + length);
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * One pixel.
     *
     * @param x Column
     * @param y Row
     * @return Its gray level, 0 to 255
     */
    public int get(int x, int y) {
        Objects.checkIndex(x, width);
        Objects.checkIndex(y, height);
        return pixels[y * width + x] & 0xFF;
    }

    /**
     * A copy of the pixels, row by row.
     *
     * @return The pixels
     */
    public byte[] pixels() {
        return pixels.clone();
    }

    /**
     * Reads the pixels without copying them, for the operations of this package.
     *
     * @return The backing array, not to be modified
     */
    byte[] raw() {
        return pixels;
    }

    /**
     * The image turned by quarter turns, as numpy's {@code rot90(image, k)}.
     *
     * @param k Quarter turns counter-clockwise; negative turns clockwise
     * @return The turned image
     */
    public GrayImage rot90(int k) {
        int turns = Math.floorMod(k, 4);
        if (turns == 0) {
            return this;
        }
        int outWidth = turns % 2 == 0 ? width : height;
        int outHeight = turns % 2 == 0 ? height : width;
        byte[] out = new byte[pixels.length];
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                int sx;
                int sy;
                switch (turns) {
                    case 1 -> {
                        sx = width - 1 - y;
                        sy = x;
                    }
                    case 2 -> {
                        sx = width - 1 - x;
                        sy = height - 1 - y;
                    }
                    default -> {
                        sx = y;
                        sy = height - 1 - x;
                    }
                }
                out[y * outWidth + x] = pixels[sy * width + sx];
            }
        }
        return new GrayImage(outWidth, outHeight, out);
    }

    /**
     * A rectangle of the image, clipped to it; numpy's {@code image[y0:y1, x0:x1]}.
     *
     * @param x0 First column, inclusive
     * @param y0 First row, inclusive
     * @param x1 Last column, exclusive
     * @param y1 Last row, exclusive
     * @return The region, or null when the clipped rectangle is empty
     */
    public GrayImage region(int x0, int y0, int x1, int y1) {
        int cx0 = Math.max(0, x0);
        int cy0 = Math.max(0, y0);
        int cx1 = Math.min(width, x1);
        int cy1 = Math.min(height, y1);
        if (cx1 <= cx0 || cy1 <= cy0) {
            return null;
        }
        int w = cx1 - cx0;
        byte[] out = new byte[w * (cy1 - cy0)];
        for (int y = cy0; y < cy1; y++) {
            System.arraycopy(pixels, y * width + cx0, out, (y - cy0) * w, w);
        }
        return new GrayImage(w, cy1 - cy0, out);
    }

    /**
     * The mean gray level.
     *
     * @return The mean of every pixel
     */
    public double mean() {
        long sum = 0;
        for (byte p : pixels) {
            sum += p & 0xFF;
        }
        return (double) sum / pixels.length;
    }

    /**
     * The image with every pixel inverted, {@code 255 - p}.
     *
     * @return The inverted image
     */
    public GrayImage inverted() {
        byte[] out = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            out[i] = (byte) (255 - (pixels[i] & 0xFF));
        }
        return new GrayImage(width, height, out);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GrayImage other && width == other.width && height == other.height
            && Arrays.equals(pixels, other.pixels);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * width + height) + Arrays.hashCode(pixels);
    }

    @Override
    public String toString() {
        return "GrayImage[" + width + "x" + height + "]";
    }
}
