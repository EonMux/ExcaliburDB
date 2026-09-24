package com.eonmux.excalibur.graph.erasure;

import com.eonmux.excalibur.graph.provenance.SourceType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Listing the requests a log can describe, a page at a time.
 *
 * <p>A caller holding no request id - an operator auditing what a namespace still owes - has to
 * be able to find the obligations without guessing ids. The listing is the log's, so it has to
 * be the same from the open log and from the file.</p>
 */
@DisplayName("Erasure request listing")
class ErasureLogListingTest {

    private static final Instant REQUESTED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant APPLIED = Instant.parse("2026-01-01T00:05:00Z");

    private static Path logFile(Path dir) {
        return dir.resolve("erasure").resolve("requests.log");
    }

    /** Submits five requests and completes the second and fourth, out of id order. */
    private static List<Long> fiveWithTwoDone(ErasureLog log) throws IOException {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(log.submit(new ErasureScope.Source(SourceType.USER_ASSERTED, i), REQUESTED));
        }
        log.complete(List.of(ids.get(3)), APPLIED);
        log.complete(List.of(ids.get(1)), APPLIED);
        return ids;
    }

    private static List<Long> idsOf(ErasureLog.RequestPage page) {
        return page.requests().stream().map(ErasureLog.RequestStatus::id).toList();
    }

    @Test
    @DisplayName("lists pending and retained discharged requests together, in id order")
    void listsBothStatesInIdOrder(@TempDir Path dir) throws IOException {
        try (ErasureLog log = ErasureLog.open(logFile(dir))) {
            List<Long> ids = fiveWithTwoDone(log);

            ErasureLog.RequestPage page = log.requests(null, 0, 10);

            assertThat(idsOf(page)).containsExactlyElementsOf(ids);
            assertThat(page.total()).isEqualTo(5);
            assertThat(page.next()).isEmpty();
            assertThat(page.requests().get(1).state()).isEqualTo(ErasureLog.State.DISCHARGED);
            assertThat(page.requests().get(1).completedAt()).contains(APPLIED);
            assertThat(page.requests().get(0).state()).isEqualTo(ErasureLog.State.PENDING);
        }
    }

    @Test
    @DisplayName("narrows to one state, and the total counts only that state")
    void narrowsByState(@TempDir Path dir) throws IOException {
        try (ErasureLog log = ErasureLog.open(logFile(dir))) {
            List<Long> ids = fiveWithTwoDone(log);

            ErasureLog.RequestPage pending = log.requests(ErasureLog.State.PENDING, 0, 10);
            ErasureLog.RequestPage done = log.requests(ErasureLog.State.DISCHARGED, 0, 10);

            assertThat(idsOf(pending)).containsExactly(ids.get(0), ids.get(2), ids.get(4));
            assertThat(pending.total()).isEqualTo(3);
            assertThat(idsOf(done)).containsExactly(ids.get(1), ids.get(3));
            assertThat(done.total()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("pages continue strictly after the id the last one ended on")
    void pagesByCursor(@TempDir Path dir) throws IOException {
        try (ErasureLog log = ErasureLog.open(logFile(dir))) {
            List<Long> ids = fiveWithTwoDone(log);

            ErasureLog.RequestPage first = log.requests(null, 0, 2);
            ErasureLog.RequestPage second = log.requests(null, first.next().orElseThrow(), 2);
            ErasureLog.RequestPage last = log.requests(null, second.next().orElseThrow(), 2);

            assertThat(idsOf(first)).containsExactly(ids.get(0), ids.get(1));
            assertThat(idsOf(second)).containsExactly(ids.get(2), ids.get(3));
            assertThat(idsOf(last)).containsExactly(ids.get(4));
            assertThat(last.next()).as("null on the last page").isEmpty();
            assertThat(last.total()).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("a page that ends exactly on the last request says there is no next one")
    void anExactFinalPageHasNoNext(@TempDir Path dir) throws IOException {
        try (ErasureLog log = ErasureLog.open(logFile(dir))) {
            fiveWithTwoDone(log);

            assertThat(log.requests(null, 0, 5).next()).isEmpty();
        }
    }

    @Test
    @DisplayName("completions past the retained window are not listed")
    void unretainedCompletionsAreNotListed(@TempDir Path dir) throws IOException {
        try (ErasureLog log = ErasureLog.open(logFile(dir))) {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i <= ErasureLog.RETAINED_COMPLETIONS; i++) {
                ids.add(log.submit(new ErasureScope.Entity(i), REQUESTED));
            }
            log.complete(ids, APPLIED);

            ErasureLog.RequestPage page = log.requests(null, 0, 1);

            assertThat(page.total()).isEqualTo(ErasureLog.RETAINED_COMPLETIONS);
            assertThat(idsOf(page)).containsExactly(ids.get(1));
        }
    }

    @Test
    @DisplayName("the file answers exactly as the open log does")
    void fileMatchesOpenLog(@TempDir Path dir) throws IOException {
        ErasureLog.RequestPage open;
        try (ErasureLog log = ErasureLog.open(logFile(dir))) {
            fiveWithTwoDone(log);
            open = log.requests(null, 1, 3);
        }

        assertThat(ErasureLog.requestsOf(logFile(dir), null, 1, 3)).isEqualTo(open);
    }

    @Test
    @DisplayName("a log that does not exist, or was torn at creation, lists nothing")
    void absentOrEmptyFilesListNothing(@TempDir Path dir) throws IOException {
        assertThat(ErasureLog.requestsOf(logFile(dir), null, 0, 10).requests()).isEmpty();

        Files.createDirectories(logFile(dir).getParent());
        Files.write(logFile(dir), new byte[0]);
        ErasureLog.RequestPage torn = ErasureLog.requestsOf(logFile(dir), null, 0, 10);

        assertThat(torn.requests()).isEmpty();
        assertThat(torn.total()).isZero();
    }

    @Test
    @DisplayName("a limit below one or a negative cursor is refused")
    void refusesBadBounds(@TempDir Path dir) throws IOException {
        try (ErasureLog log = ErasureLog.open(logFile(dir))) {
            assertThatThrownBy(() -> log.requests(null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> log.requests(null, -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
