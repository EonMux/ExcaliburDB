package excalibur.api.protocol;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reading a request body without letting the client choose how much memory it gets.
 *
 * <p>{@code InputStream.readAllBytes} on a request body is a promise to allocate whatever the
 * caller sends. With chunked transfer encoding there is no declared length to sanity-check, so the
 * read continues until the JVM runs out of heap - and a rate limiter does not help, because a
 * single request is enough. Every endpoint reads through here so that the bound is one decision
 * rather than one per endpoint, which is how one endpoint came to be missing it.
 *
 * <h2>Why not trust Content-Length</h2>
 * <p>It is a client-supplied number, absent under chunked encoding and free to disagree with what
 * follows it. The bound is applied to the bytes actually read.
 */
public final class RequestBody {

    /**
     * Largest request body any endpoint accepts.
     *
     * <p>Generous for a JSON document describing a query or a source, and small enough that a
     * burst of concurrent requests cannot exhaust the heap between them.
     */
    public static final int MAX_REQUEST_SIZE = 1024 * 1024;

    private RequestBody() {
        // Utility class
    }

    /**
     * Reads the body of {@code exchange} as UTF-8, refusing anything oversized.
     *
     * @param exchange The exchange
     * @return The body
     * @throws IOException              if the body cannot be read
     * @throws IllegalArgumentException if the body exceeds {@link #MAX_REQUEST_SIZE}
     */
    public static String read(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return read(in);
        }
    }

    /**
     * Reads {@code in} as UTF-8, refusing anything oversized.
     *
     * <p>Reads one byte beyond the limit rather than checking afterwards: that is what
     * distinguishes a body exactly at the limit from one that merely starts that way, without
     * ever holding more than the limit plus one byte.
     *
     * @param in The stream, which this method does not close
     * @return The body
     * @throws IOException              if the stream cannot be read
     * @throws IllegalArgumentException if the body exceeds {@link #MAX_REQUEST_SIZE}
     */
    public static String read(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_REQUEST_SIZE + 1);
        if (bytes.length > MAX_REQUEST_SIZE) {
            throw new IllegalArgumentException(
                "Request body too large (max " + MAX_REQUEST_SIZE + " bytes)");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
