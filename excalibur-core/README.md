# ExcaliburDB Core

## Overview

This module implements **Phase 1: Core Primitives** of ExcaliburDB - the foundational building blocks for the disk-backed data relevancy retrieval engine.

## Components Implemented

### 1. Configuration System (`excalibur.config`)

**Files:**
- `Config.java` - Top-level configuration data class with builder pattern
- `ConfigLoader.java` - Configuration loader with environment variable overrides
- `ConfigValidation.java` - Configuration validation utilities

**Features:**
- Properties file parsing
- Environment variable override resolution
- Schema validation with strict type checking
- Immutable configuration (read-only after loading)
- Nested configuration classes (VisibilityConfig, IngestConfig, HealthConfig)

**Thread Safety:**
- `ConfigLoader` - NOT thread-safe (loads once at startup)
- `Config` - Thread-safe for reading, immutable
- `ConfigValidation` - Thread-safe utility methods

### 2. Metrics Framework (`excalibur.metrics`)

**Files:**
- `MetricsRegistry.java` - MBean registration orchestration
- `QueryLatencyMetrics.java` - Query latency histogram metrics
- `CuckooFilterFPRateMetrics.java` - Filter false positive rate tracking
- `HnswQueueDepthMetrics.java` - HNSW integration queue depth
- `IngestThroughputMetrics.java` - Ingestion throughput measurement
- `VisibilityEpochMetrics.java` - Visibility epoch tracking
- `TailScanSizeMetrics.java` - Tail scan size monitoring
- `JmxMBean.java` - JMX MBean registration helper

**Features:**
- Latency percentile calculation (p50, p95, p99)
- Atomic counters for thread-safe metrics
- JMX MBean registration (when available)
- Peak value tracking
- Start/stop measurement support

**Thread Safety:**
- All metrics classes are thread-safe using `Atomic*` types
- `CopyOnWriteArrayList` for latency history
- `CopyOnWriteArrayList` for concurrent MBean registration

## Configuration Keys

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `excalibur.enabled` | boolean | true | Application enabled flag |
| `excalibur.visibility.epoch-tick-ms` | int | 100 | Visibility epoch tick interval |
| `excalibur.visibility.query-timeout-ms` | int | 30000 | Query timeout in ms |
| `excalibur.ingest.hnsw-queue-depth-limit` | int | 100000 | HNSW queue depth limit |
| `excalibur.ingest.document-atomicity-timeout-ms` | int | 2000 | Document atomicity timeout |
| `excalibur.health.readiness-threshold` | double | 0.5 | Health endpoint readiness threshold |
| `excalibur.health.healthy-threshold` | double | 0.9 | Health endpoint healthy threshold |
| `excalibur.health.max-latency-ms` | int | 5000 | Maximum query latency (ms) |

## Environment Variable Overrides

Configuration values can be overridden using environment variables:

```
EXCALIBUR_VISIBILITY_EPOCH_TICK_MS=50
EXCALIBUR_HEALTH_READINESS_THRESHOLD=0.8
EXCALIBUR_INGEST_HNSW_QUEUE_DEPTH_LIMIT=200000
```

## Usage Example

```java
import excalibur.config.ConfigLoader;
import excalibur.config.ConfigValidation;

// Load configuration
Config config = ConfigLoader.load("excalibur.properties", Map.of());

// Validate configuration
try {
    ConfigValidation.validate(config);
    System.out.println("Configuration validated successfully");
} catch (IllegalStateException e) {
    System.err.println("Configuration error: " + e.getMessage());
}

// Access configuration
int epochTick = config.visibilityEpochTickMs();  // 100
String baseUrl = config.llmBaseUrl();             // "https://api.openai.com/v1"
```

## Testing

### Unit Tests

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "excalibur.config.ConfigLoaderTest"

# Run tests with coverage
./gradlew test jacocoTestReport
```

### JMH Benchmarks

```bash
# Run all benchmarks
./gradlew jmh

# Run specific benchmark
./gradlew jmh --tests "MemoryMappedFileBenchmark"

# Generate benchmark report
./gradlew jmh --reports-html
```

## Build

```bash
# Build module
./gradlew :excalibur-core:build

# Build fat JAR
./gradlew :excalibur-core:jar

# Clean build
./gradlew :excalibur-core:clean
```

## Domain Correctness Requirements

### Configuration Validation

1. **Visibility Configuration:**
   - `epochTickMs` must be >= 0
   - `queryTimeoutMs` must be > 0

2. **Health Configuration:**
   - `readinessThreshold` must be in [0.0, 1.0]
   - `healthyThreshold` must be in [0.0, 1.0]
   - `maxLatencyMs` must be >= 0

3. **Ingest Configuration:**
   - `hnswQueueDepthLimit` must be >= 0
   - `documentAtomicityTimeoutMs` must be > 0

### Metrics Correctness

1. **Latency Histogram:**
   - p50, p95, p99 must be >= 0 when no data
   - Percentiles must be non-decreasing (p50 <= p95 <= p99)
   - Average must equal sum of all latencies / count

2. **Counters:**
   - Must be monotonically increasing
   - Must never be negative

3. **Gauges:**
   - Values must be in valid range
   - Must never exceed limits

## Future Extensions

- [ ] Add latency histogram buckets for finer granularity
- [ ] Add metric retention policies
- [ ] Add metric aggregation (sliding windows, rolling averages)
- [ ] Add metrics export to Prometheus
