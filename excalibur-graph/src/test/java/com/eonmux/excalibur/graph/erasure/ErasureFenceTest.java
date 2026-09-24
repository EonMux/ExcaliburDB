package com.eonmux.excalibur.graph.erasure;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.PublishFence;
import com.eonmux.excalibur.graph.staging.PublishRefusedException;
import com.eonmux.excalibur.graph.staging.StagedErasure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What an erasure log owes, fenced around a build.
 *
 * <p>A build reads staging, and staging still holds everything an outstanding obligation covers
 * until a compaction erases it. These are the terms on which a build may publish anyway: it
 * leaves out what is owed as of when it started, and its publish is refused if anything was
 * owed afterwards.</p>
 */
@DisplayName("Erasure fence")
class ErasureFenceTest {

    private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

    private static Provenance document(long id) {
        return Provenance.of(SourceType.DOCUMENT, id, 0.9, 1);
    }

    @Test
    @DisplayName("withholds exactly what the outstanding requests cover")
    void withholdsWhatIsOwed(@TempDir Path dir) throws IOException {
        try (ErasureLog log = ErasureLog.open(dir.resolve("erasure.log"))) {
            log.submit(new ErasureScope.Entity(3), WHEN);
            log.submit(new ErasureScope.Source(SourceType.DOCUMENT, 9), WHEN);

            PublishFence fence = log.fence();
            StagedErasure withheld = fence.withheld();

            assertThat(fence.obligations()).isEqualTo(2);
            assertThat(withheld.erasesFact(3, 0, 4, 0, 0, document(1)))
                .as("a fact about the erased entity").isTrue();
            assertThat(withheld.erasesFact(4, 0, 3, 0, 0, document(1)))
                .as("in either direction").isTrue();
            assertThat(withheld.erasesFact(4, 0, 5, 0, 0, document(9)))
                .as("a fact from the withdrawn document").isTrue();
            assertThat(withheld.erasesFact(4, 0, 5, 0, 0, document(1)))
                .as("anything else").isFalse();
            assertThat(withheld.erasesLabel(3)).isTrue();
            assertThat(withheld.erasesLabel(4))
                .as("a withdrawn document does not take the names it mentioned").isFalse();
            assertThat(withheld.erasesSnippet(4, document(9))).isTrue();
            assertThat(withheld.erasesSnippet(4, document(1))).isFalse();
        }
    }

    @Test
    @DisplayName("withholds prose of unrecorded origin while a request selects by provenance")
    void withholdsProseItCannotClear(@TempDir Path dir) throws IOException {
        try (ErasureLog log = ErasureLog.open(dir.resolve("erasure.log"))) {
            log.submit(new ErasureScope.Source(SourceType.DOCUMENT, 9), WHEN);

            assertThat(log.fence().withheld().erasesSnippet(4, Provenance.UNKNOWN))
                .as("it may have come from the withdrawn document, and nothing can say it did not")
                .isTrue();
        }
    }

    @Test
    @DisplayName("keeps prose of unrecorded origin when only entities are owed")
    void keepsProseAnEntityScopeCanJudge(@TempDir Path dir) throws IOException {
        try (ErasureLog log = ErasureLog.open(dir.resolve("erasure.log"))) {
            log.submit(new ErasureScope.Entity(3), WHEN);

            StagedErasure withheld = log.fence().withheld();
            assertThat(withheld.erasesSnippet(4, Provenance.UNKNOWN)).isFalse();
            assertThat(withheld.erasesSnippet(3, Provenance.UNKNOWN))
                .as("the erased entity's own prose, whatever its origin").isTrue();
        }
    }

    @Test
    @DisplayName("a log that owes nothing withholds nothing")
    void nothingOwedWithholdsNothing(@TempDir Path dir) throws IOException {
        try (ErasureLog log = ErasureLog.open(dir.resolve("erasure.log"))) {
            long id = log.submit(new ErasureScope.Entity(3), WHEN);
            log.complete(List.of(id), WHEN);

            PublishFence fence = log.fence();
            assertThat(fence.obligations()).isZero();
            assertThat(fence.withheld().erasesFact(3, 0, 4, 0, 0, document(1))).isFalse();
            assertThat(fence.withheld().erasesLabel(3)).isFalse();
            assertThat(fence.withheld().erasesSnippet(3, Provenance.UNKNOWN)).isFalse();
        }
    }

    @Test
    @DisplayName("lets the swap through when nothing was owed since the fence was taken")
    void admitsASwapNothingOvertook(@TempDir Path dir) throws IOException {
        try (ErasureLog log = ErasureLog.open(dir.resolve("erasure.log"))) {
            log.submit(new ErasureScope.Entity(3), WHEN);
            PublishFence fence = log.fence();

            AtomicBoolean swapped = new AtomicBoolean();
            fence.swap(() -> swapped.set(true));

            assertThat(swapped).isTrue();
        }
    }

    @Test
    @DisplayName("refuses the swap once anything was owed after the fence was taken")
    void refusesASwapAnErasureOvertook(@TempDir Path dir) throws IOException {
        try (ErasureLog log = ErasureLog.open(dir.resolve("erasure.log"))) {
            PublishFence fence = log.fence();
            long late = log.submit(new ErasureScope.Source(SourceType.USER_ASSERTED, 7), WHEN);

            AtomicBoolean swapped = new AtomicBoolean();
            assertThatThrownBy(() -> fence.swap(() -> swapped.set(true)))
                .isInstanceOf(PublishRefusedException.class)
                .hasMessageContaining(String.valueOf(late));
            assertThat(swapped).as("nothing was moved into place").isFalse();
            assertThat(log.status(late).orElseThrow().state())
                .as("and the request is untouched by the refusal")
                .isEqualTo(ErasureLog.State.PENDING);
        }
    }

    @Test
    @DisplayName("a request accepted during the swap is ordered after it")
    void aSubmitDuringTheSwapWaitsForIt(@TempDir Path dir) throws Exception {
        try (ErasureLog log = ErasureLog.open(dir.resolve("erasure.log"))) {
            PublishFence fence = log.fence();
            AtomicLong accepted = new AtomicLong();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread submitter = new Thread(() -> {
                try {
                    accepted.set(log.submit(new ErasureScope.Entity(3), WHEN));
                } catch (IOException | RuntimeException e) {
                    failure.set(e);
                }
            });

            fence.swap(() -> {
                submitter.start();
                try {
                    submitter.join(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                assertThat(submitter.isAlive())
                    .as("the request cannot be accepted while the swap it would overtake runs")
                    .isTrue();
            });
            submitter.join();

            assertThat(failure.get()).isNull();
            assertThat(log.status(accepted.get()).orElseThrow().state())
                .isEqualTo(ErasureLog.State.PENDING);
            assertThatThrownBy(() -> fence.swap(() -> { }))
                .as("and a second swap behind the same fence is refused")
                .isInstanceOf(PublishRefusedException.class);
        }
    }

    @Test
    @DisplayName("a closed log admits no swap")
    void aClosedLogAdmitsNothing(@TempDir Path dir) throws IOException {
        ErasureLog log = ErasureLog.open(dir.resolve("erasure.log"));
        PublishFence fence = log.fence();
        log.close();

        assertThatThrownBy(() -> fence.swap(() -> { }))
            .isInstanceOf(IllegalStateException.class);
    }
}
