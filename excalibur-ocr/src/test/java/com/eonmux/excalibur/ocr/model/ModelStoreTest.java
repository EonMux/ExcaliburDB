package com.eonmux.excalibur.ocr.model;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("A model store")
class ModelStoreTest {

    private static final byte[] ALPHA = "alpha model".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BETA = "beta model, longer".getBytes(StandardCharsets.UTF_8);
    private static final ModelFile ALPHA_FILE = pin("alpha.onnx", ALPHA);
    private static final ModelFile BETA_FILE = pin("beta.npz", BETA);

    private HttpServer server;
    private final Map<String, byte[]> served = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private URI source;

    @TempDir
    Path dir;

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/models/", exchange -> {
            requests.incrementAndGet();
            String name = exchange.getRequestURI().getPath().substring("/models/".length());
            byte[] body = served.get(name);
            int status = statuses.getOrDefault(name, body == null ? 404 : 200);
            byte[] payload = status == 200 && body != null ? body : new byte[0];
            exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        source = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/models/");
        served.put(ALPHA_FILE.name(), ALPHA);
        served.put(BETA_FILE.name(), BETA);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("downloads what it lacks once, and then reads it from disk")
    void downloadsOnce() throws Exception {
        ModelStore store = ModelStore.downloading(dir.resolve("models"), source);

        assertThat(store.missing(List.of(ALPHA_FILE, BETA_FILE))).hasSize(2);
        Path got = store.ensure(List.of(ALPHA_FILE, BETA_FILE));

        assertThat(got).isEqualTo(dir.resolve("models").toAbsolutePath());
        assertThat(Files.readAllBytes(got.resolve("alpha.onnx"))).isEqualTo(ALPHA);
        assertThat(Files.readAllBytes(got.resolve("beta.npz"))).isEqualTo(BETA);
        assertThat(store.missing(List.of(ALPHA_FILE, BETA_FILE))).isEmpty();
        assertThat(requests).hasValue(2);

        store.ensure(List.of(ALPHA_FILE, BETA_FILE));
        ModelStore.downloading(dir.resolve("models"), source).ensure(List.of(ALPHA_FILE));
        assertThat(requests).as("a verified file is not fetched again").hasValue(2);
        assertThat(leftovers(got)).isEmpty();
    }

    @Test
    @DisplayName("refuses bytes other than the pinned ones, leaving nothing under the name")
    void refusesOtherBytes() throws Exception {
        served.put(ALPHA_FILE.name(), "alpha modeL".getBytes(StandardCharsets.UTF_8));
        ModelStore store = ModelStore.downloading(dir, source);

        assertThatThrownBy(() -> store.ensure(List.of(ALPHA_FILE)))
            .isInstanceOf(ModelUnavailableException.class)
            .hasMessageContaining("delivered other bytes")
            .hasMessageContaining(ALPHA_FILE.sha256());
        assertThat(dir.resolve("alpha.onnx")).doesNotExist();
        assertThat(leftovers(dir)).isEmpty();
    }

    @Test
    @DisplayName("reports a source that does not have the file")
    void reportsHttpFailure() {
        served.remove(BETA_FILE.name());
        statuses.put("alpha.onnx", 503);
        ModelStore store = ModelStore.downloading(dir, source);

        assertThatThrownBy(() -> store.ensure(List.of(BETA_FILE)))
            .isInstanceOf(ModelUnavailableException.class).hasMessageContaining("HTTP 404");
        assertThatThrownBy(() -> store.ensure(List.of(ALPHA_FILE)))
            .hasMessageContaining("HTTP 503");
    }

    @Test
    @DisplayName("reports a source it cannot reach, with what to do about it")
    void reportsUnreachable() throws Exception {
        URI nowhere = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/models/");
        server.stop(0);
        ModelStore store = ModelStore.downloading(dir, nowhere);

        assertThatThrownBy(() -> store.ensure(List.of(ALPHA_FILE)))
            .isInstanceOf(ModelUnavailableException.class)
            .hasMessageContaining("Could not download")
            .hasMessageContaining("yourself");
    }

