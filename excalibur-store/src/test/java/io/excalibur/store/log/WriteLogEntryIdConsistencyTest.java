package io.excalibur.store.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests to verify entry ID consistency between what is written and what is read.
 * This specifically tests the bug where nextEntryId() is called multiple times
 * in the append() and appendBarrier() methods, causing the checksum to be
 * calculated with a different ID than what's written to the buffer.
 */
@DisplayName("WriteLog Entry ID Consistency Tests")
class WriteLogEntryIdConsistencyTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Entry ID returned from append should match ID in scanned entry")
    void returnedEntryIdShouldMatchScannedEntryId() throws IOException {
        Path logPath = tempDir.resolve("test-entry-id.log");
        
        try (WriteLog log = new WriteLog(logPath)) {
            // Append an entry and get the returned ID
            long returnedId = log.appendPut("key1", "value1");
            
            // Scan to get the entry
            List<LogEntry> entries = log.scanRange(0, log.writeHead());
            
            assertThat(entries).hasSize(1);
            long scannedId = entries.get(0).getId();
            
            // CRITICAL: The returned ID MUST match the scanned ID
            assertThat(returnedId)
                .as("Returned entry ID (%d) must match scanned entry ID (%d)", returnedId, scannedId)
                .isEqualTo(scannedId);
        }
    }

    @Test
    @DisplayName("Entry ID should be consistent across multiple appends")
    void entryIdShouldBeConsistentAcrossMultipleAppends() throws IOException {
        Path logPath = tempDir.resolve("test-multi-entry-id.log");
        
        try (WriteLog log = new WriteLog(logPath)) {
            long id1 = log.appendPut("key1", "value1");
            long id2 = log.appendPut("key2", "value2");
            long id3 = log.appendDelete("key1");
            
            List<LogEntry> entries = log.scanRange(0, log.writeHead());
            
            assertThat(entries).hasSize(3);
            
            // Each scanned ID should match the returned ID in order
            assertThat(entries.get(0).getId()).isEqualTo(id1);
            assertThat(entries.get(1).getId()).isEqualTo(id2);
            assertThat(entries.get(2).getId()).isEqualTo(id3);
        }
    }

    @Test
    @DisplayName("Barrier entry ID should be consistent")
    void barrierEntryIdShouldBeConsistent() throws IOException {
        Path logPath = tempDir.resolve("test-barrier-id.log");
        
        try (WriteLog log = new WriteLog(logPath)) {
            long barrierId = log.appendBarrier();
            
            List<LogEntry> entries = log.scanRange(0, log.writeHead());
            
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).getId())
                .as("Barrier entry ID must be consistent")
                .isEqualTo(barrierId);
        }
    }

    @Test
    @DisplayName("Checksum should verify successfully when reading from disk")
    void checksumShouldVerifyFromDisk() throws IOException {
        Path logPath = tempDir.resolve("test-checksum.log");
        
        // Write entries
        try (WriteLog log = new WriteLog(logPath)) {
            log.appendPut("key1", "value1");
            log.appendPut("key2", "value2");
            log.sync();
        }
        
        // Re-open and recover (this reads from disk)
        try (WriteLog log = new WriteLog(logPath)) {
            log.recover();
            List<LogEntry> entries = log.scanRange(0, log.writeHead());
            
            // If checksums were wrong, entries would be skipped
            assertThat(entries)
                .as("All entries should be recoverable from disk (checksums must be valid)")
                .hasSize(2);
        }
    }

    @Test
    @DisplayName("Batch append entry IDs should be consistent")
    void batchAppendEntryIdsShouldBeConsistent() throws IOException {
        Path logPath = tempDir.resolve("test-batch-id.log");
        
        try (WriteLog log = new WriteLog(logPath)) {
            List<WriteLog.BatchEntry> batch = List.of(
                WriteLog.BatchEntry.put("k1".getBytes(), "v1".getBytes()),
                WriteLog.BatchEntry.put("k2".getBytes(), "v2".getBytes()),
                WriteLog.BatchEntry.put("k3".getBytes(), "v3".getBytes())
            );
            
            WriteLog.BatchAppendResult result = log.appendBatch(batch);
            List<Long> returnedIds = result.entryIds();
            
            List<LogEntry> entries = log.scanRange(result.startOffset(), result.endOffset());
            
            assertThat(entries).hasSize(3);
            
            // Each scanned ID should match the returned ID
            for (int i = 0; i < 3; i++) {
                assertThat(entries.get(i).getId())
                    .as("Batch entry %d: scanned ID must match returned ID", i)
                    .isEqualTo(returnedIds.get(i));
            }
        }
    }
}
