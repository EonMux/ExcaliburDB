package excalibur.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The unit a rate limit is charged against.
 *
 * <p>A key that is too fine hands an attacker a fresh budget per request; a key that is
 * too coarse charges unrelated clients for each other's traffic. The two errors are not
 * symmetric - the first defeats the limiter outright - so the tests are written from the
 * side of what an address's owner actually controls.</p>
 */
@DisplayName("Client address key")
class ClientAddressKeyTest {

    @Nested
    @DisplayName("IPv6")
    class SixtyFourBitPrefix {

        @Test
        @DisplayName("charges a whole delegated prefix as one client")
        void oneKeyPerPrefix() throws UnknownHostException {
            // A residential IPv6 allocation is a /64 at its smallest. Keying on the full
            // address would give its holder 2^64 buckets, which is no limit at all.
            String first = ClientAddressKey.of(InetAddress.getByName("2001:db8:1:2::1"));
            String last = ClientAddressKey.of(InetAddress.getByName("2001:db8:1:2:ffff:ffff:ffff:ffff"));
            assertThat(first).isEqualTo(last);
        }

        @Test
        @DisplayName("still separates one prefix from the next")
        void neighbouringPrefixesStayApart() throws UnknownHostException {
            assertThat(ClientAddressKey.of(InetAddress.getByName("2001:db8:1:2::1")))
                .isNotEqualTo(ClientAddressKey.of(InetAddress.getByName("2001:db8:1:3::1")));
        }

        @Test
        @DisplayName("names the prefix it charged, not a bare address")
        void theKeyReadsAsAPrefix() throws UnknownHostException {
            assertThat(ClientAddressKey.of(InetAddress.getByName("2001:db8:1:2::1")))
                .isEqualTo("2001:db8:1:2::/64");
        }

        @Test
        @DisplayName("collapses an IPv4-mapped address onto its IPv4 key")
        void mappedAddressesAreNotASecondIdentity() throws UnknownHostException {
            // ::ffff:203.0.113.7 and 203.0.113.7 are the same peer. Two keys would mean
            // two budgets for one client.
            assertThat(ClientAddressKey.of(InetAddress.getByName("::ffff:203.0.113.7")))
                .isEqualTo(ClientAddressKey.of(InetAddress.getByName("203.0.113.7")));
        }
    }

    @Nested
    @DisplayName("IPv4")
    class WholeAddress {

        @Test
        @DisplayName("charges each address separately")
        void neighboursAreNotChargedForEachOther() throws UnknownHostException {
            // IPv4 is scarce enough that no client holds a meaningful range of it, while
            // one address routinely fronts a whole office behind NAT. Widening the key
            // here would punish those users for nothing gained.
            assertThat(ClientAddressKey.of(InetAddress.getByName("203.0.113.7")))
                .isNotEqualTo(ClientAddressKey.of(InetAddress.getByName("203.0.113.8")));
        }

        @Test
        @DisplayName("keys on the address as written")
        void theKeyIsTheAddress() throws UnknownHostException {
            assertThat(ClientAddressKey.of(InetAddress.getByName("203.0.113.7")))
                .isEqualTo("203.0.113.7");
        }
    }

    @Nested
    @DisplayName("An address that is missing")
    class Unknown {

        @Test
        @DisplayName("is charged against one shared budget rather than skipping the limit")
        void nullIsStillCharged() {
            assertThat(ClientAddressKey.of(null)).isEqualTo("unknown");
        }
    }
}
