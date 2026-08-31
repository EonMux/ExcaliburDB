package excalibur.ingest.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-request address check.
 *
 * <p>An allow-listed hostname says nothing about where it points. DNS can be repointed between the
 * moment a source is registered — which is when the API layer validates its addresses — and the
 * moment the crawl actually runs, so a name that resolved publicly at registration can resolve to
 * a metadata endpoint by the time it is fetched. Re-resolving per request is what makes that
 * ineffective.
 */
@DisplayName("HttpSource address policy")
class HttpSourceAddressPolicyTest {

    private static HttpSource source(String baseUrl, boolean allowPrivate) {
        return HttpSource.builder()
            .sourceId("address-policy-test")
            .baseUrl(baseUrl)
            .allowedDomains(Set.of("localhost", "example.com"))
            .allowPrivateAddresses(allowPrivate)
            .build();
    }

    @Test
    @DisplayName("an allow-listed host resolving to loopback is refused by default")
    void loopbackRefusedByDefault() {
        // localhost is in the allow-list, so only the address check can reject it. This is the
        // rebind case: the name passed every check except where it actually points.
        HttpSource secure = source("http://localhost:8080/", false);

        assertThat(secure.getAllowedDomains()).contains("localhost");
        assertThat(secure.isUrlAllowedForTesting("http://localhost:8080/page"))
            .as("in the allow-list, but resolves to 127.0.0.1")
            .isFalse();
    }

    @Test
    @DisplayName("opting in permits it, which is how a local crawl is expressed")
    void optInPermitsLoopback() {
        assertThat(source("http://localhost:8080/", true)
            .isUrlAllowedForTesting("http://localhost:8080/page"))
            .isTrue();
    }

    @Test
    @DisplayName("a host outside the allow-list is refused whatever it resolves to")
    void allowListStillApplies() {
        assertThat(source("http://localhost:8080/", true)
            .isUrlAllowedForTesting("http://elsewhere.invalid/page"))
            .isFalse();
    }

    @Test
    @DisplayName("an unresolvable host is refused rather than allowed through")
    void unresolvableRefused() {
        // Failing open here would turn every transient DNS failure into a bypass.
        HttpSource secure = HttpSource.builder()
            .sourceId("address-policy-test")
            .baseUrl("http://localhost:8080/")
            .allowedDomains(Set.of("no-such-host.invalid"))
            .allowPrivateAddresses(false)
            .build();

        assertThat(secure.isUrlAllowedForTesting("http://no-such-host.invalid/page")).isFalse();
    }

    @Test
    @DisplayName("a non-http scheme is refused")
    void schemeStillChecked() {
        assertThat(source("http://localhost:8080/", true)
            .isUrlAllowedForTesting("file:///etc/passwd"))
            .isFalse();
    }
}
