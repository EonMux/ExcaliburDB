package excalibur.api.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.io.Serializable;
import java.util.Base64;
import java.util.Map;

/**
 * HTTP Method enum representing supported HTTP verbs.
 * <p>
 * Thread-safety: This enum is inherently thread-safe.
 */
public enum HttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
    PATCH;
    
    private static final Logger LOG = LoggerFactory.getLogger(HttpMethod.class);
    
    /**
     * Gets the HTTP method name.
     * 
     * @return The HTTP method name as a string
     */
    public static String name(HttpMethod method) {
        return method.name();
    }
    
    /**
     * Checks if the method is a GET request.
     * 
     * @return true if this is GET, false otherwise
     */
    public boolean isGet() {
        return this == GET;
    }
    
    /**
     * Checks if the method is a POST request.
     * 
     * @return true if this is POST, false otherwise
     */
    public boolean isPost() {
        return this == POST;
    }
    
    /**
     * Checks if the method is a PUT request.
     * 
     * @return true if this is PUT, false otherwise
     */
    public boolean isPut() {
        return this == PUT;
    }
    
    /**
     * Checks if the method is a DELETE request.
     * 
     * @return true if this is DELETE, false otherwise
     */
    public boolean isDelete() {
        return this == DELETE;
    }
    
    /**
     * Checks if the method is a PATCH request.
     * 
     * @return true if this is PATCH, false otherwise
     */
    public boolean isPatch() {
        return this == PATCH;
    }
    
    /**
     * Checks if this is a read method (GET, HEAD).
     * 
     * @return true if this is a read method
     */
    public boolean isRead() {
        return this == GET;
    }
    
    /**
     * Checks if this is a write method (POST, PUT, DELETE, PATCH).
     * 
     * @return true if this is a write method
     */
    public boolean isWrite() {
        return this == POST || this == PUT || this == DELETE || this == PATCH;
    }
}
