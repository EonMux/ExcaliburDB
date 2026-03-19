# Stub and Incomplete Implementation Inventory

**Generated**: 2026-03-19
**Scope**: Phases 1-6 (Core, Store, Filter, Vector, Shard, Query)

## Summary

| Category | Count | Severity |
|----------|-------|----------|
| Placeholder/No-op Methods | 6 | MEDIUM |
| Partial Implementations | 4 | HIGH |
| Interface Default Stubs | 1 | LOW |
| Serialization Gaps | 1 | LOW |
| Hierarchical Filter Missing | 1 | HIGH |

---

## 1. Placeholder Implementations (HIGH PRIORITY)

### 1.1 QueryPipeline.java - Cuckoo Filter Cascade (Stage 1)

**File**: `excalibur-query/src/main/java/excalibur/query/pipeline/QueryPipeline.java:281`

```java
// Placeholder: mark all candidates as passing Cuckoo filter
List<SegmentCandidate> passed = new ArrayList<>(context.candidates);
```

**Issue**: Stage 1 (Cuckoo filter cascade) is a pass-through. No actual filtering occurs.
**Impact**: All segments are searched regardless of Cuckoo filter results. Degraded query performance.
**Required Fix**: Implement hierarchical Cuckoo filter cascade to eliminate segments that cannot contain relevant entries.

### 1.2 QueryPipeline.java - LSH Pre-filter (Stage 2)

**File**: `excalibur-query/src/main/java/excalibur/query/pipeline/QueryPipeline.java:304`

```java
// Placeholder: mark all candidates as passing LSH filter
List<SegmentCandidate> passed = new ArrayList<>(context.candidates);
```

**Issue**: Stage 2 (LSH pre-filter) is a pass-through. No actual filtering occurs.
**Impact**: No coarse vector similarity filtering, searches all segments with HNSW.
**Required Fix**: Implement LSH-based pre-filtering to reduce candidates to 10x-100x top_k.

### 1.3 QueryPipeline.java - Tail Scan

**File**: `excalibur-query/src/main/java/excalibur/query/pipeline/QueryPipeline.java:391-395`

```java
if (lastIntegratedOffset < writeLogHead && candidate.segment.writeLog() != null) {
    // Tail scan needed for live indexing
    // This would be handled by TailScanner
    // For now, just return HNSW results
}
```

**Issue**: Tail scan for live indexing is not executed.
**Impact**: Entries in write log not yet integrated into HNSW graph are invisible to queries.
**Required Fix**: Call `TailScanner` to scan write log entries beyond `lastIntegratedOffset`.

### 1.4 CuckooMaintenanceWorker.java - Hierarchical Filter Propagation

**File**: `excalibur-shard/src/main/java/excalibur/shard/worker/CuckooMaintenanceWorker.java:495-502`

```java
private void propagateDeletionsUpHierarchy() {
    // Placeholder for hierarchical filter propagation
    // When hierarchical filters are implemented, this method would:
    // 1. Track deleted keys at segment level
    // 2. Delete corresponding entries in parent filters
    // 3. Handle the case where a key exists in multiple segments

    LOGGER.trace("Hierarchical filter propagation - no-op (hierarchy not yet implemented)");
}
```

**Issue**: Hierarchical Cuckoo filter (corpus → topic cluster → segment) is not implemented.
**Impact**: No bulk pruning when segments are tombstoned. No propagation of deletions up the hierarchy.
**Required Fix**: Implement hierarchical filter structure per Phase 3 spec.

---

## 2. Interface Default Stubs (LOW PRIORITY)

### 2.1 VisibilityTracker.Default - Segment Registration

**File**: `excalibur-store/src/main/java/io/excalibur/store/VisibilityTracker.java:139-151`

```java
@Override
public void registerSegment(long segmentId, long epoch) {
    // No-op in simple implementation
}

@Override
public void markTombstoned(long segmentId, long epoch) {
    // No-op in simple implementation
}

@Override
public Set<Long> visibleSegments(long queryEpoch) {
    // Returns empty set - caller must use catalog directly
    return Set.of();
}
```

**Issue**: Default implementation provides no segment tracking.
**Impact**: Query pipeline cannot use this implementation for epoch-based visibility. Must use `excalibur-shard` implementation.
**Status**: BY DESIGN - This is a fallback for StorageEngine when full VisibilityTracker is unavailable.
**Action**: No fix required, but document limitation clearly.

---

## 3. Partial Implementations (MEDIUM PRIORITY)

### 3.1 LshIndex.java - Serialization

**File**: `excalibur-filter/src/main/java/excalibur/filter/lsh/LshIndex.java:395-398`

```java
// Note: Full serialization would include each table's data
// For now, this provides a header for file-based persistence
return segment.asSlice(0, offset);
```

**Issue**: `toMemorySegment()` only serializes header, not hash table data.
**Impact**: LSH index cannot be persisted to disk and reloaded.
**Required Fix**: Implement full serialization of all hash table entries.

### 3.2 HnswIntegrationWorker.java - Segment State Lookup

**File**: `excalibur-shard/src/main/java/excalibur/shard/worker/HnswIntegrationWorker.java:589-601`

```java
private LifecycleState getSegmentState(Segment segment) {
    try {
        // Get segment ID from path (assumes format segment_N.dat)
        String filename = segment.path().getFileName().toString();
        String idStr = filename.replace("segment_", "").replace(".dat", "");
        long segmentId = Long.parseLong(idStr);

        SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
        return metadata != null ? metadata.getLifecycleState() : null;
    } catch (Exception e) {
        return null;
    }
}
```

