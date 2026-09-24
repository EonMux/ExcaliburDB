package com.eonmux.excalibur.ingest.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PublicAddresses")
class PublicAddressesTest {

    private static InetAddress literal(String address) throws UnknownHostException {
        // A literal is parsed, never looked up.
        return InetAddress.getByName(address);
    }

    /** Builds an address from its sixteen bytes, so no JDK conversion of mapped forms applies. */
    private static InetAddress ipv6(int... bytes) throws UnknownHostException {
        byte[] raw = new byte[16];
        for (int i = 0; i < 16; i++) {
            raw[i] = (byte) bytes[i];
        }
        return java.net.Inet6Address.getByAddress(null, raw, -1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "0.0.0.0", "0.1.2.3",                       // this network
        "10.0.0.5",                                 // private
        "100.64.0.1", "100.127.255.254",            // shared address space (CGNAT)
        "127.0.0.1", "127.8.9.10",                  // loopback
        "169.254.169.254",                          // link-local, the metadata endpoint
        "172.16.3.9", "172.31.255.255",             // private
        "192.0.0.8", "192.0.0.170",                 // IETF protocol assignments
        "192.0.2.1",                                // TEST-NET-1
        "192.88.99.1",                              // 6to4 relay anycast
        "192.168.1.7",                              // private
        "198.18.0.1", "198.19.255.255",             // benchmarking
        "198.51.100.7", "203.0.113.9",              // TEST-NET-2 and 3
        "224.0.0.1", "239.255.255.250",             // multicast
        "240.0.0.1", "255.255.255.255"              // reserved, broadcast
    })
    @DisplayName("refuses every IPv4 range that is not publicly routed")
    void nonPublicIpv4(String address) throws UnknownHostException {
        assertThat(PublicAddresses.isPublic(literal(address))).as(address).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "1.1.1.1", "8.8.8.8", "93.184.215.14",
        "100.63.255.255", "100.128.0.0",            // either side of 100.64.0.0/10
        "172.15.255.255", "172.32.0.0",             // either side of 172.16.0.0/12
        "198.17.255.255", "198.20.0.0",             // either side of 198.18.0.0/15
        "192.0.1.1", "223.255.255.254"
    })
    @DisplayName("accepts public IPv4")
    void publicIpv4(String address) throws UnknownHostException {
        assertThat(PublicAddresses.isPublic(literal(address))).as(address).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "::", "::1",                                // unspecified, loopback
        "::a9fe:a9fe",                              // IPv4-compatible 169.254.169.254
        "64:ff9b::a9fe:a9fe",                       // NAT64 to the metadata endpoint
        "64:ff9b::a00:1",                           // NAT64 to 10.0.0.1
        "64:ff9b:1::808:808",                       // local-use NAT64
        "100::1",                                   // discard-only
        "2001::1", "2001:db8::1",                   // IETF assignments, documentation
        "2002:a9fe:a9fe::1",                        // 6to4 around 169.254.169.254
        "2002:7f00:1::",                            // 6to4 around loopback
        "3fff::1", "5f00::1",                       // documentation, SRv6
        "fc00::1", "fd12:3456::1",                  // unique local
        "fe80::1", "fec0::1",                       // link-local, site-local
        "ff02::1",                                  // multicast
        "4000::1"                                   // outside global unicast
    })
    @DisplayName("refuses every IPv6 range that is not publicly routed, embedded IPv4 included")
    void nonPublicIpv6(String address) throws UnknownHostException {
        assertThat(PublicAddresses.isPublic(literal(address))).as(address).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2606:4700:4700::1111",                     // global unicast
        "64:ff9b::808:808",                         // NAT64 to 8.8.8.8
        "2002:808:808::1"                           // 6to4 around 8.8.8.8
    })
    @DisplayName("accepts public IPv6, and translations to public IPv4")
    void publicIpv6(String address) throws UnknownHostException {
        assertThat(PublicAddresses.isPublic(literal(address))).as(address).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {127, 10, 0})
    @DisplayName("judges an IPv4-mapped address by the IPv4 address it carries")
    void mappedIpv4(int firstOctet) throws UnknownHostException {
        // Built from bytes, so this holds whether or not the JDK hands back the IPv4 form.
        InetAddress internal = ipv6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, firstOctet, 0, 0, 1);
        InetAddress external = ipv6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 8, 8, 8, 8);

        assertThat(PublicAddresses.isPublic(internal)).isFalse();
        assertThat(PublicAddresses.isPublic(external)).isTrue();
    }
}
