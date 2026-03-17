package io.excalibur.core.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Codec for 64-bit long values.
 * Uses big-endian byte order.
 *
 * <h2>Thread Safety</h2>
 * <p>All methods are thread-safe. The singleton instance is immutable.</p>
 *
 * @author ExcaliburDB
 */
public class LongCodec implements Codec<Long> {

    private static final LongCodec INSTANCE = new LongCodec();
    private static final int SIZE = 8;

    private LongCodec() {
        // Private constructor for singleton
    }

    /**
     * Gets the singleton instance.
     *
     * @return The LongCodec instance
     */
    public static LongCodec getInstance() {
        return INSTANCE;
    }

    @Override
    public long sizeOf() {
        return SIZE;
    }

    @Override
    public Codec<Long> instance() {
        return INSTANCE;
    }

    @Override
    public byte[] encode(Long value) {
        if (value == null) {
            // Encode 0 as default for null
            ByteBuffer buffer = ByteBuffer.allocate(SIZE)
                .order(ByteOrder.BIG_ENDIAN);
            buffer.putLong(0L);
            return buffer.array();
        }

        ByteBuffer buffer = ByteBuffer.allocate(SIZE)
            .order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(value);
        return buffer.array();
    }

    @Override
    public Long decode(byte[] data) {
        if (data == null || data.length == 0) {
            return 0L;
        }
        if (!isValid(data)) {
            throw new IllegalArgumentException("Invalid encoded data: insufficient bytes");
        }

        return ByteBuffer.wrap(data, 0, SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .getLong();
    }

    @Override
    public Long decode(byte[] data, int offset) {
        if (data == null || offset < 0) {
            throw new IllegalArgumentException("Invalid data or offset");
        }
        if (!isValid(data, offset)) {
            throw new IllegalArgumentException("Invalid encoded data at offset: " + offset);
        }

        return ByteBuffer.wrap(data, offset, SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .getLong();
    }

    @Override
    public boolean isValid(byte[] data) {
        if (data == null) {
            return false;
        }
        // Allow empty data (decodes to 0)
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
