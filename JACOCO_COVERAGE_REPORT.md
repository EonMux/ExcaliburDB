# JaCoCo Coverage Report Summary

**Generated:** 2026-03-23
**Threshold:** ≥80% LINE coverage per module

## Executive Summary

| Status | Count |
|--------|-------|
| ✅ PASS (≥80%) | 3 |
| ❌ FAIL (<80%) | 6 |
| ⏭️ EXCLUDED | 4 |

---

## Module Coverage Results

### Phase 1-6 Modules (Critical Path)

| Module | Phase | Line Coverage | Branch Coverage | Status | Gap |
|--------|-------|---------------|-----------------|--------|-----|
| excalibur-core | 1 | **79.5%** | 72.0% | ❌ FAIL | -0.5% |
| excalibur-store | 2 | **79.5%** | 68.4% | ❌ FAIL | -0.5% |
| excalibur-filter | 3 | **80.7%** | 81.0% | ✅ PASS | +0.7% |
| excalibur-vector | 4 | **85.9%** | 75.0% | ✅ PASS | +5.9% |
| excalibur-shard | 5 | **50.9%** | 36.0% | ❌ FAIL | -29.1% |
| excalibur-query | 6 | **37.0%** | 32.0% | ❌ FAIL | -43.0% |

### Phase 7-9 Modules (LLM, Ingestion, API)

| Module | Phase | Line Coverage | Branch Coverage | Status | Gap |
|--------|-------|---------------|-----------------|--------|-----|
| excalibur-llm | 7 | **85.4%** | 79.7% | ✅ PASS | +5.4% |
| excalibur-ingest | 8 | **75.6%** | 61.5% | ❌ FAIL | -4.4% |
| excalibur-api | 9 | **55.8%** | 37.4% | ❌ FAIL | -24.2% |

### Excluded Modules

| Module | Reason | Line Coverage |
|--------|--------|---------------|
| excalibur-bench | JMH benchmarks (not unit-testable) | 0.0% |
| excalibur-integration | Integration tests only (no production code) | 0.0% |
| excalibur-test | Test utilities | 7.8% |
| excalibur-config | Config infrastructure | N/A |
| excalibur-metrics | Metrics utilities | N/A |

---

## Detailed Analysis

### Near-Miss Modules (0.5% below threshold)

These modules are essentially at threshold and need minimal test additions:

#### excalibur-core (79.5%)
**Low coverage packages:**
- `io.excalibur.core.report` (42.9%) - 250 missed lines
- `io.excalibur.core.exception` (58.3%) - 20 missed lines
- `io.excalibur.core.log` (72.6%) - 20 missed lines
- `io.excalibur.core.checksum` (73.7%) - 20 missed lines

**Recommendation:** Add ~30-40 lines of tests for `report` package edge cases.

#### excalibur-store (79.5%)
**Low coverage packages:**
- `io.excalibur.store.log` (69.3%) - 194 missed lines
- `io.excalibur.store` (77.0%) - 639 missed lines

**Recommendation:** Focus on write log recovery paths and segment state machine edge cases.

---

### Critical Gap Modules (Need significant work)

#### excalibur-query (37.0%) - CRITICAL
This is Phase 6 (Query Pipeline) and is 43% below threshold.

**Low coverage packages:**
- `excalibur.query.executor` (28.5%) - 472 missed lines
- `excalibur.query.pipeline` (31.7%) - 355 missed lines
- `excalibur.query.visibility` (33.7%) - 116 missed lines

**Missing coverage for:**
- Query execution paths
- Pipeline stage implementations
- Visibility epoch handling

#### excalibur-shard (50.9%) - CRITICAL
This is Phase 5 (Index Orchestration) and is 29% below threshold.

**Low coverage packages:**
- `excalibur.shard.worker` (35.9%) - 366 missed lines
- `excalibur.shard` (43.9%) - 160 missed lines

**Missing coverage for:**
- Background workers
- Segment lifecycle state transitions
- Epoch-based reclamation

#### excalibur-api (55.8%)
This is Phase 9 (API Surface).

**Missing coverage for:**
- HTTP endpoint handlers
- Request/response serialization
- Error handling paths

#### excalibur-ingest (75.6%)
This is Phase 8 (Ingestion Pipeline).

**Missing coverage for:**
- Document chunking edge cases
- Embedding client integration
- Upsert logic paths

---

## Action Items

### Immediate (to reach 80%)

1. **excalibur-core** (+0.5% needed)
   - Add tests for `io.excalibur.core.report` edge cases
   - Estimated: 1-2 test classes

2. **excalibur-store** (+0.5% needed)
   - Add tests for write log recovery scenarios
   - Estimated: 1-2 test classes

3. **excalibur-ingest** (+4.4% needed)
   - Add tests for chunking boundaries
   - Add tests for embedding failure handling
   - Estimated: 3-4 test classes

### High Priority (Critical phases)

4. **excalibur-shard** (+29.1% needed)
   - Comprehensive worker thread tests
   - Segment lifecycle state machine tests
   - Epoch visibility tests
   - Estimated: 8-10 test classes

5. **excalibur-query** (+43% needed)
   - Full pipeline integration tests
   - Executor path tests
   - Visibility epoch snapshot tests
   - Estimated: 12-15 test classes

### Medium Priority

6. **excalibur-api** (+24.2% needed)
   - HTTP endpoint tests
   - Error handling tests
   - Estimated: 5-6 test classes

---

## Summary

**Phase 1-6 Readiness:**
- ✅ Phase 3 (filter): PASS
- ✅ Phase 4 (vector): PASS
- ❌ Phase 1 (core): 0.5% below (trivial fix)
- ❌ Phase 2 (store): 0.5% below (trivial fix)
- ❌ Phase 5 (shard): 29.1% below (significant work)
- ❌ Phase 6 (query): 43% below (significant work)

**Recommendation:** Fix excalibur-core and excalibur-store immediately (trivial), then prioritize excalibur-shard and excalibur-query test additions.
