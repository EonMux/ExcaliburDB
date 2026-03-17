package io.excalibur.core.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Codec for UTF-8 encoded strings.
 * Strings are encoded as a 4-byte length prefix (big-endian) followed by UTF-8 bytes.
 *
 * <h2>Thread Safety</h2>
 * <p>All methods are thread-safe. The singleton instance is immutable.</p>
 *
 * @author ExcaliburDB
 */
public class StringCodec implements Codec<String> {

    private static final StringCodec INSTANCE = new StringCodec();
    private static final int LENGTH_PREFIX_SIZE = 4;

    private StringCodec() {
        // Private constructor for singleton
    }

    /**
     * Gets the singleton instance.
     *
     * @return The StringCodec instance
     */
    public static StringCodec getInstance() {
        return INSTANCE;
    }

    @Override
    public long sizeOf() {
        // Variable size - returns minimum size
        return LENGTH_PREFIX_SIZE;
    }

    @Override
    public Codec<String> instance() {
        return INSTANCE;
    }

    @Override
    public byte[] encode(String value) {
        if (value == null || value.isEmpty()) {
            ByteBuffer buffer = ByteBuffer.allocate(LENGTH_PREFIX_SIZE)
                .order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(0);
            return buffer.array();
        }

        byte[] utf8Bytes = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(LENGTH_PREFIX_SIZE + utf8Bytes.length)
            .order(ByteOrder.BIG_ENDIAN);

        buffer.putInt(utf8Bytes.length);
        buffer.put(utf8Bytes);

        return buffer.array();
    }

    @Override
    public String decode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        if (!isValid(data)) {
            throw new IllegalArgumentException("Invalid encoded data");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int length = buffer.getInt();

        if (length == 0) {
            return "";
        }

        byte[] utf8Bytes = new byte[length];
        buffer.get(utf8Bytes);
        return new String(utf8Bytes, StandardCharsets.UTF_8);
    }

    @Override
    public String decode(byte[] data, int offset) {
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
            return "";
        }

        byte[] utf8Bytes = new byte[length];
        buffer.get(utf8Bytes);
        return new String(utf8Bytes, StandardCharsets.UTF_8);
    }

    @Override
    public boolean isValid(byte[] data) {
        if (data == null) {
            return false;
        }
        if (data.length == 0) {
            return true; // Empty data decodes to empty string
        }
        if (data.length < LENGTH_PREFIX_SIZE) {
            return false;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int length = buffer.getInt();

        // Check that length is non-negative and data contains full payload
        if (length < 0 || data.length < LENGTH_PREFIX_SIZE + length) {
            return false;
        }

        // Validate UTF-8 encoding
        if (length > 0) {
            try {
                String decoded = new String(data, LENGTH_PREFIX_SIZE, length, StandardCharsets.UTF_8);
                // Verify round-trip (catches malformed input)
                byte[] reencoded = decoded.getBytes(StandardCharsets.UTF_8);
                return reencoded.length == length;
            } catch (Exception e) {
                return false;
            }
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

        if (length < 0 || data.length < offset + LENGTH_PREFIX_SIZE + length) {
            return false;
        }

        // Validate UTF-8 encoding
        if (length > 0) {
            try {
                String decoded = new String(data, offset + LENGTH_PREFIX_SIZE, length, StandardCharsets.UTF_8);
                byte[] reencoded = decoded.getBytes(StandardCharsets.UTF_8);
                return reencoded.length == length;
            } catch (Exception e) {
                return false;
            }
        }

        return true;
    }

    /**
     * Gets the encoded size for a given string.
     *
     * @param value The string to encode
     * @return The encoded size in bytes
     */
    public int encodedSize(String value) {
        if (value == null || value.isEmpty()) {
            return LENGTH_PREFIX_SIZE;
        }
        byte[] utf8Bytes = value.getBytes(StandardCharsets.UTF_8);
        return LENGTH_PREFIX_SIZE + utf8Bytes.length;
    }
}
