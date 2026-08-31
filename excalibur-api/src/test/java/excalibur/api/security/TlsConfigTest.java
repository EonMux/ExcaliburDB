package excalibur.api.security;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link TlsConfig}.
 *
 * <p>The point of most of these is that a misconfiguration is reported at startup with a message
 * that names the key to fix. A TLS listener that binds and then fails every handshake is the worst
 * outcome available, because the failure surfaces on the client and says nothing useful.
 */
@DisplayName("TlsConfig")
class TlsConfigTest {

    private static final String PASSWORD = "keystore-password";

    @Nested
    @DisplayName("Enablement")
    class Enablement {

        @Test
        @DisplayName("nothing configured means no TLS")
        void disabledByDefault() {
            assertThat(TlsConfig.fromProperties(Map.of()).isEnabled()).isFalse();
            assertThat(TlsConfig.disabled().isEnabled()).isFalse();
        }

        @Test
        @DisplayName("configuring a keystore turns TLS on")
        void keystoreEnables(@TempDir Path dir) throws Exception {
            Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");

            TlsConfig tls = TlsConfig.fromProperties(Map.of(
                TlsConfig.KEY_KEYSTORE, keystore.toString(),
                TlsConfig.KEY_KEYSTORE_PASSWORD, PASSWORD));

            assertThat(tls.isEnabled()).isTrue();
            assertThat(tls.keystore()).isEqualTo(keystore);
        }

