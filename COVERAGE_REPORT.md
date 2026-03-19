# Test Coverage Report for Phases 1-4

Generated: 2026-03-19

## Coverage Summary by Module

| Module | Instruction | Branch | Line | Method | Complexity |
|--------|-------------|--------|------|--------|------------|
| excalibur-core | 54.0% (4571/8464) | 42.6% (333/782) | 52.6% (1012/1923) | 45.2% (207/458) | 37.3% |
| excalibur-store | 60.6% (11247/18567) | 51.3% (758/1479) | 59.7% (2418/4051) | 59.8% (459/767) | 48.3% |
| excalibur-filter | 68.6% (3069/4475) | 71.7% (264/368) | 68.8% (600/872) | 83.0% (117/141) | 71.7% |
| excalibur-vector | 75.1% (10521/14016) | 64.7% (1001/1546) | 77.3% (2129/2754) | 86.3% (340/394) | 61.1% |

## Aggregate Coverage

| Metric | Coverage | Details |
|--------|----------|---------|
| **Instruction** | 64.6% | 147040/227610 |
| **Line** | 64.2% | 30826/48044 |
| **Branch** | 56.4% | 11780/20875 |

## Threshold Compliance

- Required minimum: **80%**
- Current aggregate: **64.6%**
- **Status**: ❌ FAILED

## Module Analysis

### excalibur-core (Phase 1) - 54.0%
**Status**: Below threshold

Priority areas for improvement:
- Branch coverage at 42.6% - many conditional paths untested
- Method coverage at 45.2% - many public methods lack tests
- Focus on: `atomic/`, `codec/` packages

### excalibur-store (Phase 2) - 60.6%
**Status**: Below threshold

Priority areas for improvement:
- Branch coverage at 51.3% - error handling paths likely untested
- Focus on: `Segment.java`, `WriteLog.java`, `StorageEngine.java`
- Need tests for: edge cases in splitting, compaction, crash recovery

### excalibur-filter (Phase 3) - 68.6%
**Status**: Below threshold

Priority areas for improvement:
- Best branch coverage at 71.7%
- Focus on: LSH implementation, Cuckoo filter edge cases
- Need tests for: deletion correctness, false positive rate validation

### excalibur-vector (Phase 4) - 75.1%
**Status**: Below threshold (closest to target)

Priority areas for improvement:
- Best method coverage at 86.3%
- Branch coverage at 64.7% - need more edge case tests
- Focus on: HNSW graph edge cases, PQ quantization error paths

## Recommendations

### Immediate Actions Required

1. **excalibur-core**: Add tests for atomic operations and codec edge cases
2. **excalibur-store**: Add tests for segment splitting, compaction, and crash recovery
3. **excalibur-filter**: Add tests for LSH multi-probe and Cuckoo filter deletion
4. **excalibur-vector**: Add tests for HNSW graph concurrent access and PQ training

### Test Coverage Gap Analysis

The primary gaps are in:
- Error handling paths (exception branches)
- Edge cases (boundary conditions)
- Concurrent access patterns (thread safety)

### Estimated Work to Reach 80%

| Module | Gap | Estimated Tests Needed |
|--------|-----|------------------------|
| excalibur-core | 26% | ~30-40 test methods |
| excalibur-store | 19.4% | ~40-50 test methods |
| excalibur-filter | 11.4% | ~15-20 test methods |
| excalibur-vector | 4.9% | ~10-15 test methods |

## HTML Reports Location

Detailed HTML reports are available at:
- `excalibur-core/build/reports/jacoco/test/html/index.html`
- `excalibur-store/build/reports/jacoco/test/html/index.html`
- `excalibur-filter/build/reports/jacoco/test/html/index.html`
- `excalibur-vector/build/reports/jacoco/test/html/index.html`

## How to Regenerate Reports

```bash
./gradlew test jacocoTestReport
```

For specific modules:
```bash
./gradlew :excalibur-core:test :excalibur-core:jacocoTestReport
```

## Compliance with Project Constraints

The build prompt specifies:
> "Every phase includes unit tests for every public method. Cover edge cases, concurrency, and failure modes."

**Current Status**: Non-compliant

**Required Action**: Add missing tests to achieve ≥80% coverage before proceeding to Phase 5+.
