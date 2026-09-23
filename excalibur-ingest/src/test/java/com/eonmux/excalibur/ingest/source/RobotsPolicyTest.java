package com.eonmux.excalibur.ingest.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-run cache of robots.txt rules, one entry per origin.
 */
@DisplayName("robots.txt cache for one run")
class RobotsPolicyTest {

    @Test
    @DisplayName("an origin's file is loaded once and reused")
    void loadsOncePerOrigin() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        RobotsPolicy policy = new RobotsPolicy(origin -> {
            loads.incrementAndGet();
            return RobotsRules.ALLOW_ALL;
        });

        policy.rulesFor("https://example.com:443");
        policy.rulesFor("https://example.com:443");
        policy.rulesFor("http://example.com:80");

        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("an entry older than a day is loaded again")
    void expiresAfterADay() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        AtomicLong now = new AtomicLong(0);
        RobotsPolicy policy = new RobotsPolicy(origin -> {
            loads.incrementAndGet();
            return RobotsRules.ALLOW_ALL;
        }, now::get);

        policy.rulesFor("https://example.com:443");
        now.set(RobotsPolicy.MAX_AGE.minusSeconds(1).toNanos());
        policy.rulesFor("https://example.com:443");
        assertThat(loads).hasValue(1);

        now.set(RobotsPolicy.MAX_AGE.plusSeconds(1).toNanos());
        policy.rulesFor("https://example.com:443");
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("threads asking at once share one load")
    void concurrentCallersShareOneLoad() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RobotsPolicy policy = new RobotsPolicy(origin -> {
            loads.incrementAndGet();
            loading.countDown();
            release.await(10, TimeUnit.SECONDS);
            return RobotsRules.unreachable("down");
        });

        List<Thread> threads = new ArrayList<>();
        List<RobotsRules> answers = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 4; i++) {
            Thread thread = Thread.ofVirtual().start(() -> {
                try {
                    answers.add(policy.rulesFor("https://example.com:443"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
        }
        assertThat(loading.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        for (Thread thread : threads) {
            thread.join(Duration.ofSeconds(10));
        }

        assertThat(loads).hasValue(1);
        assertThat(answers).hasSize(4).allSatisfy(rules -> assertThat(rules.isReachable()).isFalse());
    }

    @Test
    @DisplayName("a load that is interrupted is not cached, so the next caller loads again")
    void interruptedLoadIsNotCached() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        RobotsPolicy policy = new RobotsPolicy(origin -> {
            if (loads.incrementAndGet() == 1) {
                throw new InterruptedException("stopped");
            }
            return RobotsRules.ALLOW_ALL;
        });

        assertThatThrownBy(() -> policy.rulesFor("https://example.com:443"))
            .isInstanceOf(InterruptedException.class);
        assertThat(policy.rulesFor("https://example.com:443")).isSameAs(RobotsRules.ALLOW_ALL);
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("a disallowed URL is counted once however often it is refused")
    void countsDistinctUrls() {
        RobotsPolicy policy = new RobotsPolicy(origin -> RobotsRules.ALLOW_ALL);

        policy.recordDisallowed("https://example.com/a");
        policy.recordDisallowed("https://example.com/a");
        policy.recordDisallowed("https://example.com/b");

        assertThat(policy.disallowedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("an origin is scheme, host and port, with the default port made explicit")
    void origins() {
        assertThat(RobotsPolicy.originOf(URI.create("https://Example.COM/a?b")))
            .isEqualTo("https://example.com:443");
        assertThat(RobotsPolicy.originOf(URI.create("http://example.com/")))
            .isEqualTo("http://example.com:80");
        assertThat(RobotsPolicy.originOf(URI.create("HTTP://example.com:8080/x")))
            .isEqualTo("http://example.com:8080");
    }

    @Test
    @DisplayName("what a rule is matched against is the raw path and query")
    void pathAndQuery() {
        assertThat(RobotsPolicy.pathAndQuery(URI.create("https://example.com")))
            .isEqualTo("/");
        assertThat(RobotsPolicy.pathAndQuery(URI.create("https://example.com/a%20b?q=a%26b")))
            .isEqualTo("/a%20b?q=a%26b");
    }
}
