package com.eonmux.excalibur.core.codec;

import java.nio.ByteBuffer;

/**
 * Codec interface for serializing and deserializing data types.
 * Provides a uniform interface for encoding and decoding values.
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations of this interface must be thread-safe. Codec instances are
 * typically stateless singletons and may be used concurrently by multiple threads.
 * The encode and decode operations must not share mutable state between invocations.</p>
 *
 * @param <T> The type being encoded/decoded
 * @author ExcaliburDB
 */
public interface Codec<T> {
    
    /**
     * Returns the encoded size of a value in bytes.
     * 
     * @return Size in bytes
     */
    long sizeOf();
    
    /**
     * Returns a singleton instance of this codec.
     * 
     * @return Codec instance
     */
    Codec<T> instance();
    
    /**
     * Encodes a value into bytes.
     * 
     * @param value The value to encode
     * @return Encoded bytes
     */
    byte[] encode(T value);
    
    /**
     * Decodes bytes into a value.
     * 
     * @param data Encoded bytes
     * @return Decoded value
     * @throws IllegalArgumentException if data is invalid
     */
    T decode(byte[] data);
    
    /**
     * Checks if a byte array represents valid encoded data.
     * 
     * @param data Byte array to check
     * @return true if valid, false otherwise
     */
    boolean isValid(byte[] data);
    
    /**
     * Decodes a value from a specific offset in the data.
     * 
     * @param data Encoded bytes
     * @param offset Offset to start decoding
     * @return Decoded value
     * @throws IllegalArgumentException if offset is invalid or data is malformed
     */
    T decode(byte[] data, int offset);
}
