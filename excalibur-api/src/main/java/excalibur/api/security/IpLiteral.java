package excalibur.api.security;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads an IP address that arrived as text, without ever consulting a resolver.
 *
 * <p>{@link java.net.InetAddress#getByName} is the obvious way to turn a string into an address
 * and the wrong one here: handed something that is not a literal it performs a name lookup, and
 * some of the strings this reads come out of a request header. A DNS round trip per request,
 * driven by whatever a caller chose to write in it, is a denial of service with the node's own
 * resolver as the amplifier. Nothing in this class leaves the CPU.</p>
 *
 * <p>Strict on purpose. A leading zero is refused rather than read as octal, because
 * {@code 010.0.0.1} means different addresses to different parsers and a proxy allowlist that
 * disagrees with the router in front of it is worse than one that rejects the entry outright.</p>
 */
final class IpLiteral {

    /** Bytes in an IPv4 address. */
    static final int IPV4_BYTES = 4;

    /** Bytes in an IPv6 address. */
    static final int IPV6_BYTES = 16;

    private static final int IPV6_GROUPS = 8;
    private static final int MAX_OCTET_DIGITS = 3;
    private static final int MAX_GROUP_DIGITS = 4;

    private IpLiteral() {
    }

    /**
     * The address {@code text} spells.
     *
     * <p>Accepts a bare IPv4 or IPv6 literal, either wrapped in brackets, and either followed by
     * a port - the forms a proxy actually writes into a forwarding header. An IPv6 zone id is
     * ignored: it names a local interface and means nothing on another host.</p>
     *
     * @param text The candidate, which may be null
     * @return The address bytes, 4 or 16 of them, or null if {@code text} is not an address
     */
    static byte[] parse(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.charAt(0) == '[') {
            int close = value.indexOf(']');
            return close < 0 ? null : parseIpv6(value.substring(1, close));
        }
        int firstColon = value.indexOf(':');
        if (firstColon < 0) {
            return parseIpv4(value);
        }
        if (firstColon == value.lastIndexOf(':')) {
            // One colon and no brackets is an IPv4 address with a port. IPv6 always has more.
            return parseIpv4(value.substring(0, firstColon));
        }
        return parseIpv6(value);
    }

    private static byte[] parseIpv4(String value) {
        byte[] octets = new byte[IPV4_BYTES];
        int start = 0;
        for (int i = 0; i < IPV4_BYTES; i++) {
            int end = i == IPV4_BYTES - 1 ? value.length() : value.indexOf('.', start);
            if (end < 0) {
                return null;
            }
            int octet = decimal(value, start, end);
            if (octet < 0 || octet > 255) {
                return null;
            }
            octets[i] = (byte) octet;
            start = end + 1;
        }
        return octets;
    }

    private static byte[] parseIpv6(String text) {
        String value = text;
        int zone = value.indexOf('%');
        if (zone >= 0) {
            value = value.substring(0, zone);
        }
        int compression = value.indexOf("::");
        if (compression >= 0 && compression != value.lastIndexOf("::")) {
            return null;
        }

        List<String> head = groups(compression < 0 ? value : value.substring(0, compression));
        List<String> tail = compression < 0 ? List.of() : groups(value.substring(compression + 2));
        if (head == null || tail == null) {
            return null;
        }

        // A trailing dotted quad stands for the last two groups, so it can only appear at the end
        // of the address: in the tail when the address is compressed, and in the head when it is
        // not. "1.2.3.4::" puts one at the front, which is not an address.
        List<String> trailing = compression < 0 ? head : tail;
        byte[] embedded = null;
        if (!trailing.isEmpty() && trailing.get(trailing.size() - 1).indexOf('.') >= 0) {
            embedded = parseIpv4(trailing.get(trailing.size() - 1));
            if (embedded == null) {
                return null;
            }
            trailing = trailing.subList(0, trailing.size() - 1);
            if (compression < 0) {
                head = trailing;
            } else {
                tail = trailing;
            }
        }

        int embeddedGroups = embedded == null ? 0 : 2;
        int written = head.size() + tail.size() + embeddedGroups;
        // Uncompressed addresses must be complete; compressed ones must leave the "::" something
        // to stand for, so a full eight groups alongside it is a contradiction.
        if (compression < 0 ? written != IPV6_GROUPS : written >= IPV6_GROUPS) {
            return null;
        }

        byte[] address = new byte[IPV6_BYTES];
        int at = 0;
        for (String group : head) {
            int value16 = hex(group);
            if (value16 < 0) {
                return null;
            }
            address[at++] = (byte) (value16 >>> 8);
            address[at++] = (byte) value16;
        }
        at = IPV6_BYTES - (tail.size() + embeddedGroups) * 2;
        for (String group : tail) {
            int value16 = hex(group);
            if (value16 < 0) {
                return null;
            }
            address[at++] = (byte) (value16 >>> 8);
            address[at++] = (byte) value16;
        }
        if (embedded != null) {
            System.arraycopy(embedded, 0, address, IPV6_BYTES - IPV4_BYTES, IPV4_BYTES);
        }
        return unmapped(address);
    }

    /**
     * Collapses an IPv4-mapped address to the four bytes it stands for.
     *
     * <p>A dual-stack listener reports an IPv4 client as {@code ::ffff:a.b.c.d}, and
     * {@link java.net.InetAddress} hands back four bytes for that text. Anything comparing an
     * address parsed here against one taken from a socket has to agree with it, or the same
     * client is two different clients depending on which side of the process it came from.</p>
     *
     * @param address Sixteen bytes
     * @return The four bytes when the address is IPv4-mapped, else the address unchanged
     */
    private static byte[] unmapped(byte[] address) {
        for (int i = 0; i < 10; i++) {
            if (address[i] != 0) {
                return address;
            }
        }
        if (address[10] != (byte) 0xFF || address[11] != (byte) 0xFF) {
            return address;
        }
        byte[] four = new byte[IPV4_BYTES];
        System.arraycopy(address, IPV6_BYTES - IPV4_BYTES, four, 0, IPV4_BYTES);
        return four;
    }

    /**
     * The colon-separated groups of {@code value}, or null if any of them is empty.
     *
     * @param value One side of the address; empty when "::" is at that end
     * @return The groups, at most eight of them, or null if the text is not groups
     */
    private static List<String> groups(String value) {
        if (value.isEmpty()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>(IPV6_GROUPS);
        int start = 0;
        while (true) {
            int colon = value.indexOf(':', start);
            String part = colon < 0 ? value.substring(start) : value.substring(start, colon);
            if (part.isEmpty() || parts.size() == IPV6_GROUPS) {
                return null;
            }
            parts.add(part);
            if (colon < 0) {
                return parts;
            }
            start = colon + 1;
        }
    }

    private static int decimal(String value, int from, int to) {
        int length = to - from;
        if (length < 1 || length > MAX_OCTET_DIGITS) {
            return -1;
        }
        if (length > 1 && value.charAt(from) == '0') {
            return -1;
        }
        int result = 0;
        for (int i = from; i < to; i++) {
            char digit = value.charAt(i);
            if (digit < '0' || digit > '9') {
                return -1;
            }
            result = result * 10 + (digit - '0');
        }
        return result;
    }

    private static int hex(String group) {
        if (group.isEmpty() || group.length() > MAX_GROUP_DIGITS) {
            return -1;
        }
        int result = 0;
        for (int i = 0; i < group.length(); i++) {
            int digit = Character.digit(group.charAt(i), 16);
            if (digit < 0) {
                return -1;
            }
            result = (result << 4) | digit;
        }
        return result;
    }
}
