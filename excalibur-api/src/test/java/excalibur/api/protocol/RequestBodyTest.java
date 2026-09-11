package excalibur.api.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the bounded request body read.
 *
 * <p>The case that matters is the endless one. A chunked request declares no length, so nothing
 * before this method knows how much is coming, and {@code readAllBytes} would keep allocating
 * until the process died - one request, no rate limiter involved.
 */
@DisplayName("RequestBody")
class RequestBodyTest {

    @Test
    @DisplayName("reads a body as UTF-8")
    void readsUtf8() throws IOException {
        String body = "{\"query\": \"naïve café 日本語\"}";

        assertThat(RequestBody.read(stream(body))).isEqualTo(body);
    }

    @Test
    @DisplayName("an empty body reads as empty, not as an error")
    void emptyBody() throws IOException {
        assertThat(RequestBody.read(stream(""))).isEmpty();
    }

    @Test
    @DisplayName("a body exactly at the limit is accepted")
    void exactlyAtTheLimit() throws IOException {
        String body = "a".repeat(RequestBody.MAX_REQUEST_SIZE);

        assertThat(RequestBody.read(stream(body))).hasSize(RequestBody.MAX_REQUEST_SIZE);
    }

    @Test
    @DisplayName("one byte over the limit is refused")
    void oneByteOver() {
        String body = "a".repeat(RequestBody.MAX_REQUEST_SIZE + 1);

        assertThatThrownBy(() -> RequestBody.read(stream(body)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("too large");
    }

    @Test
    @DisplayName("the refusal is typed, so an endpoint can answer 413 rather than 400")
    void refusalIsTyped() {
        // Every endpoint validates its fields by throwing IllegalArgumentException, so a size
        // failure that is only an IllegalArgumentException is indistinguishable from a bad
        // field and gets answered 400 - telling the caller not to retry, when splitting the
        // payload is exactly what they should do.
        String body = "a".repeat(RequestBody.MAX_REQUEST_SIZE + 1);

        assertThatThrownBy(() -> RequestBody.read(stream(body)))
            .isInstanceOf(RequestBody.TooLargeException.class);
    }

    @Test
    @DisplayName("an endless body is refused after reading the limit, not after exhausting memory")
    void endlessBody() {
        // The chunked-encoding case: a stream that never reports end of input. The assertion is
        // as much about how much was read as about the exception - a reader that consumed the
        // whole stream before checking would never get here.
        AtomicLong served = new AtomicLong();
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                served.incrementAndGet();
                return 'a';
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                java.util.Arrays.fill(buffer, offset, offset + length, (byte) 'a');
                served.addAndGet(length);
                return length;
            }
        };

        assertThatThrownBy(() -> RequestBody.read(endless))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("too large");
        assertThat(served.get()).isLessThanOrEqualTo(RequestBody.MAX_REQUEST_SIZE + 1L);
    }

    private static InputStream stream(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }
}
