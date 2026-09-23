package com.eonmux.excalibur.ingest.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC 9309 semantics of a parsed robots.txt, as they apply to one crawler.
 */
@DisplayName("robots.txt rules")
class RobotsRulesTest {

    private static final String AGENT = "ExcaliburDB/1.0";

    private static RobotsRules parse(String body) {
        return RobotsRules.parse(body, AGENT);
    }

    @Nested
    @DisplayName("Group selection")
    class GroupSelection {

        @Test
        @DisplayName("the group naming the crawler's product token applies, case-insensitively")
        void productTokenGroupApplies() {
            RobotsRules rules = parse("""
                User-agent: *
                Disallow: /

                User-agent: excaliburdb
                Disallow: /private/
                """);

            assertThat(rules.allows("/public/page.html")).isTrue();
            assertThat(rules.allows("/private/page.html")).isFalse();
        }

        @Test
        @DisplayName("the '*' group applies when no group names the crawler")
        void wildcardFallback() {
            RobotsRules rules = parse("""
                User-agent: OtherBot
                Disallow: /

                User-agent: *
                Disallow: /tmp/
                """);

            assertThat(rules.allows("/index.html")).isTrue();
            assertThat(rules.allows("/tmp/x")).isFalse();
        }

        @Test
        @DisplayName("the '*' group does not apply when a group names the crawler")
        void specificGroupReplacesWildcard() {
            RobotsRules rules = parse("""
                User-agent: *
                Disallow: /tmp/

                User-agent: ExcaliburDB
                Disallow: /private/
                """);

            assertThat(rules.allows("/tmp/x")).isTrue();
        }

        @Test
        @DisplayName("nothing applies when no group names the crawler and there is no '*' group")
        void noMatchingGroupAllowsEverything() {
            RobotsRules rules = parse("""
                User-agent: OtherBot
                Disallow: /
                """);

            assertThat(rules.allows("/anything")).isTrue();
        }

        @Test
        @DisplayName("consecutive user-agent lines share one group")
        void consecutiveAgentsShareAGroup() {
            RobotsRules rules = parse("""
                User-agent: OtherBot
                User-agent: ExcaliburDB
                Disallow: /shared/
                """);

            assertThat(rules.allows("/shared/x")).isFalse();
        }

        @Test
        @DisplayName("every group naming the crawler is combined into one")
        void matchingGroupsAreCombined() {
            RobotsRules rules = parse("""
                User-agent: ExcaliburDB
                Disallow: /a/

                User-agent: OtherBot
                Disallow: /b/

                User-agent: ExcaliburDB
                Disallow: /c/
                """);

            assertThat(rules.allows("/a/x")).isFalse();
            assertThat(rules.allows("/b/x")).isTrue();
            assertThat(rules.allows("/c/x")).isFalse();
        }

        @Test
        @DisplayName("a version on the robots.txt side of the match is ignored")
        void versionOnTheFileSideIsIgnored() {
            RobotsRules rules = parse("""
                User-agent: ExcaliburDB/2.0
                Disallow: /
                """);

            assertThat(rules.allows("/x")).isFalse();
        }

        @Test
        @DisplayName("a crawler whose token is only a prefix of a group's name is not that group")
        void prefixIsNotAMatch() {
            RobotsRules rules = parse("""
                User-agent: ExcaliburDBPlus
                Disallow: /
                """);

            assertThat(rules.allows("/x")).isTrue();
        }

        @Test
        @DisplayName("rules before any user-agent line belong to no group")
        void rulesBeforeAnyGroupAreIgnored() {
            RobotsRules rules = parse("""
                Disallow: /
                User-agent: *
                Disallow: /tmp/
                """);

            assertThat(rules.allows("/index.html")).isTrue();
        }

