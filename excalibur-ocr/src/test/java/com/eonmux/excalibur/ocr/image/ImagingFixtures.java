package com.eonmux.excalibur.ocr.image;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.testing.Fixtures;

import java.util.Map;

/**
 * Reads the imaging fixtures, which pack many variable-sized images end to end in one array.
 */
final class ImagingFixtures {

    private ImagingFixtures() {
        throw new AssertionError("Static methods only");
    }

    static Map<String, NdArray> load(String name) {
        return Fixtures.npz("imaging/" + name);
    }

    /** Hands out consecutive slices of a packed array. */
    static final class Packed {
        private final long[] values;
        private int next;

        Packed(NdArray array) {
            this.values = array.longs();
        }

        GrayImage image(int width, int height) {
            byte[] pixels = new byte[width * height];
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = (byte) values[next + i];
            }
            next += pixels.length;
            return GrayImage.of(width, height, pixels);
        }

        int[] ints(int count) {
            int[] out = new int[count];
            for (int i = 0; i < count; i++) {
                out[i] = (int) values[next + i];
            }
            next += count;
            return out;
        }

        boolean exhausted() {
            return next == values.length;
        }
    }
}