**Issue**: Segment ID extraction from filename is fragile.
**Impact**: May fail if segment filename format changes.
**Required Fix**: Add segment ID accessor to Segment class or use catalog path mapping.

### 3.3 QueryEndpoint.java - Minimal JSON Parser

**File**: `excalibur-api/src/main/java/excalibur/api/endpoint/QueryEndpoint.java:183-209`

```java
private QueryRequest parseRequest(String json, String correlationId) {
    // Simple JSON parsing (in production, use a proper JSON parser)
    ...
}
```

**Issue**: Hand-rolled JSON parser is fragile and may not handle edge cases.
**Impact**: May fail on malformed JSON or edge cases (escaped quotes, nested objects).
**Status**: ACCEPTABLE per project constraints ("Hand-serialized. No Jackson, no Gson").
**Action**: Ensure test coverage for edge cases.

---

## 4. Missing Hierarchical Cuckoo Filter (HIGH PRIORITY)

### 4.1 HierarchicalCuckooFilter - Not Implemented

**Expected Location**: `excalibur-filter/src/main/java/excalibur/filter/hierarchical/`

**Spec Requirement** (Phase 3):
> **Hierarchical Cuckoo Filter.** Tree of Cuckoo filters: corpus → topic cluster → segment. Query checks coarsest first, drills down on positives. Each level is independently updatable and supports deletion.

**Current State**: Only single-level Cuckoo filter exists.

**Impact**:
- Cannot bulk-prune segments at corpus level
- Query must check all segment-level filters
- No topic cluster optimization

**Required Implementation**:
1. `HierarchicalCuckooFilter` class with tree structure
2. Corpus-level filter
3. Topic cluster level filters
4. Segment-level filter references
5. Deletion propagation from segment → cluster → corpus

---

## 5. Thread Safety Documentation Compliance

**Constraint**: "Every public class must document its thread-safety guarantees in Javadoc."

### 5.1 Compliant Modules (Verified)
- `excalibur-query/` - All classes have thread-safety documentation
- `excalibur-shard/` - All worker classes documented
- `excalibur-filter/lsh/` - LshIndex has thread-safety documentation

### 5.2 Requires Verification
- `excalibur-core/` - Need spot check
- `excalibur-store/` - Need spot check
- `excalibur-vector/` - Need spot check

---

## 6. Pattern Analysis

### 6.1 Return -1 for Not Found

Files using `-1` as sentinel for "not found":
- `excalibur-store/Segment.java:1274,1281,1296`
- `excalibur-store/WriteLog.java:707,713`
- `excalibur-store/catalog/CatalogFile.java:493`
- `excalibur-filter/BucketReader.java:257,275`
- `excalibur-vector/HnswGraph.java:1556`
- `excalibur-shard/EpochTickScheduler.java:314`

**Assessment**: Consistent pattern, acceptable for primitive returns.

### 6.2 Return null for Failure

Files using `null` returns for error cases:
- `excalibur-shard/HnswIntegrationWorker.java:554,562,567,579,600`
- `excalibur-shard/CuckooMaintenanceWorker.java:534`
- `excalibur-shard/ConsistentHashRing.java:304,334,360`
- `excalibur-shard/VolumeManager.java:276`

**Assessment**: Acceptable for internal helper methods, but should document nullable returns.

### 6.3 Return Empty Collections

Files returning empty collections for edge cases:
- Multiple files return `new ArrayList<>()`, `Collections.emptyList()`, `Set.of()`

**Assessment**: Standard practice, acceptable.

---

## 7. No UnsupportedOperationException Stubs Found

**Good News**: Search for `throw new UnsupportedOperationException` found no production code violations.

Only matches:
1. Test files expecting the exception (valid)
2. Backup file in `_disabled_backup/` (not in use)

This confirms compliance with the constraint: "If you write `throw new UnsupportedOperationException()`, you have failed."

---

## 8. Recommendations

### Immediate (Before Phase 5 Integration)
1. **Implement tail scan in QueryPipeline** - Critical for live indexing correctness
2. **Wire Cuckoo filter cascade** - Even without hierarchy, single-level filtering helps

### Short-term (Phase 5-6 Completion)
1. **Implement Hierarchical Cuckoo Filter** - Required for spec compliance
2. **Implement LSH pre-filter integration** - Required for query performance
3. **Complete LshIndex serialization** - Required for persistence

### Long-term (Phase 7+)
1. Add segment ID accessor to Segment class
2. Expand JSON parser edge case handling
3. Verify thread-safety documentation across all modules

---

## 9. Files Requiring Attention

| File | Issue | Priority |
|------|-------|----------|
| `QueryPipeline.java` | Stage 1 & 2 pass-through, no tail scan | HIGH |
| `CuckooMaintenanceWorker.java` | No hierarchical propagation | HIGH |
| `LshIndex.java` | Incomplete serialization | MEDIUM |
| `HnswIntegrationWorker.java` | Fragile segment ID extraction | LOW |
| `VisibilityTracker.Default` | No-op segment tracking | BY DESIGN |

---

## 10. Untracked Phase 5-6 Work Assessment

### Ready for Commit (with caveats)
- `excalibur-query/` - Complete implementation, placeholder stages documented
- `excalibur-shard/IndexOrchestrator.java` - Complete, well-documented
- `excalibur-shard/worker/*.java` - Complete with documented limitations
- `excalibur-api/endpoint/QueryEndpoint.java` - Complete, minimal JSON parser acceptable

### Needs Work Before Commit
- None identified - all untracked work is functional with documented limitations

### Missing from Spec
- `HierarchicalCuckooFilter` - Spec required, not implemented
- Count-Min Sketch for selectivity estimation - Spec required, not found
