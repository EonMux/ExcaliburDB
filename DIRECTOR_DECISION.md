## Director Decision: Cycle 1 Implementation Plan

### Goal Progress Assessment
**Phase 1 Status**: ✅ COMPLETE (100%)
- All core primitives verified and tested
- MappedFile, XxHash64, CRC32, AtomicOps, SegmentAllocator, Codecs all working

**Remaining Bootstrap Work**: ~30% of overall PROJECT GOAL
- Bootstrap application entry point
- Configuration loading and validation
- SLF4J logging setup
- Metrics collection
- JMX MBean registration

### Convergence Check

**Selected Tasks Directly Advance PROJECT GOAL:**
1. **Bootstrap Application** - Provides entry point for ExcaliburDB; without this, no system can run
2. **Configuration Loader** - PROJECT GOAL requires config for visibility epoch, ingestion thresholds, health readiness
3. **Metrics & Health** - PROJECT GOAL explicitly requires query latency histograms, system health monitoring, and JMX MBeans

**Rejected Ideas:**
- Coordination service - REJECTED (Skeptic verdict). Visibility epochs already provide global coordination.

### Decision Summary

**Implement 3 lightweight bootstrap components in `excalibur-core`:**

1. **ConfigLoader** - Properties file loader with schema validation and environment variable overrides
2. **Metrics** - Direct metrics implementation (no framework) - query latency histogram, system health gauge, HNSW queue depth gauge
3. **BootstrapApplication** - Main entry point that initializes logging, config, metrics, and JMX MBeans

### Reasoning

**Prioritization Logic:**
- Bootstrap application is the foundation - without it, nothing else runs
- Configuration must be loaded before any other component can start
- Metrics are required by PROJECT GOAL for observability (health endpoint, JMX monitoring)

**Alignment with PROJECT GOAL:**
- "Config. Single properties file, parsed manually" → Implement lightweight loader, not full service
- "JMX MBeans for: query latency p50/p99, Cuckoo filter FP rate, HNSW queue depth" → Implement directly using SLF4J infrastructure
- "Health endpoint... ready = coverage ≥ readiness threshold" → Metrics framework provides this

**Risk Mitigation:**
- No external dependencies beyond SLF4J (already required)
- Direct metrics implementation avoids abstraction overhead
- Lightweight config loader stays within PROJECT GOAL constraints

### Implementation Tasks

**Task 1: ConfigLoader with Validation**
- Load `excalibur.properties` from classpath
- Validate required fields: `excalibur.visibility.epoch-tick-ms`, `excalibur.health.readiness-threshold`
- Apply environment variable overrides
- Return typed `Config` data class
- **Domain Correctness**: Config values must be positive integers for timing, 0.0-1.0 range for thresholds

**Task 2: Metrics Implementation**
- `LatencyHistogram` - Fixed-bucket histogram for query latency (p50/p99 accuracy)
- `HealthGauge` - System health indicator (0.0-1.0)
- `QueueGauge` - HNSW integration queue depth
- **Domain Correctness**: Histogram buckets must be monotonically increasing; gauges must be bounded 0-1; latency values must be non-negative

**Task 3: BootstrapApplication**
- Main entry point (`public static void main(String[])`)
- Load config, set up logging, register metrics, initialize JMX MBeans
- Print startup banner with coverage and epoch info
- **Domain Correctness**: Must load config without exceptions; MBean registration must succeed or fail gracefully

### Success Criteria

**Configuration Loading:**
- [ ] Loads `excalibur.properties` successfully
- [ ] Validates required fields (epoch-tick-ms > 0, readiness-threshold 0.0-1.0)
- [ ] Applies environment variable overrides correctly
- [ ] Throws `IllegalStateException` on invalid configuration

**Metrics Implementation:**
- [ ] `LatencyHistogram` records timing measurements
- [ ] `HealthGauge` returns 0.0-1.0 values
- [ ] `QueueGauge` returns non-negative values
- [ ] Histogram supports percentile calculations (p50, p99)

**JMX MBean Registration:**
- [ ] `QueryLatency` MBean registered with name `io.excalibur.query:latency`
- [ ] `SystemHealth` MBean registered with name `io.excalibur.system:health`
- [ ] `IngestionQueue` MBean registered with name `io.excalibur.ingest:queue`
- [ ] MBeans expose required attributes: `count`, `p50`, `p99`, `value`

### Next Cycle Preview

**Cycle 2: Storage Engine Foundation**
- Phase 2 (Storage Engine) depends on Phase 1 being complete
- Implement key-value storage with segment-based architecture
- Append-only write log for crash recovery
- Open addressing with Robin Hood hashing
- Zero-copy reads via MemorySegment slicing

**Verification**: Run `:excalibur-store:test` to ensure StorageEngine implementation
