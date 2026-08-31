package io.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Split observers.
 *
 * <p>The engine publishes a split by renaming children and widening the routing table. Anything
 * that keys state by {@link Segment} identity — index structures, worker registrations — is left
 * pointing at a retired parent until it hears about it. Polling for that leaves a window in which
 * queries route to a child nothing has registered against, so the notification has to be an event.
 */
@DisplayName("StorageEngine split observers")
class SplitObserverTest {

    private static final int KEY_COUNT = 400;

    @TempDir
    Path tempDir;

    private Path dataDir;
    private StorageEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = tempDir.resolve("split-observer");
        Files.createDirectories(dataDir);
        engine = new StorageEngine(dataDir);
        engine.start();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    private record Split(Segment parent, Segment left, Segment right) {
    }

    @Test
    @DisplayName("an observer is told which parent split and into which children")
    void observerReceivesTheSplit() throws Exception {
        List<Split> seen = new CopyOnWriteArrayList<>();
        engine.addSplitObserver((parent, left, right) -> seen.add(new Split(parent, left, right)));

        Segment parent = splitSomething();

        assertThat(seen).hasSize(1);
        Split split = seen.get(0);
        assertThat(split.parent()).isSameAs(parent);
        assertThat(split.left()).isNotNull();
        assertThat(split.right()).isNotNull();
        assertThat(split.left()).isNotSameAs(split.right());
    }

    @Test
    @DisplayName("an observer sees the routing table already pointing at the children")
    void observerRunsAfterPublication() throws Exception {
        // Told too early, an observer would register index structures against children the engine
        // has not yet made reachable, and its own view would disagree with routing.
        AtomicInteger routingAtNotification = new AtomicInteger();
        engine.addSplitObserver((parent, left, right) ->
            routingAtNotification.set(engine.getSegmentCount()));

        int routingBefore = engine.getSegmentCount();
        splitSomething();

        assertThat(routingAtNotification.get())
            .as("the routing table is already widened when the observer runs")
            .isEqualTo(routingBefore * 2);
    }

    @Test
    @DisplayName("every observer is told, even when one of them throws")
    void oneFailingObserverDoesNotSilenceTheRest() throws Exception {
        // By notification time the split is durable and routing already points at the children.
        // Letting one observer's failure stop the loop would leave the others permanently unaware
        // of a split that did happen.
        List<String> notified = new CopyOnWriteArrayList<>();
        engine.addSplitObserver((parent, left, right) -> {
            notified.add("first");
            throw new IllegalStateException("observer failure");
        });
        engine.addSplitObserver((parent, left, right) -> notified.add("second"));

        splitSomething();

        assertThat(notified).containsExactly("first", "second");
    }

    @Test
    @DisplayName("a removed observer stops hearing about splits")
    void removedObserverIsSilent() throws Exception {
        List<String> notified = new ArrayList<>();
        SplitWorker.SplitListener observer = (parent, left, right) -> notified.add("called");

        engine.addSplitObserver(observer);
        assertThat(engine.removeSplitObserver(observer)).isTrue();

        splitSomething();

        assertThat(notified).isEmpty();
    }

    @Test
    @DisplayName("the parent is retired once the routing table no longer points at it")
    void parentIsClosedAfterTheSplit() throws Exception {
        // The parent used to be dropped rather than closed: its descriptor, its mapping and its
        // write log stayed open for the life of the process, and the left child's move unlinked
        // the parent's file, so the disk blocks behind it were held too. One of each per split.
        Segment parent = splitSomething();

        assertThat(parent.isClosed())
            .as("nothing can route to the parent any more, so it must have been retired")
            .isTrue();
    }

    @Test
    @DisplayName("observers still see an open parent, and the children outlive it")
    void observersRunBeforeTheParentIsRetired() throws Exception {
        // Retiring the parent before the observers run would hand them a closed segment to read
        // identity off. It happens after.
        List<Boolean> parentOpenAtNotification = new CopyOnWriteArrayList<>();
        List<Segment> children = new CopyOnWriteArrayList<>();
        engine.addSplitObserver((parent, left, right) -> {
            parentOpenAtNotification.add(!parent.isClosed());
            children.add(left);
            children.add(right);
        });

        splitSomething();

        assertThat(parentOpenAtNotification).containsExactly(true);
        assertThat(children).hasSize(2).allSatisfy(child ->
            assertThat(child.isClosed())
                .as("a child is live and must not be retired with its parent")
                .isFalse());
    }

    @Test
    @DisplayName("keys survive the parent's retirement")
    void dataSurvivesRetirement() throws Exception {
        // The point of the whole exercise: closing the parent must release its handles without
        // taking any data with it. Both children were built from it before it went.
        splitSomething();

        for (int i = 0; i < KEY_COUNT; i++) {
            assertThat(engine.get(("key-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .as("key-%s after the split", i)
                .isNotNull();
        }
    }

    @Test
    @DisplayName("a null observer is refused")
    void nullObserverRefused() {
        assertThatThrownBy(() -> engine.addSplitObserver(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** Fills a segment and splits it, returning the parent. */
    private Segment splitSomething() throws Exception {
        for (int i = 0; i < KEY_COUNT; i++) {
            engine.put(("key-" + i).getBytes(), ("value-" + i).getBytes());
        }
        int routingBefore = engine.getSegmentCount();
        Segment parent = findPopulatedSegment(routingBefore);
        engine.getSplitWorker().requestSplit(
            parent, Integer.numberOfTrailingZeros(routingBefore));
        awaitSplit(parent);
        return parent;
    }

    private Segment findPopulatedSegment(int routingCount) {
        for (int i = 0; i < routingCount; i++) {
            Segment segment = engine.getSegment(i);
            if (segment != null && segment.entryCount() > 0) {
                return segment;
            }
        }
        throw new AssertionError("no populated segment to split");
    }

    private void awaitSplit(Segment parent) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (!parent.isSplitComplete()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("split did not complete within the timeout");
            }
            Thread.sleep(10);
        }
    }
}
