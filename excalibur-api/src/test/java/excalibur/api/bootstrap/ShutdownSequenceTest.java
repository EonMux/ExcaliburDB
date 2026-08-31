package excalibur.api.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the ordered shutdown sequence.
 *
 * <p>Order is the point: the arena must not be closed until every reader has drained,
 * because a live mapping over a closed arena crashes the JVM rather than throwing.</p>
 */
@DisplayName("ShutdownSequence")
class ShutdownSequenceTest {

    @Test
    @DisplayName("runs steps in the order they were added")
    void runsInOrder() {
        List<String> order = new ArrayList<>();

        ShutdownSequence sequence = new ShutdownSequence()
            .add("stop accepting", () -> order.add("stop accepting"))
            .add("drain workers", () -> order.add("drain workers"))
            .add("close storage", () -> order.add("close storage"))
            .add("close arena", () -> order.add("close arena"));

        List<String> completed = sequence.run();

        assertThat(order).containsExactly("stop accepting", "drain workers", "close storage", "close arena");
        assertThat(completed).isEqualTo(order);
    }

    @Test
    @DisplayName("still closes the arena when an earlier step throws")
    void continuesAfterFailure() {
        List<String> order = new ArrayList<>();

        List<String> completed = new ShutdownSequence()
            .add("stop accepting", () -> order.add("stop accepting"))
            .add("drain workers", () -> {
                throw new IllegalStateException("worker refused to stop");
            })
            .add("close storage", () -> order.add("close storage"))
            .add("close arena", () -> order.add("close arena"))
            .run();

        assertThat(order).containsExactly("stop accepting", "close storage", "close arena");
        assertThat(completed).containsExactly("stop accepting", "close storage", "close arena");
    }

    @Test
    @DisplayName("skips a step whose target is absent")
    void skipsAbsentTarget() {
        List<String> order = new ArrayList<>();

        new ShutdownSequence()
            .addIfPresent("close arena", null, () -> order.add("close arena"))
            .addIfPresent("close storage", "engine", () -> order.add("close storage"))
            .run();

        assertThat(order).containsExactly("close storage");
    }

    @Test
    @DisplayName("runs only once")
    void runsOnce() {
        List<String> order = new ArrayList<>();
        ShutdownSequence sequence = new ShutdownSequence()
            .add("close storage", () -> order.add("close storage"));

        sequence.run();
        sequence.run();

        assertThat(order).containsExactly("close storage");
    }

    @Test
    @DisplayName("reports its step names before running")
    void reportsStepNames() {
        ShutdownSequence sequence = new ShutdownSequence()
            .add("a", () -> { })
            .add("b", () -> { });

        assertThat(sequence.stepNames()).containsExactly("a", "b");
    }
}
