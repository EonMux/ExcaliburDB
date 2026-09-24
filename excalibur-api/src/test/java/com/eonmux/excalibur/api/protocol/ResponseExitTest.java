package com.eonmux.excalibur.api.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That every response leaves through {@link ResponseBody}.
 *
 * <p>The {@code code} member of an error, the HEAD handling and the response counters are all
 * applied there and only there. A handler that wrote its status line itself would answer an error
 * a client has to parse prose to classify, and nothing at the socket would say so until a client
 * hit it. So the guarantee is checked where it is made: no production class but
 * {@link ResponseBody} sends response headers.</p>
 */
@DisplayName("Response exit")
class ResponseExitTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path RESPONSE_BODY =
        MAIN.resolve("com/eonmux/excalibur/api/protocol/ResponseBody.java");

    @Test
    @DisplayName("no production class but ResponseBody sends response headers")
    void onlyResponseBodySendsHeaders() throws IOException {
        assertThat(MAIN).as("run from the module directory").isDirectory();

        List<Path> writers;
        try (Stream<Path> sources = Files.walk(MAIN)) {
            writers = sources
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !path.equals(RESPONSE_BODY))
                .filter(ResponseExitTest::sendsHeaders)
                .toList();
        }

        assertThat(writers)
            .as("these write a status line themselves, bypassing the error code and the counters")
            .isEmpty();
    }

    private static boolean sendsHeaders(Path source) {
        try {
            return Files.readString(source).contains(".sendResponseHeaders(");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
