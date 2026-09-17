package io.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That {@code resetToBootstrapping} transitions under the same monitor its siblings do.
 *
 * <p>Every transition on {@link SegmentCatalog.SegmentMetadata} is a check-then-act: it
 * validates the move out of the current lifecycle state and then assigns the next one. The
 * pair is only atomic while {@code stateLock} is held, and {@code resetToBootstrapping} was
 * the one transition that did neither under it.</p>
 *
 * <p>The interleaving itself - two threads validating against the same stale state - is not
 * something a test can provoke on demand. What is checkable is the property that rules it
 * out: while the monitor is held elsewhere, a transition that takes it cannot make progress.
 * Holding {@code stateLock} from the test and watching the transition block is therefore
 * deterministic in both directions - an implementation that skips the lock runs straight
 * through, and one that takes it can never complete while the test owns the monitor.</p>
 */
@DisplayName("SegmentMetadata.resetToBootstrapping locking")
class SegmentMetadataResetLockingTest {

    /** How long a correctly locked reset is watched for before it is declared blocked. */
    private static final long BLOCKED_OBSERVATION_MS = 500;

    @Test
    @DisplayName("validates and assigns under stateLock, like every other transition")
    void resetToBootstrappingHoldsTheStateLock() throws Exception {
        SegmentCatalog.SegmentMetadata metadata = purgedMetadata();
        Object stateLock = stateLockOf(metadata);

        CountDownLatch started = new CountDownLatch(1);
        Thread resetter = new Thread(() -> {
            started.countDown();
            metadata.resetToBootstrapping();
        }, "reset-to-bootstrapping");

        synchronized (stateLock) {
            resetter.start();
            assertThat(started.await(10, TimeUnit.SECONDS))
                .as("the resetting thread reached the transition")
                .isTrue();
            resetter.join(BLOCKED_OBSERVATION_MS);

            assertThat(resetter.isAlive())
                .as("resetToBootstrapping must serialise on stateLock like its siblings, so it "
                    + "cannot get through while this test holds the monitor")
                .isTrue();
            assertThat(metadata.getLifecycleState())
                .as("nothing may be assigned while the monitor is held elsewhere")
                .isEqualTo(LifecycleState.PURGED);
        }

        resetter.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(resetter.isAlive())
            .as("the reset completes once the monitor is released")
            .isFalse();
        assertThat(metadata.getLifecycleState()).isEqualTo(LifecycleState.BOOTSTRAPPING);
    }

    @Test
    @DisplayName("a reset the transition rules refuse leaves the state untouched")
    void refusedResetLeavesTheStateUntouched() {
        SegmentCatalog.SegmentMetadata metadata = new SegmentCatalog.SegmentMetadata(7L);
        metadata.transitionToIndexing();
        metadata.transitionToActive();

        assertThatThrownBy(metadata::resetToBootstrapping)
            .isInstanceOf(IllegalStateException.class);

        assertThat(metadata.getLifecycleState())
            .as("a refused transition is not a half-applied one")
            .isEqualTo(LifecycleState.ACTIVE);
    }

    @Test
    @DisplayName("a reset from PURGED is accepted")
    void resetFromPurgedIsAccepted() {
        SegmentCatalog.SegmentMetadata metadata = purgedMetadata();

        metadata.resetToBootstrapping();

        assertThat(metadata.getLifecycleState()).isEqualTo(LifecycleState.BOOTSTRAPPING);
    }

    /**
     * Walks a fresh metadata record to PURGED, the one state a reset is legal from.
     *
     * @return The metadata, in PURGED
     */
    private static SegmentCatalog.SegmentMetadata purgedMetadata() {
        SegmentCatalog.SegmentMetadata metadata = new SegmentCatalog.SegmentMetadata(1L);
        metadata.transitionToIndexing();
        metadata.transitionToActive();
        metadata.transitionToTombstoned();
        metadata.transitionToPurged();
        return metadata;
    }

    /**
     * Reaches the monitor the transitions are meant to serialise on.
     *
     * @param metadata The record whose monitor is wanted
     * @return The monitor object
     * @throws ReflectiveOperationException if the field is not where it is expected
     */
    private static Object stateLockOf(SegmentCatalog.SegmentMetadata metadata)
            throws ReflectiveOperationException {
        Field field = SegmentCatalog.SegmentMetadata.class.getDeclaredField("stateLock");
        field.setAccessible(true);
        return field.get(metadata);
    }
}
