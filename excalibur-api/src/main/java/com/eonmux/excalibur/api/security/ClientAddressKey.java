package com.eonmux.excalibur.api.security;

import java.net.InetAddress;

/**
 * Collapses a peer address to the unit a rate limit is charged against.
 *
 * <p>The limiter keeps one pair of buckets per key, so the key decides what a client
 * <em>is</em>. Two failures follow from getting it wrong, and they are not symmetric:</p>
 *
 * <ul>
 *   <li>A key finer than what one party controls defeats the limiter outright. The holder
 *       of an IPv6 /64 - the smallest allocation a residential connection routinely gets -
 *       can source 2^64 addresses, so a key on the full address hands them a full bucket
 *       per request. It also lets them evict every other tracked client from the limiter's
 *       bounded map.</li>
 *   <li>A key coarser than that charges unrelated clients for each other's traffic.</li>
 * </ul>
 *
 * <p>The key is therefore the smallest span a single party is routinely delegated:
 * the /64 prefix for IPv6, and the whole address for IPv4. IPv4 is scarce enough that
 * no client holds a useful range of it, while one IPv4 address routinely fronts an
 * entire office behind NAT - so widening the IPv4 key would cost real users their
 * budget and gain nothing.</p>
 *
 * <p>What address gets here is decided before this class sees it. A forwarding header is
 * attacker-controlled, so {@link RequestGuard} reads one only from a hop named in
 * {@link TrustedProxies} and takes the nearest entry the client could not have written; with no
 * proxy configured the peer address is the only thing consulted.</p>
 */
final class ClientAddressKey {

    /** Charged when the peer address is unavailable, so such requests are still metered. */
    static final String UNKNOWN = "unknown";

    /** Bytes in an IPv4 address, which is keyed whole. */
    private static final int IPV4_BYTES = 4;

    /** Leading bytes of an IPv6 address that identify the delegated network: a /64. */
    private static final int IPV6_PREFIX_BYTES = 8;

    private static final int GROUP_COUNT = IPV6_PREFIX_BYTES / 2;

    private ClientAddressKey() {
    }

    /**
     * Derives the rate-limit key for a peer.
     *
     * @param address The peer address, or null when the exchange carries none
     * @return A stable key naming the span charged
     */
    static String of(InetAddress address) {
        return ofBytes(address == null ? null : address.getAddress());
    }

    /**
     * Derives the rate-limit key from raw address bytes.
     *
     * @param octets Four or sixteen bytes, or null when the client is unknown
     * @return A stable key naming the span charged
     */
    static String ofBytes(byte[] octets) {
        if (octets == null) {
            return UNKNOWN;
        }
        if (octets.length == IPV4_BYTES) {
            return dottedQuad(octets);
        }
        if (octets.length < IPV6_PREFIX_BYTES) {
            return UNKNOWN;
        }
        return prefixOf(octets);
    }

    private static String dottedQuad(byte[] octets) {
        StringBuilder key = new StringBuilder(15);
        for (int i = 0; i < IPV4_BYTES; i++) {
            if (i > 0) {
                key.append('.');
            }
            key.append(octets[i] & 0xFF);
        }
        return key.toString();
    }

    private static String prefixOf(byte[] octets) {
        StringBuilder key = new StringBuilder(24);
        for (int group = 0; group < GROUP_COUNT; group++) {
            if (group > 0) {
                key.append(':');
            }
            int value = ((octets[group * 2] & 0xFF) << 8) | (octets[group * 2 + 1] & 0xFF);
            key.append(Integer.toHexString(value));
        }
        return key.append("::/64").toString();
    }
}
