package com.eonmux.excalibur.ingest.source;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;

/**
 * Decides whether an address is on the public internet, which is the only place a crawl may be
 * sent.
 *
 * <h2>Why one implementation</h2>
 * <p>Two checks stand between a caller-supplied URL and a request: the API refuses a
 * registration whose host resolves anywhere internal, and {@link HttpSource} re-resolves before
 * every request because DNS can be repointed after registration. Each had its own list of ranges,
 * and a range missing from either list is a way past it. Both ask this class.</p>
 *
 * <h2>What is public</h2>
 * <p>The predicates on {@link InetAddress} cover loopback, link-local, the private ranges,
 * multicast and the unspecified address, and nothing else: the shared address space carriers
 * use for NAT ({@code 100.64.0.0/10}), which reaches whatever the provider's network reaches;
 * benchmarking, documentation and reserved space; and every IPv6 form that carries an IPv4
 * address inside it all pass them. So the rule is written out from the IANA special-purpose
 * address registries instead:</p>
 * <ul>
 *   <li><b>IPv4</b> is public unless it falls in one of the special-purpose blocks listed in
 *       {@link #NON_PUBLIC_IPV4}.</li>
 *   <li><b>IPv6</b> is public only inside global unicast, {@code 2000::/3}, and outside the
 *       special-purpose blocks within it. Everything else - loopback, unique-local, link-local,
 *       site-local, multicast, discard-only and every unassigned range - is not.</li>
 *   <li><b>An IPv6 address that carries an IPv4 one</b> is judged by the IPv4 address:
 *       IPv4-mapped ({@code ::ffff:0:0/96}) and IPv4-compatible ({@code ::/96}) forms, the NAT64
 *       well-known prefix ({@code 64:ff9b::/96}), and 6to4 ({@code 2002::/16}). A NAT64 gateway
 *       forwards {@code 64:ff9b::a9fe:a9fe} to {@code 169.254.169.254}, so judging the IPv6 form
 *       alone would send the crawler to the metadata endpoint.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; every method is a pure function of its argument.</p>
 */
public final class PublicAddresses {

    /** An address block: the leading bits every address in it shares. */
    private record Block(byte[] prefix, int bits) {

        static Block of(int bits, int... prefix) {
            byte[] bytes = new byte[prefix.length];
            for (int i = 0; i < prefix.length; i++) {
                bytes[i] = (byte) prefix[i];
            }
            return new Block(bytes, bits);
        }

        boolean contains(byte[] address) {
            if (address.length < prefix.length) {
                return false;
            }
            int whole = bits / Byte.SIZE;
            for (int i = 0; i < whole; i++) {
                if (address[i] != prefix[i]) {
                    return false;
                }
            }
            int rest = bits % Byte.SIZE;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (Byte.SIZE - rest)) & 0xFF;
            return (address[whole] & mask) == (prefix[whole] & mask);
        }
    }

    /** The IPv4 special-purpose blocks that are not globally reachable. */
    private static final List<Block> NON_PUBLIC_IPV4 = List.of(
        Block.of(8, 0),                    // 0.0.0.0/8, "this network"
        Block.of(8, 10),                   // 10.0.0.0/8, private
        Block.of(10, 100, 64),             // 100.64.0.0/10, shared address space (carrier NAT)
        Block.of(8, 127),                  // 127.0.0.0/8, loopback
        Block.of(16, 169, 254),            // 169.254.0.0/16, link-local
        Block.of(12, 172, 16),             // 172.16.0.0/12, private
        Block.of(24, 192, 0, 0),           // 192.0.0.0/24, IETF protocol assignments
        Block.of(24, 192, 0, 2),           // 192.0.2.0/24, documentation (TEST-NET-1)
        Block.of(24, 192, 88, 99),         // 192.88.99.0/24, 6to4 relay anycast
        Block.of(16, 192, 168),            // 192.168.0.0/16, private
        Block.of(15, 198, 18),             // 198.18.0.0/15, benchmarking
        Block.of(24, 198, 51, 100),        // 198.51.100.0/24, documentation (TEST-NET-2)
        Block.of(24, 203, 0, 113),         // 203.0.113.0/24, documentation (TEST-NET-3)
        Block.of(4, 224),                  // 224.0.0.0/4, multicast
        Block.of(4, 240));                 // 240.0.0.0/4, reserved, and the broadcast address

    /** Global unicast, the only IPv6 space that is routed on the internet. */
    private static final Block GLOBAL_UNICAST = Block.of(3, 0x20);

    /** The special-purpose blocks inside global unicast that are not globally reachable. */
    private static final List<Block> NON_PUBLIC_IPV6 = List.of(
        Block.of(23, 0x20, 0x01, 0x00),    // 2001::/23, IETF protocol assignments
        Block.of(32, 0x20, 0x01, 0x0d, 0xb8), // 2001:db8::/32, documentation
        Block.of(20, 0x3f, 0xff, 0x00),    // 3fff::/20, documentation
        Block.of(16, 0x5f, 0x00));         // 5f00::/16, segment routing identifiers

    /** IPv4-mapped, {@code ::ffff:0:0/96}: the last four bytes are the IPv4 address. */
    private static final Block IPV4_MAPPED =
        Block.of(96, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF);

    /** IPv4-compatible, {@code ::/96}, deprecated but still parsed: the last four bytes. */
    private static final Block IPV4_COMPATIBLE =
        Block.of(96, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** The NAT64 well-known prefix, {@code 64:ff9b::/96}: the last four bytes. */
    private static final Block NAT64 =
        Block.of(96, 0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0);

    /** 6to4, {@code 2002::/16}: bytes two to five are the IPv4 address. */
    private static final Block SIX_TO_FOUR = Block.of(16, 0x20, 0x02);

    private static final int IPV4_BYTES = 4;
    private static final int IPV6_BYTES = 16;

    private PublicAddresses() {
        throw new AssertionError("Utility class");
    }

    /**
     * Whether a request to this address stays on the public internet.
     *
     * @param address The resolved address
     * @return true when it is publicly routed, and every IPv4 address it carries is too
     * @throws NullPointerException if address is null
     */
    public static boolean isPublic(InetAddress address) {
        Objects.requireNonNull(address, "address must not be null");
        byte[] bytes = address.getAddress();
        if (bytes.length == IPV4_BYTES) {
            return isPublicIpv4(bytes);
        }
        return bytes.length == IPV6_BYTES && isPublicIpv6(bytes);
    }

    private static boolean isPublicIpv4(byte[] address) {
        for (Block block : NON_PUBLIC_IPV4) {
            if (block.contains(address)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPublicIpv6(byte[] address) {
        if (IPV4_MAPPED.contains(address) || IPV4_COMPATIBLE.contains(address)
                || NAT64.contains(address)) {
            return isPublicIpv4(slice(address, IPV6_BYTES - IPV4_BYTES));
        }
        if (SIX_TO_FOUR.contains(address)) {
            return isPublicIpv4(slice(address, 2));
        }
        if (!GLOBAL_UNICAST.contains(address)) {
            return false;
        }
        for (Block block : NON_PUBLIC_IPV6) {
            if (block.contains(address)) {
                return false;
            }
        }
        return true;
    }

    /** The four bytes of an IPv4 address carried at {@code from}. */
    private static byte[] slice(byte[] address, int from) {
        byte[] ipv4 = new byte[IPV4_BYTES];
        System.arraycopy(address, from, ipv4, 0, IPV4_BYTES);
        return ipv4;
    }
}
