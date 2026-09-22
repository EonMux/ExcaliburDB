package com.eonmux.excalibur.core.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Codec for IEEE 754 double-precision values.
 * Uses little-endian byte order.
 *
 * <h2>Thread Safety</h2>
 * <p>All methods are thread-safe. The singleton instance is immutable.</p>
 *
 * @author ExcaliburDB
 */
public class DoubleCodec implements Codec<Double> {

    private static final DoubleCodec INSTANCE = new DoubleCodec();
    private static final int SIZE = 8;

    private DoubleCodec() {
        // Private constructor for singleton
    }

    /**
     * Gets the singleton instance.
     *
     * @return The DoubleCodec instance
     */
    public static DoubleCodec getInstance() {
        return INSTANCE;
    }

    @Override
    public long sizeOf() {
        return SIZE;
    }

    @Override
    public Codec<Double> instance() {
        return INSTANCE;
    }

    @Override
    public byte[] encode(Double value) {
        if (value == null) {
            // Encode NaN as default for null
            ByteBuffer buffer = ByteBuffer.allocate(SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
            buffer.putDouble(Double.NaN);
            return buffer.array();
        }

        ByteBuffer buffer = ByteBuffer.allocate(SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putDouble(value);
        return buffer.array();
    }

    @Override
    public Double decode(byte[] data) {
        if (data == null || data.length == 0) {
            return Double.NaN;
        }
        if (!isValid(data)) {
            throw new IllegalArgumentException("Invalid encoded data: insufficient bytes");
        }

        return ByteBuffer.wrap(data, 0, SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getDouble();
    }

    @Override
    public Double decode(byte[] data, int offset) {
        if (data == null || offset < 0) {
            throw new IllegalArgumentException("Invalid data or offset");
        }
        if (!isValid(data, offset)) {
            throw new IllegalArgumentException("Invalid encoded data at offset: " + offset);
        }

        return ByteBuffer.wrap(data, offset, SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getDouble();
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
        // Must have at least 8 bytes
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
