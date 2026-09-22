package com.eonmux.excalibur.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the resolver-free address parser.
 *
 * <p>The valid cases are checked against {@link InetAddress}, which is the definition this has
 * to agree with: an address parsed here is compared with one taken from a socket, and a parser
 * that disagreed with the platform would make one client look like two.</p>
 */
@DisplayName("IpLiteral")
class IpLiteralTest {

    @Nested
    @DisplayName("Addresses it accepts")
    class Accepts {

        @ParameterizedTest
        @ValueSource(strings = {
            "0.0.0.0", "127.0.0.1", "10.0.0.1", "203.0.113.7", "255.255.255.255",
            "::", "::1", "2001:db8::1", "2001:db8:1:2:3:4:5:6", "fe80::1",
            "2001:db8::", "::ffff:203.0.113.7", "2001:db8::203.0.113.7",
        })
        @DisplayName("agree byte for byte with the platform parser")
        void matchesInetAddress(String literal) throws UnknownHostException {
            assertThat(IpLiteral.parse(literal))
                .containsExactly(InetAddress.getByName(literal).getAddress());
        }

        @Test
        @DisplayName("an IPv4-mapped address is the four bytes it stands for")
        void mappedCollapsesToFour() {
            assertThat(IpLiteral.parse("::ffff:203.0.113.7"))
                .isEqualTo(IpLiteral.parse("203.0.113.7"))
                .hasSize(4);
        }

        @Test
        @DisplayName("a port is ignored, in both families")
        void portIsIgnored() {
            assertThat(IpLiteral.parse("203.0.113.7:8080")).isEqualTo(IpLiteral.parse("203.0.113.7"));
            assertThat(IpLiteral.parse("[2001:db8::1]:8080")).isEqualTo(IpLiteral.parse("2001:db8::1"));
        }

        @Test
        @DisplayName("brackets and surrounding space are ignored")
        void bracketsAndSpace() {
            assertThat(IpLiteral.parse("  [2001:db8::1]  ")).isEqualTo(IpLiteral.parse("2001:db8::1"));
        }

        @Test
        @DisplayName("a zone id names a local interface and is dropped")
        void zoneIsDropped() {
            assertThat(IpLiteral.parse("fe80::1%eth0")).isEqualTo(IpLiteral.parse("fe80::1"));
        }
    }

    @Nested
    @DisplayName("Text it refuses")
    class Refuses {

        @ParameterizedTest
        @ValueSource(strings = {
            "", "   ", "proxy.internal", "localhost", "example.com",
            "1.2.3", "1.2.3.4.5", "1.2.3.", "256.0.0.1", "-1.0.0.1", "1.2.3.a",
            "1:2:3:4:5:6:7:8:9", "1::2::3", ":1:2:3:4:5:6:7", "12345::1", "::g",
            "1.2.3.4::", "[2001:db8::1", "1:2:3:4:5:6:7",
        })
        @DisplayName("is not an address")
        void isNotAnAddress(String text) {
            assertThat(IpLiteral.parse(text)).isNull();
        }

        @Test
        @DisplayName("null")
        void nullText() {
            assertThat(IpLiteral.parse(null)).isNull();
        }

        @Test
        @DisplayName("a leading zero, which means different addresses to different parsers")
        void leadingZeroIsAmbiguous() {
            assertThat(IpLiteral.parse("010.0.0.1")).isNull();
            assertThat(IpLiteral.parse("127.0.0.01")).isNull();
        }

        @Test
        @DisplayName("a hostname, without ever asking a resolver")
        void hostnamesAreNeverResolved() {
            // Any name at all: the point is that nothing here can produce a lookup, so a caller
            // cannot spend the node's resolver by writing names into a forwarding header.
            assertThat(IpLiteral.parse("a-name-that-does-not-resolve.invalid")).isNull();
        }
    }
}
