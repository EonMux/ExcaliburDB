# Phase 10: JMH Benchmarks Reference

This document provides the correct locations for all Phase 10 JMH benchmarks.

## Benchmark Location Reference

### Storage Benchmarks

| Benchmark | Location | Gradle Task | Target |
|-----------|----------|-------------|--------|
| Segment Lookup | `excalibur-bench/src/main/java/excalibur/bench/benchmark/SegmentBenchmark.java` | `runBenchmark` | ≥1B lookups/sec |
| MappedFile I/O | `excalibur-bench/src/main/java/excalibur/bench/benchmark/MappedFileBenchmark.java` | `runMappedFileBenchmark` | Read/write throughput |
| Hash Function | `excalibur-bench/src/main/java/excalibur/bench/benchmark/HashBenchmark.java` | `runHashBenchmark` | xxHash64 speed |

### Cuckoo Filter Benchmarks

| Benchmark | Location | Gradle Task | Target |
|-----------|----------|-------------|--------|
| Cuckoo Lookup | `excalibur-bench/src/main/java/excalibur/bench/benchmark/CuckooFilterBenchmark.java` | `runCuckooFilterBenchmark` | Lookup throughput |
| Cuckoo Insert | `excalibur-bench/src/main/java/excalibur/bench/benchmark/CuckooFilterInsertBenchmark.java` | `runCuckooFilterInsertBenchmark` | Insert throughput |
| Cuckoo Delete | `excalibur-bench/src/main/java/excalibur/bench/benchmark/CuckooFilterDeleteBenchmark.java` | `runCuckooFilterDeleteBenchmark` | Delete throughput |
| Cuckoo Memory-Mapped | `excalibur-bench/src/main/java/excalibur/bench/benchmark/CuckooFilterMemoryMappedBenchmark.java` | `runCuckooFilterMemoryMappedBenchmark` | Mapped I/O throughput |
| Cuckoo FP Rate | `excalibur-bench/src/main/java/excalibur/bench/benchmark/CuckooFilterFPRateBenchmark.java` | `runCuckooFilterFPRateBenchmark` | FP rate ≤0.1% |

### HNSW Benchmarks

| Benchmark | Location | Gradle Task | Target |
|-----------|----------|-------------|--------|
| **HNSW Recall@10** | `excalibur-bench/src/main/java/excalibur/bench/benchmark/HnswRecallBenchmark.java` | `runHnswRecallBenchmark` | **≥99% recall@10** |
| HNSW Recall Runner | `excalibur-bench/src/main/java/excalibur/bench/HnswRecallBenchmarkRunner.java` | (same task) | Runner class |

**IMPORTANT**: The HNSW Recall benchmark tests:
- Recall@10 at varying efSearch values (100, 200, 300)
- Uses `HnswGraph.search()` for vector similarity queries
- Computes ground truth via brute force
- Target: ≥99% recall@10 with default configuration (M=16, efConstruction=200, efSearch=200)

### Vector Distance Benchmarks

| Benchmark | Location | Gradle Task | Target |
|-----------|----------|-------------|--------|
| Distance Functions | `excalibur-bench/src/main/java/excalibur/bench/benchmark/DistanceBenchmark.java` | `runDistanceBenchmark` | Cosine/L2/IP throughput |
| Vector Layout | `excalibur-bench/src/main/java/excalibur/bench/benchmark/VectorLayoutBenchmark.java` | `runVectorLayoutBenchmark` | SoA vs AoS comparison |

### Query Pipeline Benchmarks

| Benchmark | Location | Gradle Task | Target |
|-----------|----------|-------------|--------|
| Tail Scan | `excalibur-bench/src/main/java/excalibur/bench/benchmark/TailScanBenchmarkRunner.java` | `runTailScanBenchmark` | Brute-force throughput |
| End-to-End Query | `excalibur-bench/src/main/java/excalibur/bench/benchmark/EndToEndQueryBenchmarkRunner.java` | `runEndToEndQueryBenchmark` | p50/p99 latency |

## Running Benchmarks

```bash
# Run all benchmarks
./gradlew :excalibur-bench:jmh

# Run specific benchmarks
./gradlew :excalibur-bench:runHnswRecallBenchmark
./gradlew :excalibur-bench:runCuckooFilterBenchmark
./gradlew :excalibur-bench:runDistanceBenchmark
./gradlew :excalibur-bench:runBenchmark  # Storage segment lookup
```

## Common Mistakes to Avoid

1. **Wrong location for HNSW benchmark**: The HNSW benchmark is NOT at `excalibur-bench/src/test/java/excalibur/bench/SegmentBenchmarkRunner.java` - that's a storage benchmark. The correct location is `excalibur-bench/src/main/java/excalibur/bench/benchmark/HnswRecallBenchmark.java`.

2. **Confusing SegmentBenchmark with HNSW**: `SegmentBenchmark` tests key-value hash table lookups (storage), not vector search. Use `HnswRecallBenchmark` for vector search benchmarks.

## Benchmark Structure

Each benchmark follows JMH conventions:
- `@BenchmarkMode(Mode.AverageTime)` or `Mode.Throughput`
- `@Warmup` iterations for JIT warmup
- `@Measurement` iterations for actual measurement
- `@Fork` for process isolation
- JVM args include `--enable-preview` and `--enable-native-access=ALL-UNNAMED` for Panama FFM
