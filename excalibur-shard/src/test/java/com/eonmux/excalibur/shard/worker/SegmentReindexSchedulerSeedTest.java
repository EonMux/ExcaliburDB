package com.eonmux.excalibur.shard.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * When a source brought back at startup is next re-crawled.
 *
 * <p>Registering a restored source as though it had just been crawled restarted its interval at
 * every boot: a node bounced more often than the interval never re-crawled anything, and a first
 * crawl the restart cut short waited out a whole interval with half its documents indexed. A
 * restored source is registered with the time its last crawl ran to its end instead.</p>
 */
@DisplayName("Re-crawl schedule seeded from the last finished crawl")
class SegmentReindexSchedulerSeedTest {

    private static final long HOUR = Duration.ofHours(1).toMillis();

    private static final Duration STOP_WAIT = Duration.ofSeconds(10);

    @Test
    @DisplayName("the next re-crawl is one interval after the last crawl that finished")
    void nextRecrawlFollowsTheLastFinishedCrawl() {
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(sourceId -> true);
        Instant finished = Instant.now().minus(Duration.ofMinutes(20));

        scheduler.registerSource("docs", HOUR, finished);

        assertThat(scheduler.getNextReindexTime("docs")).isEqualTo(finished.plusMillis(HOUR));
    }

    @Test
    @DisplayName("a source whose last finished crawl is an interval old is due now")
    void anOverdueSourceIsDue() {
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(sourceId -> true);

        scheduler.registerSource("docs", HOUR, Instant.now().minus(Duration.ofHours(3)));

        assertThat(scheduler.getNextReindexTime("docs")).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("a source with no finished crawl on record is due now")
    void aSourceNeverCrawledToTheEndIsDue() {
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(sourceId -> true);
        Instant before = Instant.now();

        scheduler.registerSource("docs", HOUR, null);

        assertThat(scheduler.getNextReindexTime("docs"))
            .isBetween(before, Instant.now());
    }

    @Test
    @DisplayName("a finished time ahead of this node's clock is read as now, never later")
    void aFutureTimeIsClamped() {
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(sourceId -> true);
        Instant before = Instant.now();

        scheduler.registerSource("docs", HOUR, Instant.now().plus(Duration.ofDays(2)));

        assertThat(scheduler.getNextReindexTime("docs"))
            .as("a clock set back must not push the source out by more than one interval")
            .isBetween(before.plusMillis(HOUR), Instant.now().plusMillis(HOUR));
    }

    @Test
    @DisplayName("the interval is still validated")
    void theIntervalIsValidated() {
        SegmentReindexScheduler scheduler = new SegmentReindexScheduler(sourceId -> true);

        assertThatThrownBy(() -> scheduler.registerSource("docs", 1_000, Instant.now()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("due sources are re-crawled at the first check, one after another")
    void dueSourcesRunAtTheFirstCheckInTurn() throws InterruptedException {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch bothRan = new CountDownLatch(2);
        int[] running = {0};
        int[] mostAtOnce = {0};
        SegmentReindexScheduler[] holder = new SegmentReindexScheduler[1];
        holder[0] = new SegmentReindexScheduler(sourceId -> {
            synchronized (running) {
                running[0]++;
                mostAtOnce[0] = Math.max(mostAtOnce[0], running[0]);
            }
            order.add(sourceId);
            synchronized (running) {
                running[0]--;
            }
            holder[0].markReindexComplete(sourceId);
            bothRan.countDown();
            return true;
        });
        SegmentReindexScheduler scheduler = holder[0];
        scheduler.setCheckIntervalMsUnchecked(20);
        scheduler.registerSource("never-finished", HOUR, null);
        scheduler.registerSource("overdue", HOUR, Instant.now().minus(Duration.ofHours(2)));
        scheduler.registerSource("recent", HOUR, Instant.now().minus(Duration.ofMinutes(5)));

        scheduler.start();
        try {
            assertThat(bothRan.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            scheduler.stop(STOP_WAIT);
        }

        assertThat(order).containsExactlyInAnyOrder("never-finished", "overdue");
        assertThat(mostAtOnce[0]).isEqualTo(1);
        assertThat(scheduler.getNextReindexTime("never-finished"))
            .as("once re-crawled, the source waits out its interval like any other")
            .isAfter(Instant.now().plusMillis(HOUR - 60_000));
    }
}
