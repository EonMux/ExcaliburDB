package io.excalibur.core.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Codec for raw byte data with length encoding.
 * Bytes are encoded as a 4-byte length prefix (big-endian) followed by the raw bytes.
 *
 * <h2>Thread Safety</h2>
 * <p>All methods are thread-safe. The singleton instance is immutable.</p>
 *
 * @author ExcaliburDB
 */
public class ByteCodec implements Codec<byte[]> {

    private static final ByteCodec INSTANCE = new ByteCodec();
    private static final int LENGTH_PREFIX_SIZE = 4;

    private ByteCodec() {
        // Private constructor for singleton
    }

    /**
     * Gets the singleton instance.
     *
     * @return The ByteCodec instance
     */
    public static ByteCodec getInstance() {
        return INSTANCE;
    }

    @Override
    public long sizeOf() {
        // Variable size - returns minimum size
        return LENGTH_PREFIX_SIZE;
    }

    @Override
    public Codec<byte[]> instance() {
        return INSTANCE;
    }

    @Override
    public byte[] encode(byte[] value) {
        if (value == null) {
            return new byte[LENGTH_PREFIX_SIZE]; // Just length prefix with 0
        }

        ByteBuffer buffer = ByteBuffer.allocate(LENGTH_PREFIX_SIZE + value.length)
            .order(ByteOrder.BIG_ENDIAN);

        buffer.putInt(value.length);
        buffer.put(value);

        return buffer.array();
    }

    /**
     * Encodes a portion of a byte array.
     *
     * @param value  Source byte array
     * @param offset Offset within the array
     * @param length Number of bytes to encode
     * @return Encoded bytes with length prefix
     */
    public byte[] encode(byte[] value, int offset, int length) {
        if (value == null) {
            return new byte[LENGTH_PREFIX_SIZE];
        }
        if (offset < 0 || length < 0 || offset + length > value.length) {
            throw new IllegalArgumentException("Invalid offset or length");
        }

        ByteBuffer buffer = ByteBuffer.allocate(LENGTH_PREFIX_SIZE + length)
            .order(ByteOrder.BIG_ENDIAN);

        buffer.putInt(length);
        buffer.put(value, offset, length);

        return buffer.array();
    }

    @Override
    public byte[] decode(byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        if (!isValid(data)) {
            throw new IllegalArgumentException("Invalid encoded data");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int length = buffer.getInt();

        byte[] result = new byte[length];
        buffer.get(result);
        return result;
    }

    @Override
    public byte[] decode(byte[] data, int offset) {
        if (data == null || offset < 0 || offset >= data.length) {
            throw new IllegalArgumentException("Invalid data or offset");
        }
        if (!isValid(data, offset)) {
            throw new IllegalArgumentException("Invalid encoded data at offset: " + offset);
        }

        ByteBuffer buffer = ByteBuffer.wrap(data, offset, data.length - offset)
            .order(ByteOrder.BIG_ENDIAN);
        int length = buffer.getInt();

        byte[] result = new byte[length];
        buffer.get(result);
        return result;
    }

    @Override
    public boolean isValid(byte[] data) {
        if (data == null) {
            return false;
        }
        if (data.length == 0) {
            return true; // Empty data decodes to empty byte[]
        }
        if (data.length < LENGTH_PREFIX_SIZE) {
            return false;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int length = buffer.getInt();

        // Check that length is non-negative and data contains full payload
        return length >= 0 && data.length >= LENGTH_PREFIX_SIZE + length;
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

        return length >= 0 && data.length >= offset + LENGTH_PREFIX_SIZE + length;
    }

    /**
     * Gets the encoded size for a given byte array.
     *
     * @param value The byte array to encode
     * @return The encoded size in bytes
     */
    public int encodedSize(byte[] value) {
        if (value == null) {
            return LENGTH_PREFIX_SIZE;
        }
        return LENGTH_PREFIX_SIZE + value.length;
    }
}
