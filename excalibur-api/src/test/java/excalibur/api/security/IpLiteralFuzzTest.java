package excalibur.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Random;

/**
 * Every literal this parser accepts must mean what the JDK says it means.
 *
 * <p>{@link IpLiteral} exists because {@link InetAddress#getByName} resolves anything that is not
 * a literal, and the strings it reads come out of a request header. Replacing a parser has to
 * come with evidence that the replacement agrees with the one it replaced, over more inputs than
 * anyone writes by hand - a trusted-proxy allowlist that disagrees with the router in front of it
 * is worse than no allowlist. Seeded, so a disagreement is reproducible.</p>
 */
class IpLiteralFuzzTest {

    private static final int ROUNDS = 5000;

    @Test
    void agreesWithTheJdkOnIpv4() throws UnknownHostException {
        Random random = new Random(20260910L);
        for (int i = 0; i < ROUNDS; i++) {
            int a = random.nextInt(256), b = random.nextInt(256);
            int c = random.nextInt(256), d = random.nextInt(256);
            String text = a + "." + b + "." + c + "." + d;
            assertThat(IpLiteral.parse(text))
                .as(text)
                .isEqualTo(InetAddress.getByName(text).getAddress());
        }
    }

    @Test
    void agreesWithTheJdkOnIpv6() throws UnknownHostException {
        Random random = new Random(7L);
        for (int i = 0; i < ROUNDS; i++) {
            int[] g = new int[8];
            for (int j = 0; j < 8; j++) {
                // Bias towards zeros so compressed forms get generated.
                g[j] = random.nextInt(4) == 0 ? 0 : random.nextInt(0x10000);
            }
            String full = render(g);
            String compressed = compress(g);
            byte[] expected = InetAddress.getByName(full).getAddress();
            assertThat(IpLiteral.parse(full)).as(full).isEqualTo(expected);
            assertThat(IpLiteral.parse(compressed)).as(compressed).isEqualTo(expected);
            assertThat(IpLiteral.parse("[" + compressed + "]")).as(compressed).isEqualTo(expected);
            assertThat(IpLiteral.parse("[" + compressed + "]:8080")).isEqualTo(expected);
            assertThat(IpLiteral.parse(compressed.toUpperCase())).isEqualTo(expected);
        }
    }

    @Test
    void agreesWithTheJdkOnEmbeddedIpv4() throws UnknownHostException {
        Random random = new Random(99L);
        for (int i = 0; i < ROUNDS; i++) {
            int a = random.nextInt(256), b = random.nextInt(256);
            int c = random.nextInt(256), d = random.nextInt(256);
            String quad = a + "." + b + "." + c + "." + d;
            for (String text : new String[] {
                "::ffff:" + quad, "::" + quad, "1:2:3:4:5:6:" + quad, "1::5:6:" + quad}) {
                assertThat(IpLiteral.parse(text))
                    .as(text)
                    .isEqualTo(InetAddress.getByName(text).getAddress());
            }
        }
    }

    private static String render(int[] g) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            text.append(i == 0 ? "" : ":").append(Integer.toHexString(g[i]));
        }
        return text.toString();
    }

    /** Collapses the longest run of zero groups, the way a canonical writer would. */
    private static String compress(int[] g) {
        int bestStart = -1, bestLen = 0, start = -1, len = 0;
        for (int i = 0; i < 8; i++) {
            if (g[i] == 0) {
                start = len == 0 ? i : start;
                len++;
                if (len > bestLen) {
                    bestLen = len;
                    bestStart = start;
                }
            } else {
                len = 0;
            }
        }
        if (bestLen < 2) {
            return render(g);
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < bestStart; i++) {
            text.append(i == 0 ? "" : ":").append(Integer.toHexString(g[i]));
        }
        text.append("::");
        for (int i = bestStart + bestLen; i < 8; i++) {
            text.append(Integer.toHexString(g[i])).append(i == 7 ? "" : ":");
        }
        return text.toString();
    }
}
