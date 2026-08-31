package excalibur.llm.client;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * A body handler that refuses to read past a byte limit.
 *
 * <h2>Why</h2>
 * <p>A request timeout does not bound a body: a peer that keeps sending, slowly, never trips it,
 * and {@code BodyHandlers.ofString} accumulates the whole thing on the heap before anything looks
 * at it. The outcome is not a bad response reported to the caller, it is the process running out
 * of memory and taking ingestion and query serving down with it. The endpoint is
 * operator-configured, but a proxy in front of it - or an inference server behaving badly - is
 * precisely what the timeout does not cover.</p>
 *
 * <p>The declared {@code Content-Length} is checked first, so an oversized response is refused
 * before a byte of it is read. A chunked response that declares nothing is cut off as it
 * arrives, and the exchange is cancelled at that point rather than drained.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Each call produces a fresh handler, and each exchange gets its own subscriber.</p>
 *
 * @author ExcaliburDB
 */
public final class BoundedBody {

    private BoundedBody() {
        // Utility class
    }

    /**
     * Returns a handler that decodes the body as text, up to {@code maxBytes}.
     *
     * @param maxBytes The largest body to accept, in bytes
     * @param charset  The charset to decode with
     * @return The handler
     * @throws IllegalArgumentException if maxBytes is not positive or charset is null
     */
    public static HttpResponse.BodyHandler<String> ofString(long maxBytes, Charset charset) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive, got: " + maxBytes);
        }
        if (charset == null) {
            throw new IllegalArgumentException("charset must not be null");
        }

        return responseInfo -> {
            long declared = responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declared > maxBytes) {
                throw new BodyTooLargeException(
                    "Response declares " + declared + " bytes, above the " + maxBytes
                        + " byte limit");
            }
            BoundedSubscriber subscriber = new BoundedSubscriber(maxBytes);
            return HttpResponse.BodySubscribers.fromSubscriber(
                subscriber, s -> ((BoundedSubscriber) s).body(charset));
        };
    }

    /**
     * Accumulates a body, failing the exchange once the limit is passed.
     */
    private static final class BoundedSubscriber implements Flow.Subscriber<List<ByteBuffer>> {

        private final long maxBytes;
        private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        BoundedSubscriber(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        String body(Charset charset) {
            return sink.toString(charset);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int remaining = buffer.remaining();
                if (sink.size() + (long) remaining > maxBytes) {
                    // Cancel rather than keep reading: the point is not to notice afterwards
                    // that too much arrived, it is not to hold it.
                    subscription.cancel();
                    throw new BodyTooLargeException(
                        "Response exceeded the " + maxBytes + " byte limit");
                }
                byte[] chunk = new byte[remaining];
                buffer.get(chunk);
                sink.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            // The finisher's future carries the failure; nothing to add here.
        }

        @Override
        public void onComplete() {
            // The finisher reads the accumulated body.
        }
    }

    /**
     * Signals a response body larger than the configured limit.
     */
    public static final class BodyTooLargeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * @param message What was exceeded
         */
        public BodyTooLargeException(String message) {
            super(message);
        }
    }
}
