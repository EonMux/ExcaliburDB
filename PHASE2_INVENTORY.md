# Phase 2 (Storage Engine) - Feature Inventory

**Date:** 2026-03-19
**Status:** In Progress
**Purpose:** Document all Phase 2 components, their implementation status, and feature specifications.

---

## Feature Summary

| Feature # | Feature Name | Status | Implementation File |
|-----------|--------------|--------|---------------------|
| Feature 7 | Disk-Backed Key-Value Segment | Implemented | `Segment.java` |
| Feature 8 | Zero-Copy Reads via MemorySegment Slices | Implemented | `Segment.java` |
| Feature 9 | Storage Engine Keyspace Management | Implemented | `StorageEngine.java` |
| Feature 10 | Segment Lifecycle States | Implemented | `SegmentCatalog.java` |
| Feature 11 | Visibility Epoch for Consistent Snapshots | Implemented | `StorageEngine.java` |
| Feature 12 | Segment Catalog Metadata | Implemented | `SegmentCatalog.java` |
| Feature 13 | Write-Ahead Log (WAL) | Implemented | `WriteLog.java` |
| **Feature 14** | **WAL Entry Format** | **Implemented** | `LogEntry.java` |
| Feature 15 | Robin Hood Hashing with Distance Optimization | Implemented | `Segment.java` |
| Feature 16 | Load Factor Management | Implemented | `Segment.java`, `SplitWorker.java` |
| Feature 20 | Striped Locking for Concurrent Access | Implemented | `Segment.java` |
| Feature 47 | Background Compaction Worker | Implemented | `CompactionWorker.java` |
| Feature 48 | Non-blocking Copy-on-Write Compaction | Implemented | `CompactionWorker.java` |

---

## Feature 14: WAL Entry Format

**Status:** ✅ IMPLEMENTED AND VERIFIED

**Implementation File:** `excalibur-store/src/main/java/io/excalibur/store/format/LogEntry.java`

### Canonical Format Specification

Each WAL (Write-Ahead Log) entry has the following binary structure:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         WAL Entry Format (Variable Length)                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ Offset  │ Field      │ Size      │ Description                              │
├─────────┼────────────┼───────────┼──────────────────────────────────────────┤
│ 0       │ opcode     │ 1 byte    │ Operation type: PUT=0x01, DELETE=0x02   │
│ 1       │ lsn        │ 8 bytes   │ Log Sequence Number (little-endian)     │
│ 9       │ timestamp  │ 8 bytes   │ Epoch milliseconds (little-endian)      │
│ 17      │ key_hash   │ 8 bytes   │ 64-bit xxHash of key (little-endian)    │
│ 25      │ key_len    │ 4 bytes   │ Key length in bytes (little-endian)     │
│ 29      │ value_len  │ 4 bytes   │ Value length, 0 for DELETE (LE)         │
│ 33      │ key_bytes  │ key_len   │ Raw key bytes                           │
│ 33+K    │ value_bytes│ value_len │ Raw value bytes (empty for DELETE)      │
│ 33+K+V  │ crc32      │ 4 bytes   │ CRC-32 checksum (little-endian)         │
└─────────┴────────────┴───────────┴──────────────────────────────────────────┘

Total size = 33 + key_len + value_len + 4 = 37 + key_len + value_len bytes
```

### Format Representation

```
[opcode(1B)|lsn(8B)|timestamp(8B)|key_hash(8B)|key_len(4B)|value_len(4B)|key|value|crc32(4B)]
```

### Domain Constraints

| Field | Valid Range | Notes |
|-------|-------------|-------|
| `opcode` | 0x01 or 0x02 | PUT=0x01, DELETE=0x02 only |
| `lsn` | [0, Long.MAX_VALUE] | Monotonically increasing per segment |
| `timestamp` | (0, Long.MAX_VALUE] | Positive epoch milliseconds |
| `key_hash` | [0, Long.MAX_VALUE] | Positive 64-bit hash from xxHash64 |
| `key_len` | [1, Integer.MAX_VALUE - 37] | Non-empty keys required |
| `value_len` | [0, Integer.MAX_VALUE - 37] | Zero for DELETE operations |
| `crc32` | IEEE 802.3 CRC-32 | Checksum of all preceding bytes |

### Byte Order

All multi-byte fields use **little-endian** encoding.

### Implementation Details

1. **LogEntry Class** (`LogEntry.java`):
   - Immutable entry representation
   - Factory methods: `put()` and `delete()`
   - Serialization: `writeTo(MemorySegment, offset)` and `writeTo(ByteBuffer, offset)`
   - Deserialization: `readFrom(MemorySegment, offset)` with CRC32 validation
   - Fast path: `readFromUnchecked()` for trusted sources

2. **WriteLog Class** (`WriteLog.java`):
   - Append-only log file with memory-mapped I/O
   - `appendPut(key, value)` → returns assigned LSN
   - `appendDelete(key)` → returns assigned LSN
   - `recover()` → replays entries in LSN order
   - Automatic fsync after each append

3. **CRC32 Checksum**:
   - Uses IEEE 802.3 polynomial (0xEDB88320)
   - Computed over: opcode + lsn + timestamp + key_hash + key_len + value_len + key + value
   - Stored as last 4 bytes of entry (little-endian)

### Test Coverage

Comprehensive tests in `LogEntryTest.java` validate:
- Byte layout at each offset (lines 576-758)
- CRC32 computation and validation (lines 962-1048)
- Read/write round-trip correctness (lines 399-531)
- Domain constraint validation (lines 174-344)
- PUT and DELETE operation types
- Edge cases: empty values, maximum lengths, boundary conditions

### Integration Points

- `Segment.put()` → calls `writeLog.appendPut()` before updating hash map
- `Segment.delete()` → calls `writeLog.appendDelete()` before marking tombstone
- `Segment.recoverFromLog()` → called during segment initialization
- `StorageEngine` → creates WriteLog per segment

### Historical Note

**IMPORTANT:** This is the canonical format specification. Any documentation that describes the format as `[magic|type|timestamp|lsn|key_len|value_len|key|value|crc32]` is **INCORRECT** and should be updated to match this specification. The implementation has never included a magic field, and the field order is as documented above.

---

## Other Phase 2 Features

### Feature 7: Disk-Backed Key-Value Segment

**Status:** ✅ Implemented

Implementation uses memory-mapped files with Robin Hood hashing for O(1) lookups.

### Feature 8: Zero-Copy Reads

**Status:** ✅ Implemented

`Segment.get()` returns `MemorySegment.asSlice()` pointing to off-heap memory.

### Feature 15: Robin Hood Hashing

**Status:** ✅ Implemented

Uses distance-based displacement with early termination optimization.

### Feature 20: Striped Locking

**Status:** ✅ Implemented

Each segment has its own `ReentrantReadWriteLock` for concurrent access.

---

## References

- `excalibur-store/src/main/java/io/excalibur/store/format/LogEntry.java` - WAL entry format
- `excalibur-store/src/main/java/io/excalibur/store/WriteLog.java` - Write-ahead log
- `excalibur-store/src/main/java/io/excalibur/store/Segment.java` - Key-value segment
- `excalibur-store/src/test/java/io/excalibur/store/format/LogEntryTest.java` - Format tests
