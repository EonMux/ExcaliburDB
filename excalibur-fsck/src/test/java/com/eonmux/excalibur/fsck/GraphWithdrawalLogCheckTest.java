package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.FileClassifier;
import com.eonmux.excalibur.fsck.check.FileKind;
import com.eonmux.excalibur.fsck.check.GraphStagingCheck;
import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.provenance.SourceType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record of withdrawals of staged facts a tenant still owes is replayed like the erasure log
 * whose format it shares, rather than passed over as a file nothing recognises.
 */
class GraphWithdrawalLogCheckTest {

    @Test
    void theWithdrawalLogIsClassifiedAsAStagingLog() {
        assertThat(FileClassifier.classify("shared/graph-staging/withdrawals.log"))
            .isEqualTo(FileKind.GRAPH_STAGING_LOG);
        assertThat(GraphStagingCheck.matches(GraphStagingCheck.WITHDRAWAL_LOG)).isTrue();
    }

    @Test
    void theWithdrawalLogIsReplayedAndItsOwedCountReported(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve(GraphStagingCheck.WITHDRAWAL_LOG);
        try (ErasureLog log = ErasureLog.open(file)) {
            log.submit(new ErasureScope.Source(SourceType.DOCUMENT, 7L), Instant.EPOCH);
        }

        var result = GraphStagingCheck.check(file, "shared/graph-staging/withdrawals.log");

        assertThat(result.status()).isEqualTo(CheckStatus.INTACT);
        assertThat(result.format()).isEqualTo("graph withdrawal log");
        assertThat(result.detail()).contains("1 withdrawals of staged facts");
    }

    @Test
    void aDamagedWithdrawalLogIsCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(GraphStagingCheck.WITHDRAWAL_LOG);
        Files.write(file, "not a log at all, and long enough".getBytes());

        var result = GraphStagingCheck.check(file, "shared/graph-staging/withdrawals.log");

        assertThat(result.status()).isEqualTo(CheckStatus.CORRUPT);
    }
}
