package com.eonmux.excalibur.ingest.source;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What one origin's {@code robots.txt} permits this crawler, read as RFC 9309 specifies.
 *
 * <h2>Which rules apply</h2>
 * <p>A file is a sequence of groups, each one or more {@code User-agent} lines followed by its
 * rules. The groups naming the crawler's product token - the leading letters, hyphens and
 * underscores of its {@code User-Agent}, compared without regard to case - apply, combined into
 * one. Only when none names it do the {@code *} groups apply, and when there are none of those
 * either nothing is forbidden. Blank lines and records this class does not know, {@code Sitemap}
 * among them, end nothing: RFC 9309 lets a group run on across them.</p>
 *
 * <h2>How a URL is judged</h2>
 * <p>Against its path and query, both percent-encoding normalised the same way the rules are, so
 * {@code /%7Euser} and {@code /~user} are one path while {@code /a%2Fb} and {@code /a/b} stay two.
 * Of the rules that match, the one with the most octets decides and {@code Allow} wins a tie. A
 * rule matches from the start of the path; {@code *} stands for any run of characters and a
 * trailing {@code $} anchors the rule to the end. {@code /robots.txt} itself is always allowed.</p>
 *
 * <h2>Crawl-delay</h2>
 * <p>Not part of RFC 9309, but the common way a site asks to be crawled more slowly, so it is
 * read - in seconds, fractions allowed - from the groups that apply, the largest winning. It can
 * only ever slow a crawl down, and it is held to {@link #MAX_CRAWL_DELAY}: a file asking for a
 * day between requests is asking for a crawl that never ends, and the same ceiling bounds what a
 * registration's own {@code crawl_delay_ms} may ask for.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable.</p>
 */
final class RobotsRules {

    /**
     * How much of a {@code robots.txt} is parsed: 512 KiB. RFC 9309 has a crawler parse at least
     * 500 KiB and lets it ignore what follows, which bounds what a hostile file can cost.
     */
    static final int MAX_BYTES = 512 * 1024;

    /** The longest gap a {@code Crawl-delay} may impose: one minute. */
    static final Duration MAX_CRAWL_DELAY = Duration.ofSeconds(60);

    /** What a missing or refused file means: no rule applies. */
    static final RobotsRules ALLOW_ALL = new RobotsRules(List.of(), Duration.ZERO, null);

    private static final String ROBOTS_PATH = "/robots.txt";
    private static final String WILDCARD_AGENT = "*";
    private static final String UNRESERVED_MARKS = "-._~";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final List<Rule> rules;
    private final Duration crawlDelay;

    /** Why the file could not be read, when that is what these rules stand for. */
    private final String unreachableReason;

    private RobotsRules(List<Rule> rules, Duration crawlDelay, String unreachableReason) {
        this.rules = List.copyOf(rules);
        this.crawlDelay = crawlDelay;
        this.unreachableReason = unreachableReason;
    }

    /**
     * The rules that stand for a file that could not be read at all.
     *
     * <p>RFC 9309 has a crawler assume a complete disallow when the file is unreachable - a
     * server error or a network failure - because a site whose rules cannot be read has not
     * said that crawling it is fine.</p>
     *
     * @param reason What went wrong, for whoever reads the source's status
     * @return Rules that forbid everything
     */
    static RobotsRules unreachable(String reason) {
        return new RobotsRules(List.of(), Duration.ZERO, Objects.requireNonNull(reason, "reason"));
    }

    /**
     * Reads the answer to a {@code robots.txt} request, as RFC 9309 section 2.3.1 classifies it.
     *
     * <p>A success is parsed. Any 4xx - a missing file above all - means the site has no rules
     * for this crawler, and everything is allowed. Anything else, 5xx included, is an unreachable
     * file. Redirects never reach here: the caller follows them.</p>
     *
     * @param status    The final status code
     * @param body      The body, of which only the first {@link #MAX_BYTES} are read
     * @param userAgent The crawler's {@code User-Agent}
     * @param robotsUrl The file's URL, for the reason an unreachable one gives
     * @return The rules that apply to this crawler
     */
    static RobotsRules fromResponse(int status, byte[] body, String userAgent, String robotsUrl) {
        if (status >= 200 && status < 300) {
            return parse(decode(body), userAgent);
        }
        if (status >= 400 && status < 500) {
            return ALLOW_ALL;
        }
        return unreachable("robots.txt at " + robotsUrl + " answered HTTP " + status);
    }

    /**
     * Decodes the parsed part of a file as the UTF-8 RFC 9309 requires it to be.
     *
     * <p>A file over the cap is cut at its last complete line before it. A line cut in half is a
     * rule with the wrong path - {@code Disallow: /late-and-long} read as {@code Disallow: /l} -
     * so it is dropped rather than obeyed.</p>
     */
    private static String decode(byte[] body) {
        int length = body.length;
        if (length > MAX_BYTES) {
            length = MAX_BYTES;
            while (length > 0 && body[length - 1] != '\n' && body[length - 1] != '\r') {
                length--;
            }
        }
        return new String(body, 0, length, StandardCharsets.UTF_8);
    }

    /**
     * Parses a file and keeps the rules that apply to one crawler.
     *
     * @param content   The file's text
     * @param userAgent The crawler's {@code User-Agent}
     * @return The rules for that crawler
     */
    static RobotsRules parse(String content, String userAgent) {
        String token = productToken(userAgent);
        List<Group> groups = parseGroups(content);

        List<Group> applicable = new ArrayList<>();
        if (!token.isEmpty()) {
            for (Group group : groups) {
                if (group.agents.contains(token)) {
                    applicable.add(group);
                }
            }
        }
        if (applicable.isEmpty()) {
            for (Group group : groups) {
                if (group.agents.contains(WILDCARD_AGENT)) {
                    applicable.add(group);
                }
            }
        }

        List<Rule> rules = new ArrayList<>();
        Duration delay = Duration.ZERO;
        for (Group group : applicable) {
            rules.addAll(group.rules);
            if (group.crawlDelay.compareTo(delay) > 0) {
                delay = group.crawlDelay;
            }
        }
        return new RobotsRules(rules, delay, null);
    }

    private static List<Group> parseGroups(String content) {
        List<Group> groups = new ArrayList<>();
        Group current = null;
        // Consecutive User-agent lines name one group; the first rule ends the run of them, and
        // a User-agent line after it starts the next group.
        boolean readingAgents = false;

        String text = content.startsWith("﻿") ? content.substring(1) : content;
        for (String rawLine : text.split("\r\n|\r|\n")) {
            int hash = rawLine.indexOf('#');
            String line = (hash >= 0 ? rawLine.substring(0, hash) : rawLine).trim();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();

            switch (key) {
                case "user-agent" -> {
                    if (!readingAgents) {
                        current = new Group();
                        groups.add(current);
                        readingAgents = true;
                    }
                    String agent = agentOf(value);
                    if (!agent.isEmpty()) {
                        current.agents.add(agent);
                    }
                }
                case "allow", "disallow" -> {
                    if (current == null) {
                        continue;
                    }
                    readingAgents = false;
                    // An empty value is not a rule: "Disallow:" is how a file says "nothing".
                    if (!value.isEmpty()) {
                        current.rules.add(Rule.of("allow".equals(key), normalize(value)));
                    }
                }
                case "crawl-delay" -> {
                    if (current == null) {
                        continue;
                    }
                    readingAgents = false;
                    Duration delay = crawlDelayOf(value);
                    if (delay.compareTo(current.crawlDelay) > 0) {
                        current.crawlDelay = delay;
                    }
                }
                default -> {
                    // Sitemap and any other record: not a rule, and not the end of a group.
                }
            }
        }
        return groups;
    }

    /** The name a {@code User-agent} line gives, as compared: its product token, or {@code *}. */
    private static String agentOf(String value) {
        return value.startsWith(WILDCARD_AGENT) ? WILDCARD_AGENT : productToken(value);
    }

    /**
     * The product token of a {@code User-Agent}: its leading letters, hyphens and underscores,
     * lower-cased.
     *
     * <p>RFC 9309 limits a token to those characters, which is what separates it from the
     * version and comments that follow it in a header - {@code ExcaliburDB/1.0 (+url)} is
     * {@code excaliburdb}. A value that opens with anything else has no token and matches only
     * the {@code *} group.</p>
     *
     * @param userAgent A {@code User-Agent} value
     * @return The token, lower-cased, possibly empty
     */
    static String productToken(String userAgent) {
        int end = 0;
        while (end < userAgent.length()) {
            char c = userAgent.charAt(end);
            boolean tokenChar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || c == '-' || c == '_';
            if (!tokenChar) {
                break;
            }
            end++;
        }
        return userAgent.substring(0, end).toLowerCase(Locale.ROOT);
    }

    /**
     * Reads a {@code Crawl-delay} value in seconds, or zero when it is not a usable one.
     *
     * <p>A value that is not a finite, non-negative number is ignored rather than guessed at;
     * one above {@link #MAX_CRAWL_DELAY} is held to it.</p>
     */
    private static Duration crawlDelayOf(String value) {
        double seconds;
        try {
            seconds = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return Duration.ZERO;
        }
        if (!Double.isFinite(seconds) || seconds <= 0) {
            return Duration.ZERO;
        }
        double millis = seconds * 1000.0;
        if (millis >= MAX_CRAWL_DELAY.toMillis()) {
            return MAX_CRAWL_DELAY;
        }
        return Duration.ofMillis(Math.round(millis));
    }

    /**
     * Normalises a path, or a rule's pattern, to the one form RFC 9309 section 2.2.2 compares.
     *
     * <p>An escape of an unreserved character - a letter, a digit, {@code -._~} - is decoded,
     * because {@code %7E} and {@code ~} are the same URL. Every other escape is kept, with its
     * hex upper-cased, because decoding {@code %2F} would turn part of a segment into a path
     * separator. Anything outside printable ASCII is percent-encoded as UTF-8, which is how a rule
     * written as {@code /café} meets a URL that asks for {@code /caf%C3%A9}. {@code *} and
     * {@code $} are printable ASCII and pass through, so a pattern keeps its wildcards.</p>
     *
     * @param value A path with its query, or a rule's pattern
     * @return The normalised form
     */
    static String normalize(String value) {
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '%' && isHex(value, i + 1) && isHex(value, i + 2)) {
                int octet = Integer.parseInt(value.substring(i + 1, i + 3), 16);
                if (isUnreserved(octet)) {
                    out.append((char) octet);
                } else {
                    appendEscape(out, octet);
                }
                i += 3;
            } else if (c > 0x20 && c < 0x7F) {
                out.append(c);
                i++;
            } else {
                int codePoint = value.codePointAt(i);
                byte[] encoded = new String(Character.toChars(codePoint))
                    .getBytes(StandardCharsets.UTF_8);
                for (byte b : encoded) {
                    appendEscape(out, b & 0xFF);
                }
                i += Character.charCount(codePoint);
            }
        }
        return out.toString();
    }

    private static boolean isHex(String value, int index) {
        if (index >= value.length()) {
            return false;
        }
        char c = value.charAt(index);
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isUnreserved(int octet) {
        return (octet >= 'a' && octet <= 'z') || (octet >= 'A' && octet <= 'Z')
            || (octet >= '0' && octet <= '9') || UNRESERVED_MARKS.indexOf(octet) >= 0;
    }

    private static void appendEscape(StringBuilder out, int octet) {
        out.append('%').append(HEX[octet >> 4]).append(HEX[octet & 0xF]);
    }

    /**
     * Whether this crawler may fetch a path.
     *
     * @param pathAndQuery The raw path, with {@code ?} and the raw query when there is one
     * @return true when the rules allow it
     */
    boolean allows(String pathAndQuery) {
        if (unreachableReason != null) {
            return false;
        }
        String path = normalize(pathAndQuery.isEmpty() ? "/" : pathAndQuery);
        if (ROBOTS_PATH.equals(path)) {
            return true;
        }
        Rule decisive = null;
        for (Rule rule : rules) {
            if (rule.matches(path) && (decisive == null || rule.outranks(decisive))) {
                decisive = rule;
            }
        }
        return decisive == null || decisive.allow;
    }

    /**
     * The gap this origin asks for between requests.
     *
     * @return The {@code Crawl-delay} of the applicable groups, at most {@link #MAX_CRAWL_DELAY};
     *         zero when none asks for one
     */
    Duration crawlDelay() {
        return crawlDelay;
    }

    /**
     * Whether these rules were read from the origin, or stand for a file that could not be.
     *
     * @return false for a complete disallow standing in for an unreachable file
     */
    boolean isReachable() {
        return unreachableReason == null;
    }

    /**
     * Why the file could not be read.
     *
     * @return The reason, or null when it was read or was missing
     */
    String unreachableReason() {
        return unreachableReason;
    }

    /** One {@code User-agent} group while the file is being read. */
    private static final class Group {
        private final List<String> agents = new ArrayList<>();
        private final List<Rule> rules = new ArrayList<>();
        private Duration crawlDelay = Duration.ZERO;
    }

    /**
     * One {@code Allow} or {@code Disallow} line.
     *
     * @param allow    Whether it allows
     * @param glob     The normalised pattern without an anchoring {@code $}
     * @param anchored Whether the pattern ended in {@code $}
     * @param octets   The pattern's length as written, which is what precedence compares
     */
    private record Rule(boolean allow, String glob, boolean anchored, int octets) {

        static Rule of(boolean allow, String pattern) {
            boolean anchored = pattern.endsWith("$");
            String glob = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
            return new Rule(allow, glob, anchored, pattern.length());
        }

        boolean outranks(Rule other) {
            return octets > other.octets || (octets == other.octets && allow && !other.allow);
        }

        /**
         * Matches the pattern against the whole path when anchored, and against a prefix of it
         * otherwise.
         *
         * <p>The usual two-cursor wildcard match: on a mismatch it resumes just after the last
         * {@code *}, one character further into the path, so no star is ever retried from a
         * position an earlier one already covered. That keeps a pattern of many stars at
         * quadratic cost, where the regular expression it is equivalent to backtracks
         * exponentially.</p>
         */
        boolean matches(String path) {
            int p = 0;
            int s = 0;
            int star = -1;
            int resume = 0;
            while (s < path.length()) {
                if (p < glob.length() && glob.charAt(p) == '*') {
                    star = p++;
                    resume = s;
                } else if (p < glob.length() && glob.charAt(p) == path.charAt(s)) {
                    p++;
                    s++;
                } else if (p == glob.length() && !anchored) {
                    return true;
                } else if (star >= 0) {
                    p = star + 1;
                    s = ++resume;
                } else {
                    return false;
                }
            }
            while (p < glob.length() && glob.charAt(p) == '*') {
                p++;
            }
            return p == glob.length();
        }
    }
}
