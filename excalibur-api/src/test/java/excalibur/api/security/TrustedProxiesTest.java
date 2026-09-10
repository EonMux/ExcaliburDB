package excalibur.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the set of hops whose forwarding header is believed.
 */
@DisplayName("TrustedProxies")
class TrustedProxiesTest {

    @Nested
    @DisplayName("Membership")
    class Membership {

        @Test
        @DisplayName("nothing is trusted by default")
        void emptyByDefault() throws UnknownHostException {
            assertThat(TrustedProxies.none().isEmpty()).isTrue();
            assertThat(TrustedProxies.none().trusts(InetAddress.getByName("10.0.0.1"))).isFalse();
        }

        @Test
        @DisplayName("a bare address is that address and no other")
        void singleAddress() throws UnknownHostException {
            TrustedProxies proxies = TrustedProxies.of(List.of("10.0.0.7"));

            assertThat(proxies.trusts(InetAddress.getByName("10.0.0.7"))).isTrue();
            assertThat(proxies.trusts(InetAddress.getByName("10.0.0.8"))).isFalse();
        }

        @Test
        @DisplayName("a CIDR range covers its addresses and stops at its edge")
        void cidrRange() throws UnknownHostException {
            TrustedProxies proxies = TrustedProxies.of(List.of("10.1.0.0/16"));

            assertThat(proxies.trusts(InetAddress.getByName("10.1.0.0"))).isTrue();
            assertThat(proxies.trusts(InetAddress.getByName("10.1.255.255"))).isTrue();
            assertThat(proxies.trusts(InetAddress.getByName("10.2.0.0"))).isFalse();
            assertThat(proxies.trusts(InetAddress.getByName("10.0.255.255"))).isFalse();
        }

        @Test
        @DisplayName("host bits in the range are ignored, so 10.1.2.3/16 is 10.1.0.0/16")
        void hostBitsAreMasked() throws UnknownHostException {
            TrustedProxies proxies = TrustedProxies.of(List.of("10.1.2.3/16"));

            assertThat(proxies.trusts(InetAddress.getByName("10.1.9.9"))).isTrue();
        }

        @Test
        @DisplayName("IPv6 ranges work, and do not reach into IPv4")
        void ipv6Ranges() throws UnknownHostException {
            TrustedProxies proxies = TrustedProxies.of(List.of("2001:db8::/32"));

            assertThat(proxies.trusts(InetAddress.getByName("2001:db8:1:2::9"))).isTrue();
            assertThat(proxies.trusts(InetAddress.getByName("2001:db9::1"))).isFalse();
            assertThat(proxies.trusts(InetAddress.getByName("10.0.0.1"))).isFalse();
        }

        @Test
        @DisplayName("an IPv4 range never matches an IPv6 address")
        void familiesDoNotCross() throws UnknownHostException {
            TrustedProxies proxies = TrustedProxies.of(List.of("0.0.0.0/0"));

            assertThat(proxies.trusts(InetAddress.getByName("203.0.113.1"))).isTrue();
            assertThat(proxies.trusts(InetAddress.getByName("2001:db8::1"))).isFalse();
        }

        @Test
        @DisplayName("a null peer is not trusted")
        void nullPeer() {
            assertThat(TrustedProxies.of(List.of("0.0.0.0/0")).trusts((InetAddress) null)).isFalse();
        }

        @Test
        @DisplayName("a malformed entry is refused at startup rather than silently dropped")
        void malformedEntryIsRefused() {
            assertThatThrownBy(() -> TrustedProxies.of(List.of("proxy.internal")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proxy.internal");
            assertThatThrownBy(() -> TrustedProxies.of(List.of("10.0.0.0/33")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 and 32");
            assertThatThrownBy(() -> TrustedProxies.of(List.of("10.0.0.0/wide")))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Reading a forwarding chain")
    class ReadingTheChain {

        private final TrustedProxies proxies = TrustedProxies.of(List.of("10.0.0.0/8"));
        private final byte[] peer = IpLiteral.parse("10.0.0.1");

        @Test
        @DisplayName("takes the rightmost hop that is not itself a trusted proxy")
        void takesTheNearestUntrustedHop() {
            byte[] origin = proxies.originBehind(
                List.of("203.0.113.9, 198.51.100.4, 10.0.0.2"), peer);

            assertThat(origin).isEqualTo(IpLiteral.parse("198.51.100.4"));
        }

        @Test
        @DisplayName("ignores what the client claims about earlier hops")
        void leftmostEntryIsNotBelieved() {
            // The whole left of the chain is whatever the caller wrote. Only the rightmost entry
            // was observed by a hop this deployment trusts.
            byte[] origin = proxies.originBehind(List.of("1.2.3.4, 203.0.113.9"), peer);

            assertThat(origin).isEqualTo(IpLiteral.parse("203.0.113.9"));
        }

        @Test
        @DisplayName("falls back to the peer when the chain names only trusted proxies")
        void allHopsTrusted() {
            assertThat(proxies.originBehind(List.of("10.0.0.5, 10.0.0.6"), peer)).isEqualTo(peer);
        }

        @Test
        @DisplayName("falls back to the peer when there is no chain at all")
        void noChain() {
            assertThat(proxies.originBehind(List.of(), peer)).isEqualTo(peer);
            assertThat(proxies.originBehind(List.of(""), peer)).isEqualTo(peer);
        }

        @Test
        @DisplayName("stops at junk rather than reaching past it")
        void junkEndsTheWalk() {
            // Reaching past an unparseable entry would let a client write junk to the right of
            // the address it wants blamed and have this find it anyway.
            assertThat(proxies.originBehind(List.of("203.0.113.9, not-an-address"), peer))
                .isEqualTo(peer);
        }

        @Test
        @DisplayName("reads a chain split across repeated headers")
        void chainSplitAcrossHeaders() {
            byte[] origin = proxies.originBehind(List.of("203.0.113.9", "10.0.0.2"), peer);

            assertThat(origin).isEqualTo(IpLiteral.parse("203.0.113.9"));
        }

        @Test
        @DisplayName("tolerates the spacing proxies actually emit")
        void spacing() {
            assertThat(proxies.originBehind(List.of("  203.0.113.9  ,  10.0.0.2  "), peer))
                .isEqualTo(IpLiteral.parse("203.0.113.9"));
        }
    }
}