        @Test
        @DisplayName("the product token is the leading letters, hyphens and underscores")
        void productToken() {
            assertThat(RobotsRules.productToken("ExcaliburDB/1.0 (+https://x)"))
                .isEqualTo("excaliburdb");
            assertThat(RobotsRules.productToken("my_crawler-bot 2")).isEqualTo("my_crawler-bot");
            assertThat(RobotsRules.productToken("1bot")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Rule precedence")
    class Precedence {

        @Test
        @DisplayName("the longest matching rule decides")
        void longestMatchWins() {
            RobotsRules rules = parse("""
                User-agent: *
                Disallow: /docs/
                Allow: /docs/public/
                """);

            assertThat(rules.allows("/docs/secret.html")).isFalse();
            assertThat(rules.allows("/docs/public/page.html")).isTrue();
        }

        @Test
        @DisplayName("a longer disallow overrides a shorter allow")
        void longerDisallowOverridesAllow() {
            RobotsRules rules = parse("""
                User-agent: *
                Allow: /docs/
                Disallow: /docs/drafts/
                """);

            assertThat(rules.allows("/docs/drafts/a")).isFalse();
            assertThat(rules.allows("/docs/a")).isTrue();
        }

        @Test
        @DisplayName("allow wins a tie")
        void allowWinsTies() {
            RobotsRules rules = parse("""
                User-agent: *
                Disallow: /page
                Allow: /page
                """);

            assertThat(rules.allows("/page")).isTrue();
        }

        @Test
        @DisplayName("an empty disallow allows everything")
        void emptyDisallowAllows() {
            RobotsRules rules = parse("""
                User-agent: *
                Disallow:
                """);

            assertThat(rules.allows("/anything")).isTrue();
        }

        @Test
        @DisplayName("rules match from the start of the path, never inside it")
        void rulesArePrefixes() {
            RobotsRules rules = parse("""
                User-agent: *
                Disallow: /private
                """);

            assertThat(rules.allows("/private.html")).isFalse();
            assertThat(rules.allows("/a/private")).isTrue();
        }

        @Test
        @DisplayName("the query is part of what a rule matches")
        void queryIsMatched() {
            RobotsRules rules = parse("""
                User-agent: *
                Disallow: /search?q=
                """);

            assertThat(rules.allows("/search?q=cats")).isFalse();
            assertThat(rules.allows("/search")).isTrue();
        }

        @Test
        @DisplayName("robots.txt itself is always allowed")
        void robotsTxtIsAllowed() {
            RobotsRules rules = parse("""
                User-agent: *
                Disallow: /
                """);

            assertThat(rules.allows("/robots.txt")).isTrue();
            assertThat(rules.allows("/")).isFalse();
        }
    }

    @Nested
    @DisplayName("Wildcards")
    class Wildcards {

        @Test
        @DisplayName("'*' matches any run of characters, including none")
        void starMatchesAnything() {
            RobotsRules rules = parse("""
                User-agent: *
                Disallow: /*.pdf
                """);

            assertThat(rules.allows("/files/report.pdf")).isFalse();
            assertThat(rules.allows("/.pdf")).isFalse();
            assertThat(rules.allows("/files/report.pdf.html")).isFalse();
            assertThat(rules.allows("/files/report.html")).isTrue();
        }

        @Test
        @DisplayName("a trailing '$' anchors the rule to the end of the path")
        void dollarAnchors() {
            RobotsRules rules = parse("""
                User-agent: *
                Disallow: /*.pdf$
                """);

            assertThat(rules.allows("/files/report.pdf")).isFalse();
            assertThat(rules.allows("/files/report.pdf.html")).isTrue();
            assertThat(rules.allows("/files/report.pdf?page=2")).isTrue();
        }

        @Test
        @DisplayName("'$' anywhere but the end is an ordinary character")
        void innerDollarIsLiteral() {
            RobotsRules rules = parse("""
                User-agent: *
                Disallow: /a$b
                """);

            assertThat(rules.allows("/a$b/c")).isFalse();
            assertThat(rules.allows("/a")).isTrue();
        }

        @Test
        @DisplayName("an anchored root disallows only the root itself")
        void anchoredRoot() {
            RobotsRules rules = parse("""
                User-agent: *
                Disallow: /$
                """);

            assertThat(rules.allows("/")).isFalse();
            assertThat(rules.allows("/page")).isTrue();
        }

        @Test
        @DisplayName("several stars backtrack correctly")
        void severalStars() {
            RobotsRules rules = parse("""
                User-agent: *
                Disallow: /*/private/*/draft
                """);

            assertThat(rules.allows("/a/b/private/c/d/draft-1")).isFalse();
            assertThat(rules.allows("/a/private/draft")).isTrue();
        }

        @Test
        @DisplayName("a pathological pattern does not take exponential time")
        void pathologicalPatternIsBounded() {
            RobotsRules rules = parse("User-agent: *\nDisallow: /" + "*a".repeat(40) + "b\n");
            String path = "/" + "a".repeat(5_000);

            long start = System.nanoTime();
            assertThat(rules.allows(path)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Nested
    @DisplayName("Percent-encoding")
    class PercentEncoding {

        @Test
        @DisplayName("an encoded unreserved character matches its plain form, both ways")
        void unreservedIsDecoded() {
            RobotsRules rules = parse("""
                User-agent: *
                Disallow: /%7Euser/
                Disallow: /b%61r
                """);

            assertThat(rules.allows("/~user/home")).isFalse();
            assertThat(rules.allows("/bar")).isFalse();
            assertThat(rules.allows("/b%61r")).isFalse();
        }

        @Test
        @DisplayName("an encoded reserved character stays distinct from the character itself")
        void reservedStaysEncoded() {
            RobotsRules rules = parse("""
                User-agent: *
                Disallow: /a%2Fb
                """);

            assertThat(rules.allows("/a%2fb")).isFalse();
            assertThat(rules.allows("/a/b")).isTrue();
        }

        @Test
        @DisplayName("non-ASCII in a rule matches the percent-encoded UTF-8 of a URL")
        void nonAsciiIsEncoded() {
            RobotsRules rules = parse("""
                User-agent: *
                Disallow: /café
                """);

            assertThat(rules.allows("/caf%C3%A9/menu")).isFalse();
            assertThat(rules.allows("/caf%c3%a9")).isFalse();
            assertThat(rules.allows("/cafe")).isTrue();
        }

        @Test
        @DisplayName("normalisation upper-cases escapes and decodes only unreserved octets")
        void normalise() {
            assertThat(RobotsRules.normalize("/%7e%2f%41 é")).isEqualTo("/~%2FA%20%C3%A9");
            assertThat(RobotsRules.normalize("/100%")).isEqualTo("/100%");
        }
    }

    @Nested
    @DisplayName("File syntax")
    class Syntax {

        @Test
        @DisplayName("directive names are case-insensitive and comments are ignored")
        void caseAndComments() {
            RobotsRules rules = parse("""
                # a comment
                USER-AGENT: *   # trailing comment
                dIsAlLoW: /x # why
                """);

            assertThat(rules.allows("/x/y")).isFalse();
        }

        @Test
        @DisplayName("blank lines and unknown records do not end a group")
        void blankLinesAndUnknownRecords() {
            RobotsRules rules = parse("""
                User-agent: *

                Sitemap: https://example.com/sitemap.xml
                Disallow: /x
                """);

            assertThat(rules.allows("/x")).isFalse();
        }

        @Test
        @DisplayName("CR, LF and CRLF all end a line, and a byte-order mark is skipped")
        void lineEndingsAndBom() {
            RobotsRules rules = parse("﻿User-agent: *\rDisallow: /a\r\nDisallow: /b\nAllow: /b/c");

            assertThat(rules.allows("/a")).isFalse();
            assertThat(rules.allows("/b")).isFalse();
            assertThat(rules.allows("/b/c")).isTrue();
        }

        @Test
        @DisplayName("a line without a colon is ignored")
        void malformedLine() {
            RobotsRules rules = parse("""
                User-agent: *
                Disallow /x
                Disallow: /y
                """);

            assertThat(rules.allows("/x")).isTrue();
            assertThat(rules.allows("/y")).isFalse();
        }

        @Test
        @DisplayName("an empty file allows everything")
        void emptyFile() {
            assertThat(parse("").allows("/x")).isTrue();
        }
    }

    @Nested
    @DisplayName("Crawl-delay")
    class CrawlDelay {

        @Test
        @DisplayName("is read in seconds from the crawler's group, fractions included")
        void readFromGroup() {
            RobotsRules rules = parse("""
                User-agent: *
                Crawl-delay: 30

                User-agent: ExcaliburDB
                Crawl-delay: 1.5
                """);

            assertThat(rules.crawlDelay()).isEqualTo(Duration.ofMillis(1500));
        }

        @Test
        @DisplayName("the larger value wins when combined groups disagree")
        void largestWins() {
            RobotsRules rules = parse("""
                User-agent: ExcaliburDB
                Crawl-delay: 2

                User-agent: ExcaliburDB
                Crawl-delay: 5
                """);

            assertThat(rules.crawlDelay()).isEqualTo(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("an absurd value is held to the ceiling")
        void absurdIsCapped() {
            RobotsRules rules = parse("""
                User-agent: *
                Crawl-delay: 86400
                """);

            assertThat(rules.crawlDelay()).isEqualTo(RobotsRules.MAX_CRAWL_DELAY);
        }

        @Test
        @DisplayName("a malformed, negative or non-finite value is ignored")
        void malformedIgnored() {
            assertThat(parse("User-agent: *\nCrawl-delay: soon\n").crawlDelay()).isZero();
            assertThat(parse("User-agent: *\nCrawl-delay: -3\n").crawlDelay()).isZero();
            assertThat(parse("User-agent: *\nCrawl-delay: NaN\n").crawlDelay()).isZero();
            assertThat(parse("User-agent: *\nCrawl-delay: Infinity\n").crawlDelay()).isZero();
        }

        @Test
        @DisplayName("is zero when none is given")
        void absent() {
            assertThat(parse("User-agent: *\nDisallow: /x\n").crawlDelay()).isZero();
        }
    }

    @Nested
    @DisplayName("Answers to the robots.txt request")
    class Responses {

        private static final String URL = "https://example.com/robots.txt";

        private RobotsRules answer(int status, String body) {
            return RobotsRules.fromResponse(status, body.getBytes(StandardCharsets.UTF_8), AGENT, URL);
        }

        @Test
        @DisplayName("a success is parsed")
        void successIsParsed() {
            RobotsRules rules = answer(200, "User-agent: *\nDisallow: /x\n");

            assertThat(rules.isReachable()).isTrue();
            assertThat(rules.allows("/x")).isFalse();
        }

        @Test
        @DisplayName("any 4xx, 404 included, allows everything")
        void clientErrorAllowsEverything() {
            for (int status : new int[] {400, 401, 403, 404, 410, 429, 499}) {
                RobotsRules rules = answer(status, "User-agent: *\nDisallow: /\n");
                assertThat(rules.isReachable()).isTrue();
                assertThat(rules.allows("/x")).as("HTTP %d", status).isTrue();
            }
        }

        @Test
        @DisplayName("a 5xx disallows everything and says why")
        void serverErrorDisallowsEverything() {
            RobotsRules rules = answer(503, "");

            assertThat(rules.isReachable()).isFalse();
            assertThat(rules.allows("/x")).isFalse();
            assertThat(rules.allows("/robots.txt")).isFalse();
            assertThat(rules.unreachableReason()).contains(URL).contains("503");
        }

        @Test
        @DisplayName("an answer that is neither success, redirect nor error is unreachable")
        void otherStatusesAreUnreachable() {
            assertThat(answer(304, "").isReachable()).isFalse();
            assertThat(answer(100, "").isReachable()).isFalse();
        }

        @Test
        @DisplayName("only the first 512 KiB are parsed, and a line cut in half is dropped")
        void sizeCap() {
            String straddling = "Disallow: /late-and-long\n";
            int cutInside = "Disallow: /late".length();
            String filler = "# " + "x".repeat(200) + "\n";
            StringBuilder body = new StringBuilder("User-agent: *\nDisallow: /early\n");
            while (body.length() + filler.length() < RobotsRules.MAX_BYTES - cutInside) {
                body.append(filler);
            }
            int pad = RobotsRules.MAX_BYTES - cutInside - body.length();
            body.append('#').append("x".repeat(pad - 2)).append('\n');
            // Starts so that the cap falls inside it: kept, what is left would read
            // "Disallow: /late" and forbid far more than the file says.
            body.append(straddling);
            body.append("Disallow: /beyond\n");

            RobotsRules rules = answer(200, body.toString());

            assertThat(body.indexOf(straddling)).isEqualTo(RobotsRules.MAX_BYTES - cutInside);
            assertThat(rules.allows("/early")).isFalse();
            assertThat(rules.allows("/late-and-long")).isTrue();
            assertThat(rules.allows("/lately")).isTrue();
            assertThat(rules.allows("/beyond")).isTrue();
        }

        @Test
        @DisplayName("the cap is at least the 500 KiB RFC 9309 requires")
        void capMeetsTheRfc() {
            assertThat(RobotsRules.MAX_BYTES).isGreaterThanOrEqualTo(500 * 1024);
        }

        @Test
        @DisplayName("allow-all and unreachable are what they say")
        void constants() {
            assertThat(RobotsRules.ALLOW_ALL.allows("/x")).isTrue();
            assertThat(RobotsRules.ALLOW_ALL.crawlDelay()).isZero();
            assertThat(RobotsRules.ALLOW_ALL.unreachableReason()).isNull();
            RobotsRules down = RobotsRules.unreachable("robots.txt at x could not be read");
            assertThat(down.allows("/")).isFalse();
            assertThat(down.unreachableReason()).isEqualTo("robots.txt at x could not be read");
        }
    }
}
