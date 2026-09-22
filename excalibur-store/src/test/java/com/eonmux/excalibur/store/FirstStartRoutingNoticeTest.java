package com.eonmux.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a store says about its routing directory the first time it opens.
 *
 * <p>The directory is rebuilt from segment file names when the file that recorded it is missing,
 * and that reconstruction is only exact while at most one segment has ever split - which is worth
 * a warning. A store with no segments at all is a different situation wearing the same clothes:
 * there is nothing to reconstruct, nothing has split, and the directory built is the initial one.
 * Warning there teaches an operator that the first line of every new deployment is noise.</p>
 */
@DisplayName("Routing notice on a first start")
class FirstStartRoutingNoticeTest {

    private final List<String> warnings = new ArrayList<>();
    private Handler captor;

    @BeforeEach
    void setUp() {
        captor = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger.getLogger(StorageEngine.class.getName()).addHandler(captor);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(StorageEngine.class.getName()).removeHandler(captor);
    }

    @Test
    @DisplayName("a new store does not warn about a directory it never had")
    void aNewStoreIsSilent(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("fresh"));

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();
            assertThat(engine.getSegmentCount()).isPositive();
        }

        assertThat(warnings)
            .as("nothing has split, so nothing about the directory can be inexact")
            .noneMatch(message -> message.contains("routing directory"));
    }

    @Test
    @DisplayName("a store whose directory went missing still warns")
    void aLostDirectoryStillWarns(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("lost"));
        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();
            engine.put("k".getBytes(StandardCharsets.UTF_8), "v".getBytes(StandardCharsets.UTF_8));
        }
        Files.deleteIfExists(dataDir.resolve(RoutingDirectory.FILE_NAME));
        warnings.clear();

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();
        }

        assertThat(warnings)
            .as("the reconstruction is a best effort and an operator has to be told")
            .anyMatch(message -> message.contains("routing directory"));
    }
}
