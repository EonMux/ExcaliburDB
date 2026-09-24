package com.eonmux.excalibur.core.datadir;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReservedNames and InterruptedRestore")
class ReservedNamesTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("the lock file and a restore's journal and scratch are reserved, at the top level only")
    void reserved() {
        assertThat(ReservedNames.isReserved(DataDirectoryLock.FILE_NAME)).isTrue();
        assertThat(ReservedNames.isReserved(InterruptedRestore.JOURNAL_FILE_NAME)).isTrue();
        assertThat(ReservedNames.isReserved(InterruptedRestore.JOURNAL_FILE_NAME + ".tmp")).isTrue();
        assertThat(ReservedNames.isReserved(InterruptedRestore.STAGING_PREFIX + "x/segment_1.dat"))
            .isTrue();
        assertThat(ReservedNames.isReserved(InterruptedRestore.DISPLACED_PREFIX + "x/catalog.dat"))
            .isTrue();

        assertThat(ReservedNames.isReserved("segment_1.dat")).isFalse();
        assertThat(ReservedNames.isReserved("shared/" + DataDirectoryLock.FILE_NAME)).isFalse();
        assertThat(ReservedNames.isReserved("shared/" + InterruptedRestore.STAGING_PREFIX + "x"))
            .isFalse();
    }

    @Test
    @DisplayName("a directory without a restore journal passes")
    void noJournal() throws IOException {
        InterruptedRestore.requireNoneIn(directory);
        assertThat(InterruptedRestore.journalIn(directory)).isEmpty();
    }

    @Test
    @DisplayName("a directory with a restore journal is refused, and told how to finish it")
    void journalPresent() throws IOException {
        Files.writeString(directory.resolve(InterruptedRestore.JOURNAL_FILE_NAME), "x");
        assertThat(InterruptedRestore.journalIn(directory)).isPresent();
        assertThatThrownBy(() -> InterruptedRestore.requireNoneIn(directory))
            .isInstanceOf(IOException.class)
            .hasMessageContaining(directory.toString())
            .hasMessageContaining("excalibur-backup restore");
    }
}