        @Test
        @DisplayName("a configured keystore can be turned off, for a TLS-terminating proxy")
        void explicitlyDisabled(@TempDir Path dir) throws Exception {
            Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");

            TlsConfig tls = TlsConfig.fromProperties(Map.of(
                TlsConfig.KEY_KEYSTORE, keystore.toString(),
                TlsConfig.KEY_KEYSTORE_PASSWORD, PASSWORD,
                TlsConfig.KEY_ENABLED, "false"));

            assertThat(tls.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("asking for TLS without a keystore names the key to set")
        void enabledWithoutKeystore() {
            assertThatThrownBy(() -> TlsConfig.fromProperties(Map.of(TlsConfig.KEY_ENABLED, "true")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TlsConfig.KEY_KEYSTORE);
        }

        @Test
        @DisplayName("an unreadable value for the enable flag is not read as off")
        void unparseableFlagDoesNotSilentlyDisable() {
            // "on" is not a boolean. Reading it as false would serve plaintext to an operator who
            // asked for TLS, so it fails instead.
            assertThatThrownBy(() -> TlsConfig.fromProperties(Map.of(TlsConfig.KEY_ENABLED, "on")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TlsConfig.KEY_KEYSTORE);
        }
    }

    @Nested
    @DisplayName("Key material")
    class KeyMaterial {

        @Test
        @DisplayName("a keystore with no password is refused rather than opened with none")
        void passwordRequired(@TempDir Path dir) throws Exception {
            Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");

            assertThatThrownBy(() -> TlsConfig.fromProperties(
                Map.of(TlsConfig.KEY_KEYSTORE, keystore.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TlsConfig.KEY_KEYSTORE_PASSWORD);
        }

        @Test
        @DisplayName("a keystore path that does not exist is reported at startup")
        void missingKeystore(@TempDir Path dir) {
            assertThatThrownBy(() -> TlsConfig.fromProperties(Map.of(
                TlsConfig.KEY_KEYSTORE, dir.resolve("absent.p12").toString(),
                TlsConfig.KEY_KEYSTORE_PASSWORD, PASSWORD)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not readable");
        }

        @Test
        @DisplayName("the wrong password fails while starting, not on the first handshake")
        void wrongPassword(@TempDir Path dir) throws Exception {
            Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");

            TlsConfig tls = TlsConfig.fromProperties(Map.of(
                TlsConfig.KEY_KEYSTORE, keystore.toString(),
                TlsConfig.KEY_KEYSTORE_PASSWORD, "not-the-password"));

            assertThatThrownBy(tls::configurator).isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("a single-key keystore needs no alias")
        void singleKeyNeedsNoAlias(@TempDir Path dir) throws Exception {
            Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");

            assertThat(configOf(keystore).createSslContext()).isNotNull();
        }

        @Test
        @DisplayName("two keys without an alias is ambiguous and says so")
        void twoKeysNeedAnAlias(@TempDir Path dir) throws Exception {
            Path keystore = TestKeystore.create(
                dir, "server.p12", "PKCS12", PASSWORD, "first", "second");

            assertThatThrownBy(() -> configOf(keystore).createSslContext())
                .hasMessageContaining(TlsConfig.KEY_KEY_ALIAS)
                .hasMessageContaining("first")
                .hasMessageContaining("second");
        }

        @Test
        @DisplayName("an alias selects which certificate the server presents")
        void aliasSelects(@TempDir Path dir) throws Exception {
            Path keystore = TestKeystore.create(
                dir, "server.p12", "PKCS12", PASSWORD, "first", "second");

            TlsConfig tls = TlsConfig.fromProperties(Map.of(
                TlsConfig.KEY_KEYSTORE, keystore.toString(),
                TlsConfig.KEY_KEYSTORE_PASSWORD, PASSWORD,
                TlsConfig.KEY_KEY_ALIAS, "second"));

            assertThat(tls.createSslContext()).isNotNull();
            assertThat(tls.keyAlias()).isEqualTo("second");
        }

        @Test
        @DisplayName("an alias that is not there lists the ones that are")
        void unknownAlias(@TempDir Path dir) throws Exception {
            Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");

            TlsConfig tls = TlsConfig.fromProperties(Map.of(
                TlsConfig.KEY_KEYSTORE, keystore.toString(),
                TlsConfig.KEY_KEYSTORE_PASSWORD, PASSWORD,
                TlsConfig.KEY_KEY_ALIAS, "typo"));

            assertThatThrownBy(tls::createSslContext)
                .hasMessageContaining("typo")
                .hasMessageContaining("server");
        }

        @Test
        @DisplayName("a key password that differs from the store password is honoured")
        void distinctKeyPassword(@TempDir Path dir) throws Exception {
            // Only JKS supports it; keytool ignores -keypass for PKCS12 and says so.
            Path keystore = TestKeystore.createWithDistinctKeyPassword(
                dir, "server.jks", "JKS", PASSWORD, "key-password", "server");

            TlsConfig withoutKeyPassword = TlsConfig.fromProperties(Map.of(
                TlsConfig.KEY_KEYSTORE, keystore.toString(),
                TlsConfig.KEY_KEYSTORE_PASSWORD, PASSWORD,
                TlsConfig.KEY_KEYSTORE_TYPE, "JKS"));
            assertThatThrownBy(withoutKeyPassword::createSslContext)
                .hasMessageContaining(TlsConfig.KEY_KEY_PASSWORD);

            TlsConfig withKeyPassword = TlsConfig.fromProperties(Map.of(
                TlsConfig.KEY_KEYSTORE, keystore.toString(),
                TlsConfig.KEY_KEYSTORE_PASSWORD, PASSWORD,
                TlsConfig.KEY_KEYSTORE_TYPE, "JKS",
                TlsConfig.KEY_KEY_PASSWORD, "key-password"));
            assertThat(withKeyPassword.createSslContext()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Handshake policy")
    class HandshakePolicy {

        @Test
        @DisplayName("protocols this JVM cannot speak are dropped, not fatal")
        void unsupportedProtocolIsDropped(@TempDir Path dir) throws Exception {
            Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");

            TlsConfig tls = TlsConfig.fromProperties(Map.of(
                TlsConfig.KEY_KEYSTORE, keystore.toString(),
                TlsConfig.KEY_KEYSTORE_PASSWORD, PASSWORD,
                TlsConfig.KEY_PROTOCOLS, "TLSv1.3, TLSv99"));

            assertThat(tls.configurator()).isNotNull();
            assertThat(tls.requestedProtocols()).containsExactly("TLSv1.3", "TLSv99");
        }

        @Test
        @DisplayName("a protocol list this JVM cannot speak at all fails at startup")
        void noSupportedProtocolFails(@TempDir Path dir) throws Exception {
            Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");

            TlsConfig tls = TlsConfig.fromProperties(Map.of(
                TlsConfig.KEY_KEYSTORE, keystore.toString(),
                TlsConfig.KEY_KEYSTORE_PASSWORD, PASSWORD,
                TlsConfig.KEY_PROTOCOLS, "TLSv99"));

            assertThatThrownBy(tls::configurator)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TlsConfig.KEY_PROTOCOLS);
        }

        @Test
        @DisplayName("a cipher suite list this JVM cannot offer fails at startup")
        void noSupportedCipherSuiteFails(@TempDir Path dir) throws Exception {
            Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");

            TlsConfig tls = TlsConfig.fromProperties(Map.of(
                TlsConfig.KEY_KEYSTORE, keystore.toString(),
                TlsConfig.KEY_KEYSTORE_PASSWORD, PASSWORD,
                TlsConfig.KEY_CIPHER_SUITES, "TLS_MADE_UP_SUITE"));

            assertThatThrownBy(tls::configurator)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TlsConfig.KEY_CIPHER_SUITES);
        }

        @Test
        @DisplayName("every default cipher suite is one this JVM supports")
        void defaultsAreSupported() throws Exception {
            List<String> supported = List.of(
                SSLContext.getDefault().getSupportedSSLParameters().getCipherSuites());

            assertThat(supported).containsAll(TlsConfig.DEFAULT_CIPHER_SUITES);
            assertThat(List.of(SSLContext.getDefault().getSupportedSSLParameters().getProtocols()))
                .containsAll(TlsConfig.DEFAULT_PROTOCOLS);
        }

        @Test
        @DisplayName("the client certificate policy parses case-insensitively")
        void clientAuthParses(@TempDir Path dir) throws Exception {
            Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");

            assertThat(clientAuthOf(keystore, "need")).isEqualTo(TlsConfig.ClientAuth.NEED);
            assertThat(clientAuthOf(keystore, "WANT")).isEqualTo(TlsConfig.ClientAuth.WANT);
            assertThat(clientAuthOf(keystore, "None")).isEqualTo(TlsConfig.ClientAuth.NONE);
            assertThat(configOf(keystore).clientAuth()).isEqualTo(TlsConfig.ClientAuth.NONE);
        }

        @Test
        @DisplayName("an unrecognised client certificate policy is refused")
        void clientAuthRejectsNonsense(@TempDir Path dir) throws Exception {
            Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");

            assertThatThrownBy(() -> clientAuthOf(keystore, "maybe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(TlsConfig.KEY_CLIENT_AUTH);
        }

        private TlsConfig.ClientAuth clientAuthOf(Path keystore, String value) {
            return TlsConfig.fromProperties(Map.of(
                TlsConfig.KEY_KEYSTORE, keystore.toString(),
                TlsConfig.KEY_KEYSTORE_PASSWORD, PASSWORD,
                TlsConfig.KEY_CLIENT_AUTH, value)).clientAuth();
        }
    }

    @Nested
    @DisplayName("Serving")
    class Serving {

        @Test
        @DisplayName("a listener built from the policy actually speaks HTTPS")
        void servesHttps(@TempDir Path dir) throws Exception {
            Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");

            try (Listener listener = Listener.start(configOf(keystore))) {
                HttpResponse<String> response = listener.get(clientTrusting(keystore));

                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).isEqualTo("ok");
                assertThat(response.version()).isNotNull();
            }
        }

        @Test
        @DisplayName("a client that does not trust the certificate is rejected")
        void untrustedClientIsRejected(@TempDir Path dir) throws Exception {
            Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");

            try (Listener listener = Listener.start(configOf(keystore))) {
                // Default trust: the public web CAs, which have not signed this certificate.
                assertThatThrownBy(() -> listener.get(HttpClient.newHttpClient()))
                    .isInstanceOf(IOException.class);
            }
        }

        @Test
        @DisplayName("client-auth need refuses a client that presents no certificate")
        void needClientAuthIsEnforced(@TempDir Path dir) throws Exception {
            // The policy is set on the SSLParameters rather than on the HttpsParameters, because
            // the JDK ignores the latter once setSSLParameters has been called. Getting that
            // wrong produces a server that silently asks for nothing, which no assertion about
            // the configuration object would catch -- only a handshake does.
            Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");

            TlsConfig tls = TlsConfig.fromProperties(Map.of(
                TlsConfig.KEY_KEYSTORE, keystore.toString(),
                TlsConfig.KEY_KEYSTORE_PASSWORD, PASSWORD,
                TlsConfig.KEY_CLIENT_AUTH, "need",
                TlsConfig.KEY_TRUSTSTORE, keystore.toString(),
                TlsConfig.KEY_TRUSTSTORE_PASSWORD, PASSWORD));

            try (Listener listener = Listener.start(tls)) {
                assertThatThrownBy(() -> listener.get(clientTrusting(keystore)))
                    .isInstanceOf(IOException.class);
            }
        }

        @Test
        @DisplayName("client-auth none serves a client that presents no certificate")
        void noneClientAuthServesAnonymousClients(@TempDir Path dir) throws Exception {
            Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");

            try (Listener listener = Listener.start(configOf(keystore))) {
                assertThat(listener.get(clientTrusting(keystore)).statusCode()).isEqualTo(200);
            }
        }
    }

    @Test
    @DisplayName("no password reaches the log or a stack trace")
    void passwordsAreNotPrinted(@TempDir Path dir) throws Exception {
        Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", PASSWORD, "server");

        TlsConfig tls = TlsConfig.fromProperties(Map.of(
            TlsConfig.KEY_KEYSTORE, keystore.toString(),
            TlsConfig.KEY_KEYSTORE_PASSWORD, PASSWORD,
            TlsConfig.KEY_TRUSTSTORE, keystore.toString(),
            TlsConfig.KEY_TRUSTSTORE_PASSWORD, PASSWORD));

        assertThat(tls.toString()).doesNotContain(PASSWORD).contains("server.p12");
        assertThat(TlsConfig.disabled().toString()).isEqualTo("TlsConfig{disabled}");
    }

    private static TlsConfig configOf(Path keystore) {
        return TlsConfig.fromProperties(Map.of(
            TlsConfig.KEY_KEYSTORE, keystore.toString(),
            TlsConfig.KEY_KEYSTORE_PASSWORD, PASSWORD));
    }

    /** A client trusting exactly the certificate in the given store, and nothing else. */
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

    /** An HTTPS listener on an ephemeral port, answering {@code ok} at the root. */
    private record Listener(HttpsServer server, int port) implements AutoCloseable {

        static Listener start(TlsConfig tls) throws Exception {
            HttpsConfigurator configurator = tls.configurator();
            HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(configurator);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", exchange -> {
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return new Listener(server, server.getAddress().getPort());
        }

        HttpResponse<String> get(HttpClient client) throws Exception {
            return client.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
