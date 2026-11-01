package com.eonmux.excalibur.ocr.array;

import java.util.Arrays;
import java.util.Objects;

/**
 * A numpy array as read from a {@code .npy} file: its element type, its shape and its values in
 * C order.
 *
 * <p>Only the element types the OCR models and their test fixtures use are read; see
 * {@link Npz}. The values are held as the widest Java type that represents them exactly:
 * {@code float32} as {@code float[]}, {@code float64} as {@code double[]}, every integer type up
 * to 64 bits as {@code long[]} (an unsigned 64-bit value is kept as its bit pattern), and
 * {@code bool} as {@code boolean[]}.</p>
 *
 * @param dtype The numpy type string without its byte order, such as {@code f4}, {@code u8},
 *              {@code i8} or {@code b1}
 * @param shape The dimensions; empty for a scalar
 * @param data  The values: a {@code float[]}, {@code double[]}, {@code long[]} or
 *              {@code boolean[]}
 */
public record NdArray(String dtype, int[] shape, Object data) {

    public NdArray {
        Objects.requireNonNull(dtype, "dtype must not be null");
        Objects.requireNonNull(shape, "shape must not be null");
        Objects.requireNonNull(data, "data must not be null");
        shape = shape.clone();
        int expected = size(shape);
        int actual = switch (data) {
            case float[] f -> f.length;
            case double[] d -> d.length;
            case long[] l -> l.length;
            case boolean[] b -> b.length;
            default -> throw new IllegalArgumentException("unsupported data "
                + data.getClass().getSimpleName());
        };
        if (actual != expected) {
            throw new IllegalArgumentException("shape " + Arrays.toString(shape) + " holds "
                + expected + " values, got " + actual);
        }
    }

    /**
     * How many values a shape holds.
     *
     * @param shape The dimensions
     * @return Their product, 1 for a scalar
     */
    public static int size(int[] shape) {
        long n = 1;
        for (int d : shape) {
            n *= d;
        }
        return Math.toIntExact(n);
    }

    public int[] shape() {
        return shape.clone();
    }

    /**
     * The values as floats.
     *
     * @return A copy of the values
     * @throws IllegalStateException if the array is not {@code float32}
     */
    public float[] floats() {
        if (data instanceof float[] f) {
            return f.clone();
        }
        throw new IllegalStateException("a " + dtype + " array is not float32");
    }

    /**
     * The values as doubles, from any floating-point or integer array.
     *
     * @return The values
     * @throws IllegalStateException if the array is {@code bool}
     */
    public double[] doubles() {
        return switch (data) {
            case double[] d -> d.clone();
            case float[] f -> {
                double[] out = new double[f.length];
                for (int i = 0; i < f.length; i++) {
                    out[i] = f[i];
                }
                yield out;
            }
            case long[] l -> {
                double[] out = new double[l.length];
                for (int i = 0; i < l.length; i++) {
                    out[i] = l[i];
                }
                yield out;
            }
            default -> throw new IllegalStateException("a " + dtype + " array is not numeric");
        };
    }

    /**
     * The values as longs.
     *
     * @return A copy of the values
     * @throws IllegalStateException if the array is not of an integer type
     */
    public long[] longs() {
        if (data instanceof long[] l) {
            return l.clone();
        }
        throw new IllegalStateException("a " + dtype + " array is not an integer array");
    }

    /**
     * The values as booleans.
     *
     * @return A copy of the values
     * @throws IllegalStateException if the array is not {@code bool}
     */
    public boolean[] booleans() {
        if (data instanceof boolean[] b) {
            return b.clone();
        }
        throw new IllegalStateException("a " + dtype + " array is not bool");
    }

    /**
     * A scalar integer, such as a model's order or vocabulary size.
     *
     * @return The one value
     * @throws IllegalStateException if the array holds other than one integer
     */
    public long scalarLong() {
        long[] values = longs();
        if (values.length != 1) {
            throw new IllegalStateException("not a scalar: shape " + Arrays.toString(shape));
        }
        return values[0];
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NdArray other)) {
            return false;
        }
        return dtype.equals(other.dtype) && Arrays.equals(shape, other.shape)
            && Arrays.deepEquals(new Object[] {data}, new Object[] {other.data});
    }

    @Override
    public int hashCode() {
        return 31 * (31 * dtype.hashCode() + Arrays.hashCode(shape))
            + Arrays.deepHashCode(new Object[] {data});
    }

    @Override
    public String toString() {
        return "NdArray[" + dtype + " " + Arrays.toString(shape) + "]";
    }
}
