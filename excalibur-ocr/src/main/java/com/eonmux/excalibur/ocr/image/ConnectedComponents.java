// Reproduces the documented behaviour of OpenCV 5.0's cv::connectedComponentsWithStats
// (imgproc/src/connectedcomponents.cpp) on 8-bit masks with 32-bit labels.
package com.eonmux.excalibur.ocr.image;

import java.util.Arrays;
import java.util.Objects;

/**
 * Labels the connected regions of a binary mask and measures them, numbered exactly as OpenCV
 * 5.0's {@code cv2.connectedComponentsWithStats} numbers them.
 *
 * <p>Label 0 is the background; regions are numbered from 1. OpenCV's default algorithms
 * (Spaghetti for 8-connectivity, Spaghetti4C for 4-connectivity, and their striped parallel
 * forms) create a provisional label whenever a scan unit touches no labelled one, merge them
 * keeping the smaller, and renumber the survivors in creation order. A region's number is
 * therefore the rank of the first scan unit it occupies:</p>
 * <ul>
 *   <li>4-connectivity scans single pixels in raster order;</li>
 *   <li>8-connectivity scans 2x2 blocks (rows {@code 2k} and {@code 2k + 1}, left to right),
 *       so a region starting at the left of row 1 is numbered before one starting further
 *       right on row 0.</li>
 * </ul>
 * <p>This class labels with union-find and numbers regions by that rule.</p>
 */
public final class ConnectedComponents {

    /** Which neighbours touch. */
    public enum Connectivity {
        /** Left, right, up and down. */
        FOUR,
        /** The four of {@link #FOUR} and the four diagonals. */
        EIGHT
    }

    private ConnectedComponents() {
        throw new AssertionError("Static methods only");
    }

    /**
     * Labels a mask image in which every nonzero pixel is foreground, as OpenCV reads it.
     *
     * @param mask         The mask
     * @param connectivity Which neighbours touch
     * @return The labels and statistics
     */
    public static Components label(GrayImage mask, Connectivity connectivity) {
        Objects.requireNonNull(mask, "mask must not be null");
        byte[] raw = mask.raw();
        boolean[] foreground = new boolean[raw.length];
        for (int i = 0; i < raw.length; i++) {
            foreground[i] = raw[i] != 0;
        }
        return label(foreground, mask.width(), mask.height(), connectivity);
    }

    /**
     * Labels a mask given as one flag per pixel.
     *
     * @param mask         {@code width * height} flags, row by row; true is foreground
     * @param width        Columns, at least 1
     * @param height       Rows, at least 1
     * @param connectivity Which neighbours touch
     * @return The labels and statistics
     * @throws IllegalArgumentException if the size does not match the mask
     */
    public static Components label(boolean[] mask, int width, int height,
                                   Connectivity connectivity) {
        Objects.requireNonNull(mask, "mask must not be null");
        Objects.requireNonNull(connectivity, "connectivity must not be null");
        if (width < 1 || height < 1 || (long) width * height != mask.length) {
            throw new IllegalArgumentException("a " + width + "x" + height + " mask needs "
                + ((long) width * height) + " flags, got " + mask.length);
        }
        int[] parent = provisional(mask, width, height, connectivity == Connectivity.EIGHT);
        int[] labels = new int[mask.length];
        int count = connectivity == Connectivity.EIGHT
            ? numberByBlocks(mask, width, height, parent, labels)
            : numberByPixels(mask, parent, labels);
        return Components.measure(labels, width, height, count);
    }

    /** Union-find over pixels: each foreground pixel's parent, -1 for background. */
    private static int[] provisional(boolean[] mask, int width, int height, boolean eight) {
        int[] parent = new int[mask.length];
        Arrays.fill(parent, -1);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                if (!mask[i]) {
                    continue;
                }
                parent[i] = i;
                if (x > 0 && mask[i - 1]) {
                    union(parent, i, i - 1);
                }
                if (y > 0) {
                    int up = i - width;
                    if (mask[up]) {
                        union(parent, i, up);
                    }
                    if (eight && x > 0 && mask[up - 1]) {
                        union(parent, i, up - 1);
                    }
                    if (eight && x + 1 < width && mask[up + 1]) {
                        union(parent, i, up + 1);
                    }
                }
            }
        }
        return parent;
    }

    private static int find(int[] parent, int i) {
        int root = i;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[i] != root) {
            int next = parent[i];
            parent[i] = root;
            i = next;
        }
        return root;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }

    private static int numberByPixels(boolean[] mask, int[] parent, int[] labels) {
        int[] number = new int[mask.length];
        int next = 1;
        for (int i = 0; i < mask.length; i++) {
            if (mask[i]) {
                int root = find(parent, i);
                if (number[root] == 0) {
                    number[root] = next++;
                }
                labels[i] = number[root];
            }
        }
        return next;
    }

    private static int numberByBlocks(boolean[] mask, int width, int height, int[] parent,
                                      int[] labels) {
        int[] number = new int[mask.length];
        int next = 1;
        for (int by = 0; by < height; by += 2) {
            for (int bx = 0; bx < width; bx += 2) {
                for (int y = by; y < Math.min(by + 2, height); y++) {
                    for (int x = bx; x < Math.min(bx + 2, width); x++) {
                        int i = y * width + x;
                        if (mask[i]) {
                            int root = find(parent, i);
                            if (number[root] == 0) {
                                number[root] = next++;
                            }
                            labels[i] = number[root];
                        }
                    }
                }
            }
        }
        return next;
    }
}
