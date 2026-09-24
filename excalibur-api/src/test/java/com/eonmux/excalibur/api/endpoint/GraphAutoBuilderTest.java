package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.bootstrap.GraphBuildService;
import com.eonmux.excalibur.api.bootstrap.GraphExtraction;
import com.eonmux.excalibur.api.bootstrap.GraphFreshness;
import com.eonmux.excalibur.config.GraphConfig;
import com.eonmux.excalibur.graph.build.BuildCancelledException;
import com.eonmux.excalibur.graph.staging.PublishRefusedException;
import com.eonmux.excalibur.tenancy.NamespaceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * When the node rebuilds a namespace's graph by itself, and when it does not.
 *
 * <p>The scheduling alone, against namespaces that report their freshness and count their
 * builds: which graph is behind is the staging store's to say, and what a build does is the
 * build service's. What is decided here is whether to build now - and a build too many costs a
 * whole graph's embeddings, while a build too few serves withdrawn documents' facts.</p>
 */
@DisplayName("Automatic graph builds")
class GraphAutoBuilderTest {

    private static final Instant T0 = Instant.parse("2026-05-01T10:00:00Z");
    private static final NamespaceId ACME = NamespaceId.of("acme");
    private static final NamespaceId BETA = NamespaceId.of("beta");
    private static final NamespaceId GAMMA = NamespaceId.of("gamma");
    private static final GraphBuildService.BuildOutcome PUBLISHED =
        new GraphBuildService.BuildOutcome(true, false, 3, 2, 1, 2, 0, 3, 0);

    private final MutableClock clock = new MutableClock(T0);
    private final FakeNamespaces namespaces = new FakeNamespaces();
    private GraphAutoBuilder builder;

    @AfterEach
    void stop() {
        if (builder != null) {
            assertThat(builder.drain(Duration.ofSeconds(10))).isTrue();
        }
    }

    private static GraphAutoBuilder.Settings settings(boolean enabled, int maxConcurrent) {
        return new GraphAutoBuilder.Settings(enabled, Duration.ofSeconds(30),
            Duration.ofSeconds(60), Duration.ofSeconds(600), maxConcurrent,
            Duration.ofSeconds(60), Duration.ofSeconds(240));
    }

    private GraphAutoBuilder builder(GraphAutoBuilder.Settings settings,
                                     Optional<GraphExtraction> extraction,
                                     Predicate<NamespaceId> explicitRunning) {
        builder = new GraphAutoBuilder(settings, namespaces, () -> extraction, explicitRunning,
            clock);
        return builder;
    }

    private GraphAutoBuilder builder() {
        return builder(settings(true, 1), Optional.of(GraphExtraction.ACTIVE), id -> false);
    }

    /** Behind since {@code since}, last changed at {@code last}. */
    private static GraphFreshness behind(Instant since, Instant last) {
        return GraphFreshness.behind(Optional.of(since), Optional.of(last));
    }

