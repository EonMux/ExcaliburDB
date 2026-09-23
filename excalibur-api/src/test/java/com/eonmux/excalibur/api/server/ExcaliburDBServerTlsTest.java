package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.security.TestKeystore;
import com.eonmux.excalibur.api.security.TlsConfig;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.EpochTickScheduler;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.worker.CuckooMetricsWorker;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that the API server actually serves over TLS when it is configured to.
 *
 * <p>{@code TlsConfigTest} proves the policy produces a working listener. This proves the server
 * is the thing that starts it - that the configurator reaches an {@link
 * com.sun.net.httpserver.HttpsServer} rather than being built and dropped, which is the failure a
 * unit test of the policy alone cannot see.
 */
@DisplayName("ExcaliburDBServer TLS")
class ExcaliburDBServerTlsTest {

    private static final String PASSWORD = "keystore-password";
    private static final String TOKEN = "test-bearer-token";

    private IndexOrchestrator indexOrchestrator;
    private StorageEngine storageEngine;
    private QueryPipeline queryPipeline;
    private ExcaliburDBServer server;

    @BeforeEach
    void setUp() {
        SegmentCatalog segmentCatalog = new SegmentCatalog();

        indexOrchestrator = mock(IndexOrchestrator.class);
        when(indexOrchestrator.getCuckooFpRates()).thenReturn(CuckooMetricsWorker.FpRates.NONE);
        storageEngine = mock(StorageEngine.class);
        queryPipeline = mock(QueryPipeline.class);

        var epochTickScheduler = mock(EpochTickScheduler.class);
        when(indexOrchestrator.getVisibilityTracker())
            .thenReturn(new com.eonmux.excalibur.shard.VisibilityTracker(segmentCatalog));
        when(indexOrchestrator.getEpochTickScheduler()).thenReturn(epochTickScheduler);
        when(epochTickScheduler.getTickIntervalMs()).thenReturn(100L);
        when(storageEngine.getCatalog()).thenReturn(segmentCatalog);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    @DisplayName("a TLS-configured server answers /health over HTTPS")
    void servesHealthOverHttps(@TempDir Path dir) throws Exception {
        Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");
        start(tlsFor(keystore));

        HttpResponse<String> response = get(clientTrusting(keystore), "/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"live\": true");
    }

    @Test
    @DisplayName("an authenticated endpoint still requires its token over HTTPS")
    void authenticationStillAppliesOverHttps(@TempDir Path dir) throws Exception {
        Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");
        start(tlsFor(keystore));

        // Encrypting the channel does not authenticate the caller; both checks must hold.
        assertThat(get(clientTrusting(keystore), "/v1/stats").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a plaintext client cannot talk to a TLS listener")
    void plaintextClientIsRefused(@TempDir Path dir) throws Exception {
        Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");
        start(tlsFor(keystore));

        HttpClient plaintext = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        assertThatThrownBy(() -> plaintext.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + server.getPort() + "/health"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()))
            .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("a TLS misconfiguration fails the start without binding the port")
    void badTlsDoesNotBind(@TempDir Path dir) throws Exception {
        Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");

        ExcaliburDBServer broken =
            new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        broken.withSecurity(ApiSecurityConfig.builder()
            .authToken(TOKEN)
            .tls(TlsConfig.fromProperties(Map.of(
                TlsConfig.KEY_KEYSTORE, keystore.toString(),
                TlsConfig.KEY_KEYSTORE_PASSWORD, "wrong-password")))
            .build());

        assertThatThrownBy(broken::start).isInstanceOf(IOException.class);

        // Nothing was bound, so stopping is a no-op rather than a leak. Starting again with a
        // sound policy must succeed, which it cannot if the failed attempt held the port.
        broken.stop(0);
        start(tlsFor(keystore));
        assertThat(get(clientTrusting(keystore), "/health").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a non-loopback bind without TLS refuses to start")
    void nonLoopbackRequiresTls() {
        ExcaliburDBServer exposed =
            new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        exposed.withSecurity(ApiSecurityConfig.builder()
            .authToken(TOKEN)
            .bindAddress("0.0.0.0")
            .build());

        assertThatThrownBy(exposed::start)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("without TLS")
            .hasMessageContaining(TlsConfig.KEY_KEYSTORE)
            .hasMessageContaining(ApiSecurityConfig.KEY_ALLOW_PLAINTEXT);
    }

    @Test
    @DisplayName("allow-plaintext lets a non-loopback bind serve cleartext, deliberately")
    void allowPlaintextIsTheEscapeHatch() throws Exception {
        // The case this exists for: something in front terminates TLS. It has to be said out
        // loud, because from inside this process it looks exactly like the unsafe case.
        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        server.withSecurity(ApiSecurityConfig.builder()
            .authToken(TOKEN)
            .bindAddress("127.0.0.1")
            .allowPlaintext(true)
            .build());
        server.start();

        assertThat(get(HttpClient.newHttpClient(), "http", "/health").statusCode()).isEqualTo(200);
    }

    private static TlsConfig tlsFor(Path keystore) {
        return TlsConfig.fromProperties(Map.of(
            TlsConfig.KEY_KEYSTORE, keystore.toString(),
            TlsConfig.KEY_KEYSTORE_PASSWORD, PASSWORD));
    }

    private void start(TlsConfig tls) throws IOException {
        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        server.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).tls(tls).build());
        server.start();
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return get(client, "https", path);
    }

    private HttpResponse<String> get(HttpClient client, String scheme, String path)
            throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create(scheme + "://localhost:" + server.getPort() + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static HttpClient clientTrusting(Path keystore) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            trust.load(in, PASSWORD.toCharArray());
        }
        TrustManagerFactory factory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);

        return HttpClient.newBuilder()
            .sslContext(context)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }
}
