package com.eonmux.excalibur.core.datadir;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DataDirectoryLock")
class DataDirectoryLockTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("the holder writes who it is into the lock file")
    void recordsHolder() throws IOException {
        try (DataDirectoryLock lock = DataDirectoryLock.acquire(directory, "excalibur node")) {
            String written = Files.readString(directory.resolve(DataDirectoryLock.FILE_NAME));
            assertThat(written)
                .contains("pid=" + ProcessHandle.current().pid())
                .contains("holder=excalibur node")
                .contains("since=");
            assertThat(lock.directory()).isEqualTo(directory);
            assertThat(lock.holder().pid()).isEqualTo(ProcessHandle.current().pid());
            assertThat(lock.holder().description()).isEqualTo("excalibur node");
        }
    }

    @Test
    @DisplayName("a second claim from the same process is refused, naming the directory and holder")
    void refusesSecondClaimInProcess() throws IOException {
        try (DataDirectoryLock ignored = DataDirectoryLock.acquire(directory, "excalibur node")) {
            assertThatThrownBy(() -> DataDirectoryLock.acquire(directory, "excalibur-fsck --repair"))
                .isInstanceOf(DataDirectoryLockedException.class)
                .hasMessageContaining(directory.toString())
                .hasMessageContaining("pid " + ProcessHandle.current().pid())
                .hasMessageContaining("excalibur node")
                .satisfies(e -> {
                    DataDirectoryLockedException locked = (DataDirectoryLockedException) e;
                    assertThat(locked.directory()).isEqualTo(directory);
                    assertThat(locked.holder()).map(LockHolder::description)
                        .contains("excalibur node");
                });
        }
    }

    @Test
    @DisplayName("a released lock can be taken again, and the new holder's record replaces the old")
    void reacquireAfterRelease() throws IOException {
        DataDirectoryLock first = DataDirectoryLock.acquire(directory, "a long first holder name");
        first.close();
        first.close();
        try (DataDirectoryLock second = DataDirectoryLock.acquire(directory, "second")) {
            String written = Files.readString(directory.resolve(DataDirectoryLock.FILE_NAME));
            assertThat(written).contains("holder=second").doesNotContain("first");
            assertThat(second.holder().description()).isEqualTo("second");
        }
    }

    @Test
    @DisplayName("a lock file with the same name through a different spelling is the same lock")
    void sameDirectoryDifferentSpelling() throws IOException {
        Files.createDirectories(directory.resolve("sub"));
        try (DataDirectoryLock ignored = DataDirectoryLock.acquire(directory, "node")) {
            Path spelled = directory.resolve("sub").resolve("..");
            assertThatThrownBy(() -> DataDirectoryLock.acquire(spelled, "other"))
                .isInstanceOf(DataDirectoryLockedException.class);
        }
    }

    @Test
    @DisplayName("a directory that is not there cannot be locked")
    void missingDirectory() {
        assertThatThrownBy(() -> DataDirectoryLock.acquire(directory.resolve("absent"), "node"))
            .isInstanceOf(NoSuchFileException.class)
            .hasMessageContaining("absent");
    }

    @Test
    @DisplayName("the holder's description must be one non-blank line")
    void holderMustBeOneLine() {
        assertThatThrownBy(() -> DataDirectoryLock.acquire(directory, " "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataDirectoryLock.acquire(directory, "two\nlines"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("probing a directory no process ever locked finds nobody and creates nothing")
    void probeWithoutLockFile() throws IOException {
        assertThat(DataDirectoryLock.probe(directory)).isEmpty();
        assertThat(directory.resolve(DataDirectoryLock.FILE_NAME)).doesNotExist();
    }

    @Test
    @DisplayName("a lock file left by a process that has exited is not a holder")
    void probeStaleFile() throws IOException {
        Files.writeString(directory.resolve(DataDirectoryLock.FILE_NAME),
            "pid=999999\nholder=excalibur node\n");
        assertThat(DataDirectoryLock.probe(directory)).isEmpty();
        try (DataDirectoryLock lock = DataDirectoryLock.acquire(directory, "node")) {
            assertThat(lock.holder().pid()).isEqualTo(ProcessHandle.current().pid());
        }
    }

    @Test
    @DisplayName("probing a directory this process holds reports this process")
    void probeHeldInProcess() throws IOException {
        try (DataDirectoryLock ignored = DataDirectoryLock.acquire(directory, "node")) {
            Optional<LockHolder> holder = DataDirectoryLock.probe(directory);
            assertThat(holder).map(LockHolder::pid).contains(ProcessHandle.current().pid());
        }
        assertThat(DataDirectoryLock.probe(directory)).isEmpty();
    }

    @Test
    @DisplayName("the endpoint a holder advertises is appended to its record")
    void advertisesEndpoint() throws IOException {
        try (DataDirectoryLock lock = DataDirectoryLock.acquire(directory, "excalibur node")) {
            lock.advertise("https://0.0.0.0:8443", "https://127.0.0.1:8443/health");
            String written = Files.readString(directory.resolve(DataDirectoryLock.FILE_NAME));
            assertThat(written)
                .contains("holder=excalibur node")
                .contains("url=https://0.0.0.0:8443\n")
                .contains("health-url=https://127.0.0.1:8443/health\n");
            assertThat(lock.holder().url()).contains("https://0.0.0.0:8443");
            assertThat(lock.holder().healthUrl()).contains("https://127.0.0.1:8443/health");
            assertThat(DataDirectoryLock.probe(directory)).map(LockHolder::url)
                .contains(Optional.of("https://0.0.0.0:8443"));
        }
    }

    @Test
    @DisplayName("a released lock cannot advertise")
    void advertiseAfterRelease() throws IOException {
        DataDirectoryLock lock = DataDirectoryLock.acquire(directory, "node");
        lock.close();
        assertThatThrownBy(() -> lock.advertise("http://127.0.0.1:1", "http://127.0.0.1:1/health"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an advertised value must be one line")
    void advertiseOneLine() throws IOException {
        try (DataDirectoryLock lock = DataDirectoryLock.acquire(directory, "node")) {
            assertThatThrownBy(() -> lock.advertise("http://a\nb", "http://c"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("another process holding the lock is refused, and named by its pid")
    void anotherProcess() throws Exception {
        Process child = startHolder(directory);
        try {
            assertThatThrownBy(() -> DataDirectoryLock.acquire(directory, "excalibur node"))
                .isInstanceOf(DataDirectoryLockedException.class)
                .hasMessageContaining(directory.toString())
                .hasMessageContaining("pid " + child.pid())
                .hasMessageContaining(LockHoldingProcess.HOLDER);
            assertThat(DataDirectoryLock.probe(directory)).map(LockHolder::pid)
                .contains(child.pid());
            // The refused attempt opened and closed a channel of its own on the file. On POSIX
            // closing any descriptor of a file drops every lock this process has on it - none -
            // and must leave the other process's lock where it was.
            assertThat(DataDirectoryLock.probe(directory)).isPresent();
        } finally {
            child.getOutputStream().close();
            assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();
        }
        try (DataDirectoryLock lock = DataDirectoryLock.acquire(directory, "excalibur node")) {
            assertThat(lock.holder().pid()).isEqualTo(ProcessHandle.current().pid());
        }
    }

    /**
     * Starts a JVM that takes the lock and holds it until its standard input closes.
     *
     * @param directory The directory to lock
     * @return The process, once it has reported the lock taken
     * @throws Exception if it cannot be started or never takes the lock
     */
    static Process startHolder(Path directory) throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> command = new ArrayList<>(List.of(java, "--enable-preview",
            "-cp", System.getProperty("java.class.path"),
            LockHoldingProcess.class.getName(), directory.toString()));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
        BufferedReader output = new BufferedReader(
            new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
        String line;
        StringBuilder seen = new StringBuilder();
        while ((line = output.readLine()) != null) {
            if (line.equals(LockHoldingProcess.READY)) {
                return child;
            }
            seen.append(line).append('\n');
        }
        child.destroyForcibly();
        throw new IllegalStateException("the holder process never took the lock:\n" + seen);
    }
}
