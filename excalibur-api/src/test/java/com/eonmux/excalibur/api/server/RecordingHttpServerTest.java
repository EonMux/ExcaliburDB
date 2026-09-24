package com.eonmux.excalibur.api.server;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Recording HTTP server")
class RecordingHttpServerTest {

    @Test
    @DisplayName("records every context registered on it, and forgets a removed one")
    void recordsContexts() throws IOException {
        HttpServer delegate = HttpServer.create();
        RecordingHttpServer recording = new RecordingHttpServer(delegate);

        recording.createContext("/a", exchange -> exchange.close());
        HttpContext b = recording.createContext("/b");
        recording.createContext("/c", exchange -> exchange.close());
        recording.removeContext("/a");
        recording.removeContext(b);

        assertThat(recording.paths()).containsExactly("/c");
    }

    @Test
    @DisplayName("everything but registration is the delegate's")
    void delegatesTheLifecycle() throws IOException {
        HttpServer delegate = HttpServer.create();
        RecordingHttpServer recording = new RecordingHttpServer(delegate);
        Executor executor = Runnable::run;

        recording.bind(new InetSocketAddress("127.0.0.1", 0), 0);
        recording.setExecutor(executor);
        recording.start();
        try {
            assertThat(recording.getExecutor()).isSameAs(executor);
            assertThat(recording.getAddress()).isEqualTo(delegate.getAddress());
            assertThat(recording.getAddress().getPort()).isPositive();
        } finally {
            recording.stop(0);
        }
    }
}
