package io.excalibur.core.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Codec for IEEE 754 single-precision float values.
 * Uses little-endian byte order.
 *
 * <h2>Thread Safety</h2>
 * <p>All methods are thread-safe. The singleton instance is immutable.</p>
 *
 * @author ExcaliburDB
 */
public class FloatCodec implements Codec<Float> {

    private static final FloatCodec INSTANCE = new FloatCodec();
    private static final int SIZE = 4;

    private FloatCodec() {
        // Private constructor for singleton
    }

    /**
     * Gets the singleton instance.
     *
     * @return The FloatCodec instance
     */
    public static FloatCodec getInstance() {
        return INSTANCE;
    }

    @Override
    public long sizeOf() {
        return SIZE;
    }

    @Override
    public Codec<Float> instance() {
        return INSTANCE;
    }

    @Override
    public byte[] encode(Float value) {
        if (value == null) {
            // Encode NaN as default for null
            ByteBuffer buffer = ByteBuffer.allocate(SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
            buffer.putFloat(Float.NaN);
            return buffer.array();
        }

        ByteBuffer buffer = ByteBuffer.allocate(SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putFloat(value);
        return buffer.array();
    }

    @Override
    public Float decode(byte[] data) {
        if (data == null || data.length == 0) {
            return Float.NaN;
        }
        if (!isValid(data)) {
            throw new IllegalArgumentException("Invalid encoded data: insufficient bytes");
        }

        return ByteBuffer.wrap(data, 0, SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getFloat();
    }

    @Override
    public Float decode(byte[] data, int offset) {
        if (data == null || offset < 0) {
            throw new IllegalArgumentException("Invalid data or offset");
        }
        if (!isValid(data, offset)) {
            throw new IllegalArgumentException("Invalid encoded data at offset: " + offset);
        }

        return ByteBuffer.wrap(data, offset, SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getFloat();
    }

    @Override
    public boolean isValid(byte[] data) {
        if (data == null) {
            return false;
        }
        // Allow empty data (decodes to NaN)
        if (data.length == 0) {
            return true;
        }
        // Must have at least 4 bytes
        return data.length >= SIZE;
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
        if (offset + SIZE > data.length) {
            return false;
        }
        return true;
    }
}
