package excalibur.api.security;

import java.net.Inet4Address;
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
 * <p>Only the peer address is ever consulted. Forwarding headers are attacker-controlled
 * and would let one client masquerade as many.</p>
 */
final class ClientAddressKey {

    /** Charged when the peer address is unavailable, so such requests are still metered. */
    static final String UNKNOWN = "unknown";

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
        if (address == null) {
            return UNKNOWN;
        }
        if (address instanceof Inet4Address) {
            return address.getHostAddress();
        }
        byte[] octets = address.getAddress();
        if (octets.length < IPV6_PREFIX_BYTES) {
            return address.getHostAddress();
        }
        return prefixOf(octets);
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
