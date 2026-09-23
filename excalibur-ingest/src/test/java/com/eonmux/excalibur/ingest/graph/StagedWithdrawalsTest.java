package com.eonmux.excalibur.ingest.graph;

import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A withdrawal of staged facts that fails is owed until it is made.
 *
 * <p>Nothing else asks for it again: a removed document is not reported twice and has no registry
 * entry for an erasure to name, so a withdrawal dropped on its first failure left the document's
 * facts staged for every later build. These tests are the claim that one refused once is made
 * later, across a restart, and never at the cost of a newer version's facts.</p>
 *
 * <p>The refusal is real rather than mocked: a staging erase writes its rewrite beside the log it
 * replaces, and a directory standing where that rewrite goes makes the erase fail without touching
 * the store.</p>
 */
@DisplayName("StagedWithdrawals")
class StagedWithdrawalsTest {

    private static final String ADA = "docs:ada.md";
    private static final String BABBAGE = "docs:babbage.md";

    /** Stages one fact extracted from a document. */
    private static void stageFrom(GraphStagingStore staging, String documentKey, String object)
            throws IOException {
        staging.append(ExtractedTriple.alwaysValid("Ada", "runs", object,
            new Provenance(SourceType.DOCUMENT, LlmRelationExtractor.sourceIdOf(documentKey), 80,
                1)));
    }

    /** Makes every erase of the store's triple log fail until the returned path is removed. */
    private static Path refuseErasures(Path stagingDir) throws IOException {
        Path blocker = stagingDir.resolve(GraphStagingStore.TRIPLE_LOG + ".erasing");
        Files.createDirectories(blocker.resolve("held"));
        return blocker;
    }

    private static void allowErasures(Path blocker) throws IOException {
        Files.delete(blocker.resolve("held"));
        Files.delete(blocker);
    }

