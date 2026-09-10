package io.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What reopening a log finds past its last entry.
 *
 * <p>The file is mapped at its maximum size and written sparsely, so everything past the last
 * entry is zeroes - not a damaged entry but a slot nothing has been written to yet. Reading it
 * as corruption made every start of a node with routed write logs report as many corruption
 * warnings as it had logs, which is the shape of a real emergency and was none: an operator
 * cannot tell the two apart if the clean case cries first.</p>
 */
@DisplayName("Write log reopen scan")
class WriteLogReopenScanTest {

    @TempDir
    Path directory;

    private Path logFile;
    private final List<LogRecord> warnings = new ArrayList<>();
    private Handler captor;

    @BeforeEach
    void setUp() {
        logFile = directory.resolve("reopen.wal");
        captor = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger.getLogger(WriteLog.class.getName()).addHandler(captor);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(WriteLog.class.getName()).removeHandler(captor);
    }

    @Test
    @DisplayName("an empty log reopens without reporting corruption")
    void anEmptyLogIsNotCorrupt() throws IOException {
        new WriteLog(logFile).close();
        warnings.clear();

        try (WriteLog reopened = new WriteLog(logFile)) {
            assertThat(reopened.writeOffset()).isZero();
        }

        assertThat(messages()).isEmpty();
    }

    @Test
    @DisplayName("the zeroes past the last entry are where the log ends")
    void anUnwrittenTailIsTheEnd() throws IOException {
        long offsetAfterWrite;
        try (WriteLog log = new WriteLog(logFile)) {
            log.appendPut("k1".getBytes(StandardCharsets.UTF_8), "v1".getBytes(StandardCharsets.UTF_8));
            log.appendPut("k2".getBytes(StandardCharsets.UTF_8), "v2".getBytes(StandardCharsets.UTF_8));
            offsetAfterWrite = log.writeOffset();
        }
        warnings.clear();

        try (WriteLog reopened = new WriteLog(logFile)) {
            assertThat(reopened.writeOffset())
                .as("the scan stops where the entries do, so the next append lands after them")
                .isEqualTo(offsetAfterWrite);
            assertThat(reopened.nextLsn()).isEqualTo(2);
        }

        assertThat(messages()).isEmpty();
    }

    @Test
    @DisplayName("an entry damaged in place is still reported")
    void aDamagedEntryIsStillReported() throws IOException {
        try (WriteLog log = new WriteLog(logFile)) {
            log.appendPut("k1".getBytes(StandardCharsets.UTF_8), "v1".getBytes(StandardCharsets.UTF_8));
        }
        // The opcode stays valid and a byte of the payload changes, which is what a damaged
        // entry looks like: written, then no longer what was written.
        corruptByteAt(40);
        warnings.clear();

        try (WriteLog reopened = new WriteLog(logFile)) {
            assertThat(reopened.writeOffset()).isZero();
        }

        assertThat(messages()).anyMatch(m -> m.contains("orrupt") || m.contains("nvalid"));
    }

    private void corruptByteAt(long offset) throws IOException {
        try (FileChannel channel = FileChannel.open(logFile, StandardOpenOption.WRITE)) {
            ByteBuffer flipped = ByteBuffer.allocate(1).order(ByteOrder.LITTLE_ENDIAN);
            flipped.put((byte) 0x7F).flip();
            channel.write(flipped, offset);
        }
    }

    private List<String> messages() {
        return warnings.stream().map(LogRecord::getMessage).toList();
    }
}
