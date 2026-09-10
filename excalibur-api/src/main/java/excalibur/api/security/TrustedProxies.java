package excalibur.api.security;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The hops whose forwarding headers this node believes.
 *
 * <h2>Why this has to be configured</h2>
 * <p>A rate limit is only a limit if the thing it counts is the thing a caller cannot forge.
 * The peer address qualifies; {@code X-Forwarded-For} does not, because any client can write
 * whatever it likes there and be metered as a thousand different clients - or as somebody else,
 * whose budget it then spends.</p>
 *
 * <p>But behind a TLS-terminating proxy the peer address is the proxy, identical for every
 * caller, so metering it puts the entire internet in one bucket. Both readings are wrong, and
 * no reading of the request alone can tell them apart: whether the header is evidence or a lie
 * depends on who is in front of this node, which is a deployment fact and therefore
 * configuration.</p>
 *
 * <p>So: empty by default, and with it empty the peer address is the only thing consulted. Name
 * the proxies and their forwarding header is read - but only theirs, and only the part of it
 * they could not have been told by the client. See
 * {@link #originBehind(java.util.List, byte[])}.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable, and safe to share.</p>
 */
public final class TrustedProxies {

    private static final TrustedProxies NONE = new TrustedProxies(List.of());

    private final List<Range> ranges;

    private TrustedProxies(List<Range> ranges) {
        this.ranges = ranges;
    }

    /** A node with nothing in front of it: only the peer address is ever believed. */
    public static TrustedProxies none() {
        return NONE;
    }

    /**
     * Trusts the given addresses and CIDR ranges.
     *
     * @param specs Entries like {@code 10.0.0.0/8}, {@code 192.168.1.7} or {@code ::1};
     *              an entry with no prefix length is the single address
     * @return The set
     * @throws NullPointerException if specs is null
     * @throws IllegalArgumentException if an entry is not an address or range
     */
    public static TrustedProxies of(Collection<String> specs) {
        Objects.requireNonNull(specs, "specs must not be null");
        List<Range> parsed = new ArrayList<>(specs.size());
        for (String spec : specs) {
            parsed.add(Range.parse(spec));
        }
        return parsed.isEmpty() ? NONE : new TrustedProxies(List.copyOf(parsed));
    }

    /** Whether any proxy is trusted at all. */
    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    /**
     * Whether {@code address} is one of the trusted hops.
     *
     * @param address The peer address, which may be null
     * @return true if a forwarding header from this peer may be believed
     */
    public boolean trusts(InetAddress address) {
        return address != null && trusts(address.getAddress());
    }

    /**
     * Whether the raw address bytes name a trusted hop.
     *
     * @param address 4 or 16 bytes, or null
     * @return true if a forwarding header from this peer may be believed
     */
    boolean trusts(byte[] address) {
        if (address == null) {
            return false;
        }
        for (Range range : ranges) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The client a chain of forwarding-header values describes, read from the right.
     *
     * <p>Each proxy appends the peer it saw, so the rightmost entry was written by the hop
     * nearest this node and is the only one that hop observed rather than inherited. Walking
     * right to left and stopping at the first address that is not itself a trusted proxy yields
     * the closest hop this deployment has not vouched for - which is either the real client or
     * the first thing a client could have forged, and those are the same address to charge.</p>
     *
     * <p>Reading left to right instead - taking "the original client" the chain claims - is the
     * classic spoof: the leftmost entry is whatever the first caller wrote.</p>
     *
     * @param forwarded The header values, in the order received; may be empty
     * @param peer      The peer address, returned when the chain names nobody untrusted
     * @return The address to charge
     */
    byte[] originBehind(List<String> forwarded, byte[] peer) {
        List<String> hops = new ArrayList<>();
        for (String header : forwarded) {
            for (String hop : header.split(",")) {
                hops.add(hop);
            }
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            byte[] candidate = IpLiteral.parse(hops.get(i));
            // An unparseable entry ends the walk rather than being skipped. Skipping it would let
            // a client put junk to the right of the address it wants blamed and have this reach
            // past the junk to find it.
            if (candidate == null) {
                return peer;
            }
            if (!trusts(candidate)) {
                return candidate;
            }
        }
        return peer;
    }

    @Override
    public String toString() {
        return ranges.stream().map(Range::toString).collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * One trusted address or CIDR range.
     *
     * @param network    The masked network address, 4 or 16 bytes
     * @param prefixBits How many leading bits an address must share to be inside
     */
    private record Range(byte[] network, int prefixBits) {

        static Range parse(String spec) {
            String text = spec == null ? "" : spec.trim();
            int slash = text.lastIndexOf('/');
            String addressPart = slash < 0 ? text : text.substring(0, slash);
            byte[] address = IpLiteral.parse(addressPart);
            if (address == null) {
                throw new IllegalArgumentException(
                    "Not an address or CIDR range: '" + spec + "'");
            }
            int bits = address.length * Byte.SIZE;
            if (slash >= 0) {
                bits = prefixLength(text.substring(slash + 1), address.length * Byte.SIZE, spec);
            }
            return new Range(mask(address, bits), bits);
        }

        private static int prefixLength(String text, int maximum, String spec) {
            int bits;
            try {
                bits = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Prefix length is not a number: '" + spec + "'", e);
            }
            if (bits < 0 || bits > maximum) {
                throw new IllegalArgumentException(
                    "Prefix length must be between 0 and " + maximum + ": '" + spec + "'");
            }
            return bits;
        }

        /** Clears the host bits, so 10.1.2.3/8 and 10.0.0.0/8 are the same range. */
        private static byte[] mask(byte[] address, int prefixBits) {
            byte[] masked = address.clone();
            for (int i = 0; i < masked.length; i++) {
                int keep = prefixBits - i * Byte.SIZE;
                if (keep >= Byte.SIZE) {
                    continue;
                }
                masked[i] = keep <= 0 ? 0 : (byte) (masked[i] & (0xFF << (Byte.SIZE - keep)));
            }
            return masked;
        }

        boolean contains(byte[] candidate) {
            // An IPv4 range never contains an IPv6 address, or the reverse: the two are separate
            // spaces, and matching across them would trust a host nobody named.
            if (candidate.length != network.length) {
                return false;
            }
            return Arrays.equals(mask(candidate, prefixBits), network);
        }

        @Override
        public String toString() {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < network.length; i++) {
                text.append(i == 0 ? "" : network.length == IpLiteral.IPV4_BYTES ? "." : ":");
                text.append(network.length == IpLiteral.IPV4_BYTES
                    ? Integer.toString(network[i] & 0xFF)
                    : String.format("%02x", network[i]));
            }
            return text.append('/').append(prefixBits).toString();
        }
    }
}
