package excalibur.api.bootstrap;

import excalibur.api.security.ApiSecurityConfig;
import excalibur.api.security.TlsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The inventory of {@code excalibur.api.} settings, which decides whether a typo is noticed.
 *
 * @author ExcaliburDB
 */
@DisplayName("API configuration keys")
class ApiConfigKeysTest {

    @Test
    @DisplayName("every key the policy classes declare is recognised")
    void everyDeclaredKeyIsKnown() {
        assertThat(ApiConfigKeys.isKnown(ApiSecurityConfig.KEY_MUTATE_RATE)).isTrue();
        assertThat(ApiConfigKeys.isKnown(ApiSecurityConfig.KEY_MAX_TOP_K)).isTrue();
        assertThat(ApiConfigKeys.isKnown(ApiSecurityConfig.KEY_ALLOW_PLAINTEXT)).isTrue();
        assertThat(ApiConfigKeys.isKnown(TlsConfig.KEY_KEYSTORE)).isTrue();
        assertThat(ApiConfigKeys.isKnown(TlsConfig.KEY_ENABLED)).isTrue();
    }

    @Test
    @DisplayName("a key nothing answers to is not recognised")
    void aTypoIsNotKnown() {
        // The failure this exists to catch: parsed, stored, never consulted, and the node runs
        // on the shipped rate limit believing it runs on the configured one.
        assertThat(ApiConfigKeys.isKnown("excalibur.api.mutate-rate-per-secnod")).isFalse();
    }

    @Test
    @DisplayName("a key under another module's prefix is left to that module")
    void otherPrefixesAreNotJudged() {
        assertThat(ApiConfigKeys.isKnown("excalibur.shard.lsh-probes")).isFalse();
        assertThatCode(() -> ApiConfigKeys.reportUnrecognised(
            Map.of("excalibur.shard.lsh-probes", "3", "excalibur.filter.fingerprint-bits", "13")))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("reporting reads the settings rather than failing over one it cannot place")
    void reportingIsAWarningAndNotARefusal() {
        // A warning, not a refusal: the same file is reused across versions, and refusing a key
        // a newer build reads would turn a rollback into an outage.
        assertThatCode(() -> ApiConfigKeys.reportUnrecognised(
            Map.of("excalibur.api.mutate-rate-per-secnod", "1")))
            .doesNotThrowAnyException();
    }
}