    /** Waits for the namespace's build to finish and returns what is remembered about it. */
    private GraphAutoBuilder.Memory awaitFinished(NamespaceId id) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        GraphAutoBuilder.Memory memory = builder.status(id).memory();
        while ((memory.running() || memory.lastResult() == null)
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
            memory = builder.status(id).memory();
        }
        assertThat(memory.running()).as("the build finished").isFalse();
        return memory;
    }

    @Test
    @DisplayName("nothing is built when no graph is behind its staging")
    void nothingChanged() {
        namespaces.put(ACME, GraphFreshness.CURRENT);

        assertThat(builder().runPass()).isEmpty();

        assertThat(namespaces.builds).isEmpty();
        assertThat(builder.status(ACME).state()).isEqualTo(GraphAutoBuilder.STATE_CURRENT);
    }

    @Test
    @DisplayName("a graph behind its staging is rebuilt once staging has been quiet")
    void rebuiltAfterTheDebounce() throws InterruptedException {
        namespaces.put(ACME, behind(T0, T0));
        builder();

        clock.advance(Duration.ofSeconds(30));
        assertThat(builder.runPass()).as("staging changed half a debounce ago").isEmpty();
        assertThat(builder.status(ACME).state()).isEqualTo(GraphAutoBuilder.STATE_PENDING);

        clock.advance(Duration.ofSeconds(30));
        assertThat(builder.runPass()).containsExactly(ACME);
        GraphAutoBuilder.Memory memory = awaitFinished(ACME);

        assertThat(namespaces.builds).containsExactly(ACME);
        assertThat(memory.lastResult()).isEqualTo(GraphAutoBuilder.RESULT_COMPLETED);
        assertThat(memory.lastOutcome()).isEqualTo(PUBLISHED);
        assertThat(memory.lastStartedAtMs()).isEqualTo(T0.plusSeconds(60).toEpochMilli());
        assertThat(builder.status(ACME).state()).isEqualTo(GraphAutoBuilder.STATE_CURRENT);
        assertThat(builder.counts().completed()).isOne();

        assertThat(builder.runPass()).as("the build brought it up to date").isEmpty();
    }

    @Test
    @DisplayName("staging that never goes quiet is still published after the maximum delay")
    void maximumDelay() throws InterruptedException {
        builder();
        clock.advance(Duration.ofSeconds(600));
        // Changed a moment ago, and changing since the pass started watching it.
        namespaces.put(ACME, behind(T0, clock.instant().minusSeconds(1)));

        assertThat(builder.runPass()).containsExactly(ACME);
        awaitFinished(ACME);
        assertThat(namespaces.builds).containsExactly(ACME);
    }

    @Test
    @DisplayName("a namespace with facts staged and no graph is built without waiting")
    void neverBuilt() throws InterruptedException {
        namespaces.put(ACME, GraphFreshness.behind(Optional.empty(), Optional.empty()));

        assertThat(builder().runPass()).containsExactly(ACME);
        awaitFinished(ACME);
    }

    @Test
    @DisplayName("the oldest unpublished change is built first when slots are short")
    void oldestFirst() throws InterruptedException {
        builder();
        clock.advance(Duration.ofSeconds(120));
        namespaces.put(ACME, behind(T0.plusSeconds(30), T0.plusSeconds(30)));
        namespaces.put(BETA, behind(T0, T0));
        // Held running, so its slot is still taken when the pass looks at the next namespace.
        namespaces.gate = new CountDownLatch(1);

        assertThat(builder.runPass()).containsExactly(BETA);
        namespaces.gate.countDown();
        awaitFinished(BETA);
        assertThat(builder.runPass()).containsExactly(ACME);
        awaitFinished(ACME);
    }

    @Test
    @DisplayName("disabled, nothing is scheduled and a pass builds nothing")
    void disabled() {
        namespaces.put(ACME, behind(T0, T0));
        builder(settings(false, 1), Optional.of(GraphExtraction.ACTIVE), id -> false).start();
        clock.advance(Duration.ofHours(1));

        assertThat(builder.runPass()).isEmpty();
        assertThat(namespaces.builds).isEmpty();
        GraphAutoBuilder.Status status = builder.status(ACME);
        assertThat(status.enabled()).isFalse();
        assertThat(status.state()).isEqualTo(GraphAutoBuilder.STATE_DISABLED);
        assertThat(status.freshness().behind()).as("freshness is still reported").isTrue();
    }

    @Test
    @DisplayName("configured from the graph section")
    void fromConfig() {
        GraphAutoBuilder.Settings settings = GraphAutoBuilder.Settings.from(new GraphConfig(
            Map.of("excalibur.graph.auto-build-debounce-seconds", "5",
                "excalibur.graph.auto-build-max-concurrent", "2")));

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.debounce()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.maxConcurrent()).isEqualTo(2);
        assertThat(settings.interval()).isEqualTo(
            Duration.ofSeconds(GraphConfig.DEFAULT_AUTO_BUILD_INTERVAL_SECONDS));
        assertThatThrownBy(() -> new GraphAutoBuilder.Settings(true, Duration.ZERO,
            Duration.ZERO, Duration.ofSeconds(1), 1, Duration.ofSeconds(1),
            Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("Failures")
    class Failures {

        @Test
        @DisplayName("a failed build waits out a doubling backoff before the next attempt")
        void backoff() throws InterruptedException {
            namespaces.put(ACME, behind(T0, T0));
            namespaces.failWith = new IOException("embedding endpoint unavailable");
            builder();
            clock.advance(Duration.ofSeconds(60));

            assertThat(builder.runPass()).containsExactly(ACME);
            GraphAutoBuilder.Memory first = awaitFinished(ACME);
            assertThat(first.lastResult()).isEqualTo(GraphAutoBuilder.RESULT_FAILED);
            assertThat(first.lastError()).contains("embedding endpoint unavailable");
            assertThat(first.consecutiveFailures()).isOne();
            assertThat(first.retryAtMs()).isEqualTo(clock.millis() + 60_000);
            assertThat(builder.status(ACME).state())
                .isEqualTo(GraphAutoBuilder.STATE_BACKING_OFF);

            clock.advance(Duration.ofSeconds(59));
            assertThat(builder.runPass()).as("inside the backoff").isEmpty();

            clock.advance(Duration.ofSeconds(1));
            assertThat(builder.runPass()).containsExactly(ACME);
            GraphAutoBuilder.Memory second = awaitFinished(ACME);
            assertThat(second.consecutiveFailures()).isEqualTo(2);
            assertThat(second.retryAtMs()).as("doubled").isEqualTo(clock.millis() + 120_000);

            namespaces.failWith = null;
            clock.advance(Duration.ofSeconds(120));
            assertThat(builder.runPass()).containsExactly(ACME);
            GraphAutoBuilder.Memory recovered = awaitFinished(ACME);
            assertThat(recovered.lastResult()).isEqualTo(GraphAutoBuilder.RESULT_COMPLETED);
            assertThat(recovered.consecutiveFailures()).isZero();
            assertThat(recovered.lastError()).isNull();
            assertThat(builder.counts().failed()).isEqualTo(2);
        }

        @Test
        @DisplayName("the backoff doubles up to its ceiling and no further")
        void backoffCeiling() {
            builder();
            assertThat(builder.backoff(1)).isEqualTo(Duration.ofSeconds(60));
            assertThat(builder.backoff(2)).isEqualTo(Duration.ofSeconds(120));
            assertThat(builder.backoff(3)).isEqualTo(Duration.ofSeconds(240));
            assertThat(builder.backoff(40)).isEqualTo(Duration.ofSeconds(240));
        }

        @Test
        @DisplayName("one namespace failing does not hold back another")
        void failuresAreIsolated() throws InterruptedException {
            namespaces.put(ACME, behind(T0, T0));
            namespaces.put(BETA, behind(T0.plusSeconds(1), T0.plusSeconds(1)));
            namespaces.failFor = ACME;
            builder(settings(true, 2), Optional.of(GraphExtraction.ACTIVE), id -> false);
            clock.advance(Duration.ofSeconds(120));

            assertThat(builder.runPass()).containsExactly(ACME, BETA);
            assertThat(awaitFinished(ACME).lastResult())
                .isEqualTo(GraphAutoBuilder.RESULT_FAILED);
            assertThat(awaitFinished(BETA).lastResult())
                .isEqualTo(GraphAutoBuilder.RESULT_COMPLETED);
        }

        @Test
        @DisplayName("an unreadable namespace is skipped and looked at again")
        void unreadableFreshness() {
            namespaces.put(ACME, behind(T0, T0));
            namespaces.unreadable = ACME;
            builder();
            clock.advance(Duration.ofSeconds(120));

            assertThat(builder.runPass()).isEmpty();
            GraphAutoBuilder.Status status = builder.status(ACME);
            assertThat(status.freshness()).isNull();
            assertThat(status.freshnessError()).contains("unreadable");
        }

        @Test
        @DisplayName("a namespace list that cannot be read builds nothing")
        void unlistable() {
            namespaces.put(ACME, behind(T0, T0));
            namespaces.listFails = true;
            builder();
            clock.advance(Duration.ofSeconds(120));

            assertThat(builder.runPass()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Builds an erasure overtook")
    class Superseded {

        @Test
        @DisplayName("a refused publish is superseded, not failed, and is retried at once")
        void retriedAtOnce() throws InterruptedException {
            namespaces.put(ACME, behind(T0, T0));
            namespaces.refusals.set(1);
            builder();
            clock.advance(Duration.ofSeconds(60));

            assertThat(builder.runPass()).containsExactly(ACME);
            GraphAutoBuilder.Memory refused = awaitFinished(ACME);
            assertThat(refused.lastResult()).isEqualTo(GraphAutoBuilder.RESULT_SUPERSEDED);
            assertThat(refused.lastError()).as("nothing failed").isNull();
            assertThat(refused.consecutiveFailures()).isZero();
            assertThat(refused.consecutiveSupersessions()).isOne();
            assertThat(refused.retryAtMs()).as("no backoff").isZero();
            assertThat(builder.status(ACME).state()).isEqualTo(GraphAutoBuilder.STATE_PENDING);
            assertThat(builder.counts().superseded()).isOne();
            assertThat(builder.counts().failed()).isZero();

            assertThat(builder.runPass()).as("due again on the very next pass")
                .containsExactly(ACME);
            GraphAutoBuilder.Memory rebuilt = awaitFinished(ACME);
            assertThat(rebuilt.lastResult()).isEqualTo(GraphAutoBuilder.RESULT_COMPLETED);
            assertThat(rebuilt.consecutiveSupersessions()).isZero();
            assertThat(namespaces.builds).containsExactly(ACME);
        }

        @Test
        @DisplayName("a refused publish keeps the debounce: busy staging still waits to be quiet")
        void keepsTheDebounce() throws InterruptedException {
            namespaces.put(ACME, behind(T0, T0));
            namespaces.refusals.set(1);
            builder();
            clock.advance(Duration.ofSeconds(60));
            assertThat(builder.runPass()).containsExactly(ACME);
            awaitFinished(ACME);

            // The erasure that overtook the build staged a change a moment ago.
            namespaces.put(ACME, behind(T0, clock.instant()));
            assertThat(builder.runPass()).as("staging is not quiet yet").isEmpty();

            clock.advance(Duration.ofSeconds(60));
            assertThat(builder.runPass()).containsExactly(ACME);
            assertThat(awaitFinished(ACME).lastResult())
                .isEqualTo(GraphAutoBuilder.RESULT_COMPLETED);
        }

        @Test
        @DisplayName("refused again and again, the retries space out up to the maximum delay")
        void escalates() throws InterruptedException {
            namespaces.put(ACME, behind(T0, T0));
            namespaces.refusals.set(Integer.MAX_VALUE);
            builder();
            clock.advance(Duration.ofSeconds(600));

            assertThat(builder.runPass()).containsExactly(ACME);
            assertThat(awaitFinished(ACME).retryAtMs()).as("the first is retried at once")
                .isZero();

            assertThat(builder.runPass()).containsExactly(ACME);
            GraphAutoBuilder.Memory second = awaitFinished(ACME);
            assertThat(second.consecutiveSupersessions()).isEqualTo(2);
            assertThat(second.consecutiveFailures()).isZero();
            assertThat(second.retryAtMs()).isEqualTo(clock.millis() + 60_000);
            assertThat(builder.status(ACME).state())
                .isEqualTo(GraphAutoBuilder.STATE_BACKING_OFF);
            assertThat(builder.runPass()).as("overdue, but spaced out").isEmpty();

            clock.advance(Duration.ofSeconds(60));
            assertThat(builder.runPass()).containsExactly(ACME);
            assertThat(awaitFinished(ACME).retryAtMs()).isEqualTo(clock.millis() + 120_000);
            assertThat(builder.counts().superseded()).isEqualTo(3);
            assertThat(builder.counts().failed()).isZero();
        }

        @Test
        @DisplayName("the spacing starts at the debounce and stops at the maximum delay")
        void escalationCeiling() {
            builder();
            assertThat(builder.supersededWait(1)).isEqualTo(Duration.ZERO);
            assertThat(builder.supersededWait(2)).isEqualTo(Duration.ofSeconds(60));
            assertThat(builder.supersededWait(3)).isEqualTo(Duration.ofSeconds(120));
            assertThat(builder.supersededWait(6)).isEqualTo(Duration.ofSeconds(600));
            assertThat(builder.supersededWait(60)).isEqualTo(Duration.ofSeconds(600));
        }

        @Test
        @DisplayName("with no debounce the spacing starts at the pass interval")
        void escalationWithoutDebounce() {
            builder(new GraphAutoBuilder.Settings(true, Duration.ofSeconds(30), Duration.ZERO,
                Duration.ofSeconds(600), 1, Duration.ofSeconds(60), Duration.ofSeconds(240)),
                Optional.of(GraphExtraction.ACTIVE), id -> false);
            assertThat(builder.supersededWait(2)).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("a refusal wrapped by the build still reads as superseded")
        void wrappedRefusal() throws InterruptedException {
            namespaces.put(ACME, behind(T0, T0));
            namespaces.refusals.set(1);
            namespaces.wrapRefusal = true;
            builder();
            clock.advance(Duration.ofSeconds(60));

            assertThat(builder.runPass()).containsExactly(ACME);
            assertThat(awaitFinished(ACME).lastResult())
                .isEqualTo(GraphAutoBuilder.RESULT_SUPERSEDED);
        }

        @Test
        @DisplayName("a build that reached its publish ends the failure streak")
        void endsAFailureStreak() throws InterruptedException {
            namespaces.put(ACME, behind(T0, T0));
            namespaces.failWith = new IOException("embedding endpoint unavailable");
            builder();
            clock.advance(Duration.ofSeconds(60));
            assertThat(builder.runPass()).containsExactly(ACME);
            assertThat(awaitFinished(ACME).consecutiveFailures()).isOne();

            namespaces.failWith = null;
            namespaces.refusals.set(1);
            clock.advance(Duration.ofSeconds(60));
            assertThat(builder.runPass()).containsExactly(ACME);
            GraphAutoBuilder.Memory refused = awaitFinished(ACME);

            assertThat(refused.lastResult()).isEqualTo(GraphAutoBuilder.RESULT_SUPERSEDED);
            assertThat(refused.consecutiveFailures()).isZero();
            assertThat(refused.retryAtMs()).isZero();
        }
    }

    @Nested
    @DisplayName("What holds a build back")
    class HeldBack {

        @Test
        @DisplayName("extraction without an API key pauses automatic builds, and says so")
        void pausedWithoutExtraction() {
            namespaces.put(ACME, behind(T0, T0));
            builder(settings(true, 1), Optional.of(GraphExtraction.MISSING_API_KEY),
                id -> false);
            clock.advance(Duration.ofSeconds(120));

            assertThat(builder.runPass()).isEmpty();
            assertThat(namespaces.builds).isEmpty();
            GraphAutoBuilder.Status status = builder.status(ACME);
            assertThat(status.state()).isEqualTo(GraphAutoBuilder.STATE_PAUSED);
            assertThat(status.pausedBy()).contains(GraphExtraction.MISSING_API_KEY);
        }

        @Test
        @DisplayName("extraction turned off on purpose does not pause them")
        void extractionDisabledStillBuilds() throws InterruptedException {
            namespaces.put(ACME, behind(T0, T0));
            builder(settings(true, 1), Optional.of(GraphExtraction.DISABLED), id -> false);
            clock.advance(Duration.ofSeconds(120));

            assertThat(builder.runPass()).containsExactly(ACME);
            awaitFinished(ACME);
        }

        @Test
        @DisplayName("a namespace an explicit build is running for is left to it")
        void explicitBuildRunning() {
            namespaces.put(ACME, behind(T0, T0));
            builder(settings(true, 1), Optional.of(GraphExtraction.ACTIVE), ACME::equals);
            clock.advance(Duration.ofSeconds(120));

            assertThat(builder.runPass()).isEmpty();
        }

        @Test
        @DisplayName("a graph withheld for an interrupted erasure waits for the erasure")
        void withheld() {
            namespaces.put(ACME, behind(T0, T0).withheld(true));
            builder();
            clock.advance(Duration.ofSeconds(120));

            assertThat(builder.runPass()).isEmpty();
            assertThat(builder.status(ACME).state()).isEqualTo(GraphAutoBuilder.STATE_WITHHELD);
        }

        @Test
        @DisplayName("a graph an explicit build brought up to date is not built again")
        void currentOnceLocked() throws InterruptedException {
            namespaces.put(ACME, behind(T0, T0));
            namespaces.currentOnceLocked = true;
            builder();
            clock.advance(Duration.ofSeconds(120));

            assertThat(builder.runPass()).containsExactly(ACME);

            assertThat(awaitFinished(ACME).lastResult())
                .isEqualTo(GraphAutoBuilder.RESULT_UNCHANGED);
            assertThat(builder.counts().unchanged()).isOne();
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        @Test
        @DisplayName("at most max-concurrent builds run, and one namespace is never built twice")
        void bounded() throws InterruptedException {
            namespaces.put(ACME, behind(T0, T0));
            namespaces.put(BETA, behind(T0.plusSeconds(1), T0.plusSeconds(1)));
            namespaces.put(GAMMA, behind(T0.plusSeconds(2), T0.plusSeconds(2)));
            namespaces.gate = new CountDownLatch(1);
            builder(settings(true, 2), Optional.of(GraphExtraction.ACTIVE), id -> false);
            clock.advance(Duration.ofSeconds(120));

            assertThat(builder.runPass()).containsExactly(ACME, BETA);
            assertThat(builder.runPass()).as("both slots are taken").isEmpty();
            assertThat(builder.counts().running()).isEqualTo(2);
            assertThat(builder.status(ACME).state()).isEqualTo(GraphAutoBuilder.STATE_RUNNING);

            namespaces.gate.countDown();
            awaitFinished(ACME);
            awaitFinished(BETA);
            assertThat(builder.runPass()).containsExactly(GAMMA);
            awaitFinished(GAMMA);
            assertThat(namespaces.builds).containsExactlyInAnyOrder(ACME, BETA, GAMMA);
        }

        @Test
        @DisplayName("draining asks a running build to stop, and records that it did")
        void drainCancels() throws InterruptedException {
            namespaces.put(ACME, behind(T0, T0));
            namespaces.gate = new CountDownLatch(1);
            namespaces.stopOnCancel = true;
            builder();
            clock.advance(Duration.ofSeconds(120));
            assertThat(builder.runPass()).containsExactly(ACME);
            namespaces.entered.await(10, TimeUnit.SECONDS);

            assertThat(builder.drain(Duration.ofSeconds(10))).isTrue();

            assertThat(builder.status(ACME).memory().lastResult())
                .isEqualTo(GraphAutoBuilder.RESULT_CANCELLED);
            assertThat(builder.counts().cancelled()).isOne();
            assertThat(builder.runPass()).as("a drained builder starts nothing").isEmpty();
        }
    }

    /** Namespaces that report a set freshness and record the builds asked of them. */
    private static final class FakeNamespaces implements GraphAutoBuilder.Namespaces {

        private final Map<NamespaceId, GraphFreshness> freshness = new ConcurrentHashMap<>();
        final List<NamespaceId> builds = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch entered = new CountDownLatch(1);
        volatile IOException failWith;
        volatile NamespaceId failFor;
        volatile NamespaceId unreadable;
        volatile boolean listFails;
        volatile boolean currentOnceLocked;
        volatile boolean stopOnCancel;
        volatile CountDownLatch gate;
        final AtomicInteger refusals = new AtomicInteger();
        volatile boolean wrapRefusal;

        void put(NamespaceId id, GraphFreshness value) {
            freshness.put(id, value);
        }

        @Override
        public List<NamespaceId> list() throws IOException {
            if (listFails) {
                throw new IOException("the graph root cannot be listed");
            }
            return freshness.keySet().stream().sorted().toList();
        }

        @Override
        public GraphFreshness freshness(NamespaceId id) throws IOException {
            if (id.equals(unreadable)) {
                throw new IOException("staging log unreadable");
            }
            return freshness.getOrDefault(id, GraphFreshness.CURRENT);
        }

        @Override
        public Optional<GraphBuildService.BuildOutcome> buildIfBehind(
                NamespaceId id, BooleanSupplier cancelRequested) throws IOException {
            entered.countDown();
            if (stopOnCancel) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!cancelRequested.getAsBoolean() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                throw new BuildCancelledException("asked to stop");
            }
            CountDownLatch waitFor = gate;
            if (waitFor != null) {
                try {
                    waitFor.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (currentOnceLocked) {
                return Optional.empty();
            }
            if (refusals.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                PublishRefusedException refused = new PublishRefusedException(
                    "erasure request(s) 7 were recorded while the graph was being built");
                if (wrapRefusal) {
                    throw new java.io.UncheckedIOException(refused);
                }
                throw refused;
            }
            if (failWith != null || id.equals(failFor)) {
                throw failWith != null ? failWith : new IOException("build of " + id + " failed");
            }
            builds.add(id);
            freshness.put(id, GraphFreshness.CURRENT);
            return Optional.of(PUBLISHED);
        }
    }

    /** A clock the test moves. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
