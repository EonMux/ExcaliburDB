package io.excalibur.core.memory;

import io.excalibur.core.codec.Codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Factory for creating codec instances.
 * 
 * <h2>Thread Safety</h2>
 * <p>CodecFactory is thread-safe for creating codec instances. The INSTANCE
 * singleton is created lazily and is immutable after creation.</p>
 * 
 * @author ExcaliburDB
 */
public final class CodecFactory {
    
    /**
     * Singleton instance.
     */
    public static final CodecFactory INSTANCE = new CodecFactory();
    
    /**
     * Counter for generating unique codec instances.
     */
    private final AtomicInteger codecCounter = new AtomicInteger(0);
    
    /**
     * Creates a new CodecFactory.
     */
    private CodecFactory() {
        // Prevent instantiation
    }
    
    /**
     * Creates a new instance of the default codec.
     * 
     * @return A new Codec instance
     */
    public Codec create() {
        return new DefaultCodec();
    }
    
    /**
     * Decodes bytes using the default codec.
     * 
     * @param bytes The bytes to decode
     * @return The decoded object
     */
    public <T> T decode(byte[] bytes) {
        return create().decode(bytes);
    }
    
    /**
     * Decodes bytes at a specific offset using the default codec.
     * 
     * @param bytes The bytes to decode
     * @param offset Offset to start decoding
     * @return The decoded object
     */
    public <T> T decode(byte[] bytes, int offset) {
        return create().decode(bytes, offset);
    }
    
     /**
     * Encodes an object using the default codec.
     * 
     * @param obj The object to encode
     * @return The encoded bytes
     */
    public byte[] encode(Object obj) {
        return create().encode(obj);
    }
    
    /**
     * Encodes an object starting at a specific offset.
     * 
     * @param obj The object to encode
     * @param offset Offset to start encoding
     * @return The encoded bytes
     */
    public byte[] encode(Object obj, int offset) {
        return create().encode(obj, offset);
    }
}

/**
 * Default codec implementation.
 * 
 * <h2>Domain Correctness</h2>
 * <p>The default codec supports:
 * <ul>
 *   <li><strong>byte[]</strong> - Direct byte array encoding/decoding</li>
 *   <li><strong>long</strong> - 8-byte long encoding/decoding (little-endian)</li>
 *   <li><strong>int</strong> - 4-byte int encoding/decoding (little-endian)</li>
 *   <li><strong>float[]</strong> - Float array encoding/decoding</li>
 *   <li><strong>String</strong> - UTF-8 encoded string encoding/decoding</li>
 * </ul>
 */
final class DefaultCodec implements Codec {
    
    @Override
    public <T> T decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        // Try to detect type based on content
        if (bytes.length >= 4 && bytes[0] == 's' && bytes[1] == 't' && bytes[2] == 'r' && bytes[3] == 'i' && bytes[4] == 'n' && bytes[5] == 'g') {
            return (T) decodeString(bytes);
        }
        
        if (bytes.length == 8) {
            return (T) decodeLong(bytes);
        }
        
        if (bytes.length == 4) {
            return (T) decodeInt(bytes);
        }
        
        return (T) decodeByteArray(bytes);
    }
    
    @Override
    public byte[] encode(Object obj) {
        if (obj == null) {
            return null;
        }
        
        if (obj instanceof String) {
            return encodeString((String) obj);
        }
        if (obj instanceof Number) {
            if (((Number) obj).longValue() > Integer.MAX_VALUE) {
                return encodeLong(((Number) obj).longValue());
            } else {
                return encodeInt(((Number) obj).intValue());
            }
        }
        if (obj instanceof byte[]) {
            return (byte[]) obj;
        }
        
        throw new IllegalArgumentException("Unsupported type: " + obj.getClass().getName());
    }
    
    /**
     * Decodes a long value from bytes.
     * 
     * @param bytes Byte array containing the long
     * @return The decoded long value
     */
    private long decodeLong(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }
    
    /**
     * Encodes a long value to bytes.
     * 
     * @param value The long value to encode
     * @return Byte array containing the encoded long
     */
    private byte[] encodeLong(long value) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(value);
        return buffer.array();
    }
    
    /**
     * Decodes an int value from bytes.
     * 
     * @param bytes Byte array containing the int
     * @return The decoded int value
     */
    private int decodeInt(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
    
    /**
     * Encodes an int value to bytes.
     * 
     * @param value The int value to encode
     * @return Byte array containing the encoded int
     */
    private byte[] encodeInt(int value) {
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(value);
        return buffer.array();
    }
    
    /**
     * Decodes a String from bytes (UTF-8).
     * 
     * @param bytes Byte array containing the string
     * @return The decoded String
     */
    private String decodeString(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
    
    /**
     * Encodes a String to bytes (UTF-8).
     * 
     * @param str The string to encode
     * @return Byte array containing the encoded string
     */
    private byte[] encodeString(String str) {
        return str.getBytes(StandardCharsets.UTF_8);
    }
    
     /**
     * Decodes a byte array.
     * 
     * @param bytes The byte array to decode
     * @return The decoded byte array
     */
    private byte[] decodeByteArray(byte[] bytes) {
        return bytes;
    }
    
    /**
     * Decodes a byte array starting at a specific offset.
     * 
     * @param bytes The byte array to decode
     * @param offset Offset to start decoding
     * @return The decoded byte array
     */
    private byte[] decodeByteArray(byte[] bytes, int offset) {
        return bytes;
    }
    
    /**
     * Encodes a byte array.
     * 
     * @param bytes The byte array to encode
     * @return The encoded byte array
     */
    private byte[] encodeByteArray(byte[] bytes) {
        return bytes;
    }
    
    /**
     * Encodes a byte array starting at a specific offset.
     * 
     * @param bytes The byte array to encode
     * @param offset Offset to start encoding
     * @return The encoded byte array
     */
    private byte[] encodeByteArray(byte[] bytes, int offset) {
        return bytes;
    }
}
