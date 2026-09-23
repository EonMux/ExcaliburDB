package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.DataDirectoryScan;
import com.eonmux.excalibur.fsck.check.DataDirectoryScanner;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.FileClassifier;
import com.eonmux.excalibur.fsck.check.FileKind;
import com.eonmux.excalibur.fsck.repair.RepairKind;
import com.eonmux.excalibur.fsck.repair.RepairPlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code source-status}: how each registered source's last crawl ended.
 *
 * <p>Written the way the source catalog is - a versioned header, one line per source, a
 * trailing record count - so it is checked the same way. What differs is what damage to it
 * costs: the node reads a history it cannot trust as no history and reports each source from its
 * next run, so a damaged one is reported and not counted against the store, as a damaged lexical
 * lookup table is not.</p>
 */
class SourceStatusCheckTest {

    private static final String WHOLE =
        "\\#source-status|1\ndocs|idle|1.0|420|0|7|1730000000000\n\\#end|1\n";

    private static FileCheck verdictOn(DataDirectoryScan scan) {
        return scan.files().stream()
            .filter(file -> file.relative().equals("source-status"))
            .findFirst()
            .orElseThrow();
    }

    @Test
    void theFileAndItsStagedWriteAreClassifiedByName() {
        assertThat(FileClassifier.classify("source-status")).isEqualTo(FileKind.SOURCE_STATUS);
        assertThat(FileClassifier.classify("source-status.tmp"))
            .isEqualTo(FileKind.STAGING_LEFTOVER);
    }

    @Test
    void onlyTheStatusHistoryIsDiscardedByTheNodeOnOpen() {
        assertThat(Arrays.stream(FileKind.values()).filter(FileKind::discardedOnOpen))
            .containsExactly(FileKind.SOURCE_STATUS);
    }

    @Test
    void aWholeFileIsProvenWholeButNotVerified(@TempDir Path dataDir) throws IOException {
        Files.writeString(dataDir.resolve("source-status"), WHOLE);

        FileCheck check = verdictOn(new DataDirectoryScanner(dataDir).scan());

        assertThat(check.status()).as(check.detail()).isEqualTo(CheckStatus.LEGACY_UNPROTECTED);
        assertThat(check.version()).isEqualTo("v1");
        assertThat(check.format()).isEqualTo("source status history");
    }

    @Test
    void aTruncatedFileIsCorruptButNotDamageAndIsPlannedForQuarantine(@TempDir Path dataDir)
            throws IOException {
        Files.writeString(dataDir.resolve("source-status"),
            "\\#source-status|1\ndocs|idle|1.0|420|0|7|1730000000000\n\\#end|2\n");

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();
        FileCheck check = verdictOn(scan);

        assertThat(check.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(check.isDamage())
            .as("a history the node cannot trust costs the history, not the store")
            .isFalse();
        assertThat(scan.hasDamage()).isFalse();
        assertThat(scan.rebuiltOnOpenCount()).isEqualTo(1);
        assertThat(RepairPlanner.plan(scan).actions()).anySatisfy(action -> {
            assertThat(action.relative()).isEqualTo("source-status");
            assertThat(action.kind()).isEqualTo(RepairKind.QUARANTINE_CORRUPT_FILE);
            assertThat(action.rationale()).contains("next run");
        });
    }

    @Test
    void aStatusHistoryThatCannotBeReadIsStillDamage(@TempDir Path dataDir) {
        FileCheck unreadable = new FileCheck(dataDir.resolve("source-status"), "source-status",
            "source status history", null, CheckStatus.UNREADABLE, "permission denied", -1);

        assertThat(unreadable.isDamage())
            .as("a refused read says something about the device, whatever the file")
            .isTrue();
    }
}