    @Test
    @DisplayName("keeps a file an operator put in place, and replaces a damaged one")
    void trustsOnlyPinnedFiles() throws Exception {
        Files.write(dir.resolve("alpha.onnx"), ALPHA);
        Files.write(dir.resolve("beta.npz"), "beta model, LONGER".getBytes(StandardCharsets.UTF_8));
        ModelStore store = ModelStore.downloading(dir, source);

        assertThat(store.missing(List.of(ALPHA_FILE, BETA_FILE)))
            .as("a same-length file is not caught by the quick look").isEmpty();
        store.ensure(List.of(ALPHA_FILE, BETA_FILE));

        assertThat(requests).as("only the damaged file is fetched").hasValue(1);
        assertThat(Files.readAllBytes(dir.resolve("beta.npz"))).isEqualTo(BETA);
    }

    @Test
    @DisplayName("without downloads, uses what is there and names what is not")
    void localOnly() throws Exception {
        Files.write(dir.resolve("alpha.onnx"), ALPHA);
        ModelStore store = ModelStore.local(dir);

        assertThat(store.downloads()).isFalse();
        assertThat(store.ensure(List.of(ALPHA_FILE))).isEqualTo(dir.toAbsolutePath());
        assertThatThrownBy(() -> store.ensure(List.of(ALPHA_FILE, BETA_FILE)))
            .isInstanceOf(ModelUnavailableException.class)
            .hasMessageContaining("beta.npz")
            .hasMessageNotContaining("alpha.onnx")
            .hasMessageContaining("turned off");
        assertThat(requests).hasValue(0);
    }

    @Test
    @DisplayName("downloads each file once when many threads ask at the same time")
    void concurrentCallers() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Path>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                ModelStore store = ModelStore.downloading(dir, source);
                results.add(pool.submit(() -> {
                    start.await();
                    return store.ensure(List.of(ALPHA_FILE, BETA_FILE));
                }));
            }
            start.countDown();
            for (Future<Path> result : results) {
                assertThat(result.get()).isEqualTo(dir.toAbsolutePath());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(requests).hasValue(2);
    }

    @Test
    @DisplayName("is interrupted while it waits for another download")
    void interruptible() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/slow/", exchange -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        URI slow = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow/");
        AtomicInteger outcome = new AtomicInteger();
        Thread downloader = Thread.ofPlatform().start(() -> {
            try {
                ModelStore.downloading(dir, slow).ensure(List.of(ALPHA_FILE));
            } catch (InterruptedException e) {
                outcome.set(1);
            } catch (ModelUnavailableException e) {
                outcome.set(2);
            }
        });
        entered.await();
        downloader.interrupt();
        downloader.join(10_000);
        release.countDown();

        assertThat(downloader.isAlive()).isFalse();
        assertThat(outcome).hasValue(1);
        assertThat(leftovers(dir)).isEmpty();
    }

    @Test
    @DisplayName("is told a source it cannot use")
    void refusesBadSource() {
        assertThatThrownBy(() -> ModelStore.downloading(dir, URI.create("ftp://x/models/")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelStore.downloading(dir, URI.create("https://x/models")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(ModelStore.downloading(dir).downloads()).isTrue();
    }

    @Test
    @DisplayName("keeps its models in a per-user cache by revision, not in the data directory")
    void defaultDirectory() {
        Path path = ModelStore.defaultDirectory();
        assertThat(path).isAbsolute();
        assertThat(path.toString()).endsWith(Path.of("excalibur", "indra-ocr",
            IndraModels.REVISION).toString());
    }

    private static List<Path> leftovers(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".part")).toList();
        }
    }

    private static ModelFile pin(String name, byte[] content) {
        try {
            return new ModelFile(name, content.length, HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
