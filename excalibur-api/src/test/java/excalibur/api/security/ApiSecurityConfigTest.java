package excalibur.api.security;

import org.junit.jupiter.api.DisplayName;
import io.excalibur.config.Config;
import io.excalibur.config.ConfigLoader;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the API security policy.
 *
 * <p>The policy decides whether the process may serve at all, so the cases that matter
 * are the unsafe ones: a non-loopback bind without a token or without TLS, and an
 * allow-list that is absent rather than explicitly empty.</p>
 */
@DisplayName("ApiSecurityConfig")
class ApiSecurityConfigTest {

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        @DisplayName("binds loopback, disables authentication and both ingest modes")
        void safeDefaults() {
            ApiSecurityConfig config = ApiSecurityConfig.defaults();

            assertThat(config.bindAddress()).isEqualTo("127.0.0.1");
            assertThat(config.isAuthenticationRequired()).isFalse();
            assertThat(config.allowedIngestRoots()).isEmpty();
            assertThat(config.allowedIngestHosts()).isEmpty();
        }

        @Test
        @DisplayName("compiles in no token")
        void noCompiledInToken() {
            ApiSecurityConfig config = ApiSecurityConfig.defaults();

            assertThat(config.matchesToken("".getBytes(StandardCharsets.UTF_8))).isFalse();
            assertThat(config.matchesToken("secret".getBytes(StandardCharsets.UTF_8))).isFalse();
            assertThat(config.matchesToken(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("Token comparison")
    class TokenComparison {

        @Test
        @DisplayName("accepts the configured token and rejects everything else")
        void comparesTokens() {
            ApiSecurityConfig config = ApiSecurityConfig.builder().authToken("s3cret-token").build();

            assertThat(config.isAuthenticationRequired()).isTrue();
            assertThat(config.matchesToken("s3cret-token".getBytes(StandardCharsets.UTF_8))).isTrue();
            assertThat(config.matchesToken("s3cret-toke".getBytes(StandardCharsets.UTF_8))).isFalse();
            assertThat(config.matchesToken("s3cret-token ".getBytes(StandardCharsets.UTF_8))).isFalse();
            assertThat(config.matchesToken("".getBytes(StandardCharsets.UTF_8))).isFalse();
        }

        @Test
        @DisplayName("treats a blank token as unset")
        void blankTokenIsUnset() {
            assertThat(ApiSecurityConfig.builder().authToken("   ").build().isAuthenticationRequired())
                .isFalse();
        }
    }

    @Nested
    @DisplayName("Bind address safety")
    class BindAddressSafety {

        @Test
        @DisplayName("refuses a non-loopback bind with no token")
        void refusesUnauthenticatedPublicBind() {
            ApiSecurityConfig config = ApiSecurityConfig.builder().bindAddress("0.0.0.0").build();

            assertThatThrownBy(config::requireSafeToServe)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("excalibur.api.auth-token");
        }

        @Test
        @DisplayName("refuses a non-loopback bind that would send the token in cleartext")
        void refusesAuthenticatedPublicBindWithoutTls() {
            // A token is necessary but not sufficient. Sent over cleartext it is a credential on
            // the wire in every request, which is not meaningfully better than no token at all
            // against anyone positioned to read the link.
            ApiSecurityConfig config = ApiSecurityConfig.builder()
                .bindAddress("0.0.0.0")
                .authToken("token")
                .build();

            assertThatThrownBy(config::requireSafeToServe)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without TLS")
                .hasMessageContaining(TlsConfig.KEY_KEYSTORE)
                .hasMessageContaining(ApiSecurityConfig.KEY_ALLOW_PLAINTEXT);
        }

        @Test
        @DisplayName("allows a non-loopback bind with a token and TLS")
        void allowsAuthenticatedPublicBindWithTls(@TempDir Path dir) throws Exception {
            Path keystore = TestKeystore.create(dir, "server.p12", "PKCS12", "password", "server");
            ApiSecurityConfig config = ApiSecurityConfig.builder()
                .bindAddress("0.0.0.0")
                .authToken("token")
                .tls(TlsConfig.fromProperties(Map.of(
                    TlsConfig.KEY_KEYSTORE, keystore.toString(),
                    TlsConfig.KEY_KEYSTORE_PASSWORD, "password")))
                .build();

            assertThatCode(config::requireSafeToServe).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("allows a non-loopback bind in cleartext only when said out loud")
        void allowsPlaintextPublicBindWhenExplicit() {
            ApiSecurityConfig config = ApiSecurityConfig.builder()
                .bindAddress("0.0.0.0")
                .authToken("token")
                .allowPlaintext(true)
                .build();

            assertThatCode(config::requireSafeToServe).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the missing token is still reported before the missing TLS")
        void tokenIsReportedFirst() {
            // Both are wrong; naming the token first matches the order an operator fixes them in,
            // and keeps the message about one problem rather than two.
            ApiSecurityConfig config = ApiSecurityConfig.builder().bindAddress("0.0.0.0").build();

            assertThatThrownBy(config::requireSafeToServe)
                .hasMessageContaining(ApiSecurityConfig.KEY_AUTH_TOKEN)
                .hasMessageNotContaining("without TLS");
        }

        @Test
        @DisplayName("allows the loopback default with no token")
        void allowsLoopbackWithoutToken() throws UnknownHostException {
            ApiSecurityConfig config = ApiSecurityConfig.defaults();

            assertThatCode(config::requireSafeToServe).doesNotThrowAnyException();
            assertThat(config.bindInetAddress().isLoopbackAddress()).isTrue();
        }
    }

    @Nested
    @DisplayName("Property resolution")
    class PropertyResolution {

        @Test
        @DisplayName("reads every documented key")
        void readsKeys(@TempDir Path tempDir) throws Exception {
            Path root = Files.createDirectory(tempDir.resolve("corpus"));
            Map<String, String> props = new HashMap<>();
            props.put(ApiSecurityConfig.KEY_AUTH_TOKEN, "abc");
            props.put(ApiSecurityConfig.KEY_BIND_ADDRESS, "127.0.0.1");
            props.put(ApiSecurityConfig.KEY_READ_RATE, "7");
            props.put(ApiSecurityConfig.KEY_READ_BURST, "9");
            props.put(ApiSecurityConfig.KEY_MUTATE_RATE, "2");
            props.put(ApiSecurityConfig.KEY_MUTATE_BURST, "3");
            props.put(ApiSecurityConfig.KEY_RATE_LIMIT_MAX_CLIENTS, "11");
            props.put(ApiSecurityConfig.KEY_MAX_TOP_K, "42");
            props.put(ApiSecurityConfig.KEY_MAX_QUERY_TIMEOUT_MS, "1234");
            props.put(ApiSecurityConfig.KEY_MAX_QUERY_CHARS, "2048");
            props.put(ApiSecurityConfig.KEY_MAX_SOURCES, "5");
            props.put(ApiSecurityConfig.KEY_ALLOWED_ROOTS, root.toString());
            props.put(ApiSecurityConfig.KEY_ALLOWED_HOSTS, "Docs.Example.COM, cdn.example.com");
            props.put(ApiSecurityConfig.KEY_TRUSTED_PROXIES, "10.0.0.0/8, 192.168.1.5");

            ApiSecurityConfig config = ApiSecurityConfig.fromProperties(props);

            assertThat(config.isAuthenticationRequired()).isTrue();
            assertThat(config.readRatePerSecond()).isEqualTo(7.0);
            assertThat(config.readBurst()).isEqualTo(9.0);
            assertThat(config.mutateRatePerSecond()).isEqualTo(2.0);
            assertThat(config.mutateBurst()).isEqualTo(3.0);
            assertThat(config.rateLimitMaxClients()).isEqualTo(11);
            assertThat(config.maxTopK()).isEqualTo(42);
            assertThat(config.maxQueryTimeoutMs()).isEqualTo(1234L);
            assertThat(config.maxQueryChars()).isEqualTo(2048);
            assertThat(config.maxRegisteredSources()).isEqualTo(5);
            assertThat(config.allowedIngestRoots()).containsExactly(root.toRealPath());
            assertThat(config.allowedIngestHosts()).containsExactlyInAnyOrder(
                "docs.example.com", "cdn.example.com");
            assertThat(config.trustedProxies().trusts(InetAddress.getByName("10.9.9.9"))).isTrue();
            assertThat(config.trustedProxies().trusts(InetAddress.getByName("192.168.1.5"))).isTrue();
            assertThat(config.trustedProxies().trusts(InetAddress.getByName("192.168.1.6"))).isFalse();
        }

        @Test
        @DisplayName("trusts no proxy unless one is named")
        void trustsNoProxyByDefault() {
            assertThat(ApiSecurityConfig.fromProperties(new HashMap<>()).trustedProxies().isEmpty())
                .as("a node that believes a forwarding header by default is a node whose rate "
                    + "limit any caller can step around")
                .isTrue();
        }

        @Test
        @DisplayName("refuses to start on a malformed proxy range")
        void rejectsMalformedProxy() {
            Map<String, String> props = new HashMap<>();
            props.put(ApiSecurityConfig.KEY_TRUSTED_PROXIES, "10.0.0.0/99");

            assertThatThrownBy(() -> ApiSecurityConfig.fromProperties(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10.0.0.0/99");
        }

        @Test
        @DisplayName("falls back to defaults for unparseable or non-positive values")
        void ignoresBadValues() {
            Map<String, String> props = new HashMap<>();
            props.put(ApiSecurityConfig.KEY_READ_RATE, "not-a-number");
            props.put(ApiSecurityConfig.KEY_MAX_TOP_K, "0");
            props.put(ApiSecurityConfig.KEY_MAX_QUERY_CHARS, "-1");

            ApiSecurityConfig config = ApiSecurityConfig.fromProperties(props);

            assertThat(config.readRatePerSecond()).isEqualTo(ApiSecurityConfig.DEFAULT_READ_RATE);
            assertThat(config.maxTopK()).isEqualTo(ApiSecurityConfig.DEFAULT_MAX_TOP_K);
            assertThat(config.maxQueryChars())
                .as("a bound of zero would refuse every query, so it is not a bound this "
                    + "accepts from a typo")
                .isEqualTo(ApiSecurityConfig.DEFAULT_MAX_QUERY_CHARS);
        }

        @Test
        @DisplayName("drops an allowed root that does not exist rather than trusting it")
        void dropsUnresolvableRoot(@TempDir Path tempDir) {
            Map<String, String> props = new HashMap<>();
            props.put(ApiSecurityConfig.KEY_ALLOWED_ROOTS, tempDir.resolve("missing").toString());

            assertThat(ApiSecurityConfig.fromProperties(props).allowedIngestRoots()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Environment overrides")
    class EnvironmentOverrides {

        @Test
        @DisplayName("EXCALIBUR_API_AUTH_TOKEN sets excalibur.api.auth-token")
        void envSuppliesToken() {
            Map<String, String> env = Map.of("EXCALIBUR_API_AUTH_TOKEN", "from-env");

            // Through Config, which is now the only place the file and the environment are
            // merged. Testing a second implementation of that merge is what let the two drift.
            Map<String, String> resolved =
                new Config("excalibur.properties", env, new HashMap<>()).getEffectiveProperties();

            assertThat(resolved).containsEntry(ApiSecurityConfig.KEY_AUTH_TOKEN, "from-env");
            assertThat(ApiSecurityConfig.fromProperties(resolved)
                .matchesToken("from-env".getBytes(StandardCharsets.UTF_8))).isTrue();
        }

        @Test
        @DisplayName("environment overrides the file and ignores unrelated variables")
        void envOverridesFile(@TempDir Path tempDir) throws Exception {
            Path file = tempDir.resolve("excalibur.properties");
            Files.writeString(file, """
                # comment
                excalibur.api.max-top-k=5
                excalibur.ingest.allowed-hosts="docs.example.com"
                """);

            Map<String, String> env = new HashMap<>();
            env.put("EXCALIBUR_API_MAX_TOP_K", "77");
            env.put("PATH", "/usr/bin");

            Map<String, String> resolved =
                ConfigLoader.load(file.toString(), env).getEffectiveProperties();

            assertThat(resolved).containsEntry(ApiSecurityConfig.KEY_MAX_TOP_K, "77");
            assertThat(resolved).containsEntry(ApiSecurityConfig.KEY_ALLOWED_HOSTS, "docs.example.com");
            assertThat(resolved.keySet()).allMatch(key -> key.startsWith("excalibur."));
        }
    }
}