    private static Runnable staging(GraphStagingStore staging, String documentKey, String object) {
        return () -> {
            try {
                stageFrom(staging, documentKey, object);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
    }

    @Test
    @DisplayName("a refused withdrawal is owed, blocks the newer version, and is made by a retry")
    void aRefusedWithdrawalIsMadeLater(@TempDir Path dir) throws IOException {
        try (GraphStagingStore staging = GraphStagingStore.open(dir);
             StagedWithdrawals withdrawals =
                 StagedWithdrawals.open(staging, dir.resolve(StagedWithdrawals.LOG_FILE))) {
            stageFrom(staging, ADA, "Acme");
            stageFrom(staging, BABBAGE, "Initech");
            Path blocker = refuseErasures(dir);

            AtomicInteger staged = new AtomicInteger();
            StagedWithdrawals.Result refused = withdrawals.withdraw(ADA, staged::incrementAndGet);

            assertThat(refused.owed()).isTrue();
            assertThat(staged.get())
                .as("a newer version staged now would be taken by the retry with the old one")
                .isZero();
            assertThat(withdrawals.owes(ADA)).isTrue();
            assertThat(withdrawals.owes(BABBAGE)).isFalse();
            assertThat(withdrawals.retry()).as("still refused").isEqualTo(1);

            allowErasures(blocker);
            assertThat(withdrawals.retry()).isZero();

            assertThat(staging.stagedCount())
                .as("the document's fact is gone and the other document's is not")
                .isEqualTo(1);
            assertThat(withdrawals.owes(ADA)).isFalse();
            assertThat(withdrawals.toString()).contains("0 owed");
        }
    }

    @Test
    @DisplayName("what is owed survives a restart, and the reopened record makes it")
    void anOwedWithdrawalSurvivesARestart(@TempDir Path dir) throws IOException {
        Path log = dir.resolve(StagedWithdrawals.LOG_FILE);
        Path blocker;
        try (GraphStagingStore staging = GraphStagingStore.open(dir);
             StagedWithdrawals withdrawals = StagedWithdrawals.open(staging, log)) {
            stageFrom(staging, ADA, "Acme");
            blocker = refuseErasures(dir);

            withdrawals.withdraw(ADA, null);
        }
        allowErasures(blocker);

        try (GraphStagingStore staging = GraphStagingStore.open(dir);
             StagedWithdrawals withdrawals = StagedWithdrawals.open(staging, log)) {
            assertThat(withdrawals.owedCount()).isEqualTo(1);
            assertThat(staging.stagedCount()).isEqualTo(1);

            assertThat(withdrawals.retry()).isZero();

            assertThat(staging.stagedCount()).isZero();
        }
        assertThat(ErasureLog.inspect(log).count())
            .as("settled on disk, so the next open does not withdraw again")
            .isZero();
    }

    @Test
    @DisplayName("a document published again while a withdrawal is owed withdraws before staging")
    void aRepublicationMakesTheOwedWithdrawalFirst(@TempDir Path dir) throws IOException {
        try (GraphStagingStore staging = GraphStagingStore.open(dir);
             StagedWithdrawals withdrawals =
                 StagedWithdrawals.open(staging, dir.resolve(StagedWithdrawals.LOG_FILE))) {
            stageFrom(staging, ADA, "Acme");
            Path blocker = refuseErasures(dir);
            withdrawals.withdraw(ADA, null);

            StagedWithdrawals.Result stillRefused =
                withdrawals.stage(ADA, staging(staging, ADA, "Globex"));
            assertThat(stillRefused.owed()).isTrue();
            assertThat(staging.stagedCount()).as("nothing newer while it is owed").isEqualTo(1);

            allowErasures(blocker);
            StagedWithdrawals.Result made = withdrawals.stage(ADA, staging(staging, ADA, "Globex"));

            assertThat(made.owed()).isFalse();
            assertThat(made.removed()).isPositive();
            assertThat(staging.stagedCount()).as("only the new version").isEqualTo(1);

            // Settled, so a retry now has nothing to take - in particular not the new version.
            assertThat(withdrawals.retry()).isZero();
            assertThat(staging.stagedCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("staging a document that owes nothing withdraws nothing")
    void stagingWhatOwesNothing(@TempDir Path dir) throws IOException {
        try (GraphStagingStore staging = GraphStagingStore.open(dir);
             StagedWithdrawals withdrawals =
                 StagedWithdrawals.open(staging, dir.resolve(StagedWithdrawals.LOG_FILE))) {
            stageFrom(staging, ADA, "Acme");

            StagedWithdrawals.Result result =
                withdrawals.stage(ADA, staging(staging, ADA, "Globex"));

            assertThat(result).isEqualTo(new StagedWithdrawals.Result(0, false));
            assertThat(staging.stagedCount()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a replacement's successful withdrawal settles what an earlier one owed")
    void aLaterWithdrawalSettlesTheOwedOne(@TempDir Path dir) throws IOException {
        Path log = dir.resolve(StagedWithdrawals.LOG_FILE);
        try (GraphStagingStore staging = GraphStagingStore.open(dir);
             StagedWithdrawals withdrawals = StagedWithdrawals.open(staging, log)) {
            stageFrom(staging, ADA, "Acme");
            Path blocker = refuseErasures(dir);
            withdrawals.withdraw(ADA, null);
            withdrawals.withdraw(ADA, null);
            assertThat(withdrawals.owedCount()).as("owed once, however often refused")
                .isEqualTo(1);
            allowErasures(blocker);

            StagedWithdrawals.Result made =
                withdrawals.withdraw(ADA, staging(staging, ADA, "Globex"));

            assertThat(made.owed()).isFalse();
            assertThat(staging.stagedCount()).isEqualTo(1);
        }
        assertThat(ErasureLog.inspect(log).count()).isZero();
    }

    @Test
    @DisplayName("with nowhere to record it, a withdrawal is owed for the life of the object")
    void inMemoryWithdrawalsAreRetried(@TempDir Path dir) throws IOException {
        try (GraphStagingStore staging = GraphStagingStore.open(dir);
             StagedWithdrawals withdrawals = StagedWithdrawals.inMemory(staging)) {
            stageFrom(staging, ADA, "Acme");
            Path blocker = refuseErasures(dir);

            assertThat(withdrawals.withdraw(ADA, null).owed()).isTrue();
            assertThat(withdrawals.toString()).contains("1 owed").contains("in memory");

            allowErasures(blocker);
            assertThat(withdrawals.retry()).isZero();
            assertThat(staging.stagedCount()).isZero();
        }
    }

    @Test
    @DisplayName("a withdrawal the log cannot record is still owed and still made")
    void aWithdrawalTheLogCannotRecord(@TempDir Path dir) throws IOException {
        ErasureLog closed = ErasureLog.open(dir.resolve(StagedWithdrawals.LOG_FILE));
        closed.close();
        try (GraphStagingStore staging = GraphStagingStore.open(dir);
             StagedWithdrawals withdrawals =
                 new StagedWithdrawals(staging, closed, Clock.systemUTC())) {
            stageFrom(staging, ADA, "Acme");
            Path blocker = refuseErasures(dir);

            assertThat(withdrawals.withdraw(ADA, null).owed()).isTrue();
            assertThat(withdrawals.owes(ADA)).isTrue();

            allowErasures(blocker);
            assertThat(withdrawals.retry())
                .as("made, but a completion the log cannot record leaves it owed: settling it "
                    + "without one would let a restart replay it over newer facts")
                .isEqualTo(1);
            assertThat(staging.stagedCount()).isZero();
        }
    }
}
