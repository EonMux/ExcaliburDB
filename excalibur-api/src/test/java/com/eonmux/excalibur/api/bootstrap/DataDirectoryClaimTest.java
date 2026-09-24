package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.core.datadir.DataDirectoryLock;
import com.eonmux.excalibur.core.datadir.DataDirectoryLockedException;
import com.eonmux.excalibur.core.datadir.InterruptedRestore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The node's claim on its data directory, made before anything in it is read or written.
 */
@DisplayName("Data directory claim")
class DataDirectoryClaimTest {

    @TempDir
    Path dataDirectory;

    @Test
    @DisplayName("a free directory is claimed, and the claim names the node")
    void claimsAFreeDirectory() throws IOException {
        try (DataDirectoryLock lock =
                 DataDirectoryClaim.claim(dataDirectory, NodeAssembly.SERVER_LOCK_HOLDER)) {
            assertThat(lock.holder().description())
                .isEqualTo(NodeAssembly.SERVER_LOCK_HOLDER);
            assertThat(DataDirectoryLock.probe(dataDirectory)).isPresent();
        }
    }

    @Test
    @DisplayName("a directory something else holds is refused, naming the directory and holder")
    void refusesAHeldDirectory() throws IOException {
        try (DataDirectoryLock ignored =
                 DataDirectoryLock.acquire(dataDirectory, "excalibur-fsck --repair")) {
            assertThatThrownBy(() ->
                DataDirectoryClaim.claim(dataDirectory, NodeAssembly.SERVER_LOCK_HOLDER))
                .isInstanceOf(DataDirectoryLockedException.class)
                .hasMessageContaining(dataDirectory.toString())
                .hasMessageContaining("excalibur-fsck --repair");
        }
    }

    @Test
    @DisplayName("a directory a restore stopped part-way through is refused, and left unclaimed")
    void refusesAnInterruptedRestore() throws IOException {
        Files.writeString(dataDirectory.resolve(InterruptedRestore.JOURNAL_FILE_NAME), "x");

        assertThatThrownBy(() ->
                DataDirectoryClaim.claim(dataDirectory, NodeAssembly.SERVER_LOCK_HOLDER))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("excalibur-backup restore");
        // Released on the way out, so the restore that completes the directory can take it.
        assertThat(DataDirectoryLock.probe(dataDirectory)).isEmpty();
    }
}
