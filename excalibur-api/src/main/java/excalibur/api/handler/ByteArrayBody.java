package excalibur.api.handler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * ByteArrayBody - A simple, zero-dependency HTTP body implementation.
 * Stores content as a byte array, supporting both reading and writing.
 * <p>
 * Thread-safety: This class is NOT thread-safe. Create a new instance
 * for each request/response.
 */
public class ByteArrayBody {
    private final byte[] data;
    
    /**
     * Creates an empty body.
     */
    public ByteArrayBody() {
        this.data = new byte[0];
    }
    
    /**
     * Creates a body with the given string content.
     * 
     * @param content The string content
     */
    public ByteArrayBody(String content) {
        this.data = content.getBytes(StandardCharsets.UTF_8);
    }
    
    /**
     * Creates a body with the given byte array.
     * 
     * @param data The byte array content
     */
    public ByteArrayBody(byte[] data) {
        this.data = data.clone();
    }
    
    /**
     * Creates a body from an InputStream.
     * 
     * @param inputStream The input stream to read from
     * @return A new ByteArrayBody with the read content
     * @throws IOException if reading fails
     */
    public static ByteArrayBody fromInputStream(InputStream inputStream) 
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] bufferBytes = new byte[4096];
        int bytesRead;
        while ((bytesRead = inputStream.read(bufferBytes)) != -1) {
            buffer.write(bufferBytes, 0, bytesRead);
        }
        return new ByteArrayBody(buffer.toByteArray());
    }
    
    /**
     * Writes the body to the given output stream.
     * 
     * @param outputStream The output stream to write to
     * @throws IOException if writing fails
     */
    public void writeTo(OutputStream outputStream) throws IOException {
        outputStream.write(data);
        outputStream.flush();
    }
    
    /**
     * Reads the body from the given input stream.
     * 
     * @param inputStream The input stream to read from
     * @return The byte array content
     * @throws IOException if reading fails
     */
    public static byte[] readFrom(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] bufferBytes = new byte[4096];
        int bytesRead;
        while ((bytesRead = inputStream.read(bufferBytes)) != -1) {
            buffer.write(bufferBytes, 0, bytesRead);
        }
        return buffer.toByteArray();
    }
    
    /**
     * Returns the raw byte array.
     * 
     * @return The byte array
     */
    public byte[] getData() {
        return data;
    }
    
    /**
     * Returns the string content.
     * 
     * @return The string content
     */
    public String getString() {
        return new String(data, StandardCharsets.UTF_8);
    }
    
    /**
     * Returns the size in bytes.
     * 
     * @return The size in bytes
     */
    public long getSize() {
        return data.length;
    }
    
    /**
     * Converts the body to a String.
     * 
     * @return The string representation
     */
    @Override
    public String toString() {
        return getString();
    }
    
    /**
     * Converts the body to a String.
     * 
     * @param charset The charset to use
     * @return The string representation
     */
    public String toString(String charset) {
        return new String(data, charset);
    }
    
    /**
     * Returns the body as a String.
     * 
     * @return The string content
     */
    public String get() {
        return toString();
    }
    
    /**
     * Returns the body as a String with default charset.
     * 
     * @return The string content
     */
    public String getOrDefault() {
        return toString();
    }
    
    /**
     * Returns the body as a Map of header name to value.
     * 
     * @return A map of header names to values
     */
    public Map<String, String> getHeaders() {
        return Collections.emptyMap();
    }
    
    /**
     * Returns the body as JSON (assuming UTF-8 encoded JSON).
     * 
     * @return The JSON string
     */
    public String getJson() {
        return toString();
    }
    
    /**
     * Returns the body as JSON with default charset.
     * 
     * @return The JSON string
     */
    public String getJsonOrDefault() {
        return getJson();
    }
}
