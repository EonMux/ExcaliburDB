package io.excalibur.core.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Codec for float arrays.
 * Arrays are encoded as a 4-byte element count (big-endian) followed by
 * 4-byte float values (big-endian).
 *
 * <h2>Thread Safety</h2>
 * <p>All methods are thread-safe. The singleton instance is immutable.</p>
 *
 * @author ExcaliburDB
 */
public class FloatArrayCodec implements Codec<float[]> {

    private static final FloatArrayCodec INSTANCE = new FloatArrayCodec();
    private static final int LENGTH_PREFIX_SIZE = 4;
    private static final int FLOAT_SIZE = 4;

    private FloatArrayCodec() {
        // Private constructor for singleton
    }

    /**
     * Gets the singleton instance.
     *
     * @return The FloatArrayCodec instance
     */
    public static FloatArrayCodec getInstance() {
        return INSTANCE;
    }

    @Override
    public long sizeOf() {
        // Variable size - returns minimum size (empty array)
        return LENGTH_PREFIX_SIZE;
    }

    @Override
    public Codec<float[]> instance() {
        return INSTANCE;
    }

    @Override
    public byte[] encode(float[] value) {
        if (value == null || value.length == 0) {
            ByteBuffer buffer = ByteBuffer.allocate(LENGTH_PREFIX_SIZE)
                .order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(0);
            return buffer.array();
        }

        int totalSize = LENGTH_PREFIX_SIZE + (value.length * FLOAT_SIZE);
        ByteBuffer buffer = ByteBuffer.allocate(totalSize)
            .order(ByteOrder.BIG_ENDIAN);

        buffer.putInt(value.length);
        for (float f : value) {
            buffer.putFloat(f);
        }

        return buffer.array();
    }

    @Override
    public float[] decode(byte[] data) {
        if (data == null || data.length == 0) {
            return new float[0];
        }
        if (!isValid(data)) {
            throw new IllegalArgumentException("Invalid encoded data");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int length = buffer.getInt();

        if (length == 0) {
            return new float[0];
        }

        float[] result = new float[length];
        for (int i = 0; i < length; i++) {
            result[i] = buffer.getFloat();
        }
        return result;
    }

    @Override
    public float[] decode(byte[] data, int offset) {
        if (data == null || offset < 0 || offset >= data.length) {
            throw new IllegalArgumentException("Invalid data or offset");
        }
        if (!isValid(data, offset)) {
            throw new IllegalArgumentException("Invalid encoded data at offset: " + offset);
        }

        ByteBuffer buffer = ByteBuffer.wrap(data, offset, data.length - offset)
            .order(ByteOrder.BIG_ENDIAN);
        int length = buffer.getInt();

        if (length == 0) {
            return new float[0];
        }

        float[] result = new float[length];
        for (int i = 0; i < length; i++) {
            result[i] = buffer.getFloat();
        }
        return result;
    }

    @Override
    public boolean isValid(byte[] data) {
        if (data == null) {
            return false;
        }
        if (data.length == 0) {
            return true; // Empty data decodes to empty array
        }
        if (data.length < LENGTH_PREFIX_SIZE) {
            return false;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int length = buffer.getInt();

        // Check that length is non-negative and data contains full payload
        int expectedSize = LENGTH_PREFIX_SIZE + (length * FLOAT_SIZE);
        if (length < 0 || data.length < expectedSize) {
            return false;
        }

        // Validate each float is a valid number (not NaN unless allowed)
        for (int i = 0; i < length; i++) {
            float f = buffer.getFloat();
            // Note: We allow NaN and Infinity as they are valid IEEE 754 values
            // If you want to reject them, uncomment the next line:
            // if (Float.isNaN(f) || Float.isInfinite(f)) return false;
        }

        return true;
    }

    /**
     * Validates encoded data at a specific offset.
     *
     * @param data   Encoded data
     * @param offset Offset to start validation
     * @return true if valid, false otherwise
     */
    public boolean isValid(byte[] data, int offset) {
        if (data == null || offset < 0) {
            return false;
        }
        if (offset >= data.length) {
            return false;
        }
        if (data.length - offset < LENGTH_PREFIX_SIZE) {
            return false;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data, offset, data.length - offset)
            .order(ByteOrder.BIG_ENDIAN);
        int length = buffer.getInt();

        int expectedSize = LENGTH_PREFIX_SIZE + (length * FLOAT_SIZE);
        if (length < 0 || data.length < offset + expectedSize) {
            return false;
        }

        // Validate each float
        for (int i = 0; i < length; i++) {
            buffer.getFloat(); // Just read to validate buffer has data
        }

        return true;
    }

    /**
     * Gets the encoded size for a given float array.
     *
     * @param value The float array to encode
     * @return The encoded size in bytes
     */
    public int encodedSize(float[] value) {
        if (value == null || value.length == 0) {
            return LENGTH_PREFIX_SIZE;
        }
        return LENGTH_PREFIX_SIZE + (value.length * FLOAT_SIZE);
    }
}
