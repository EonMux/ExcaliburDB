package com.excaliburdb.integration;

/**
 * Benchmark Module (Phase 10)
 * 
 * Provides JMH benchmarks for measuring performance of ExcaliburDB components.
 * 
 * Benchmark Categories:
 * - Storage Benchmarks: Point lookup throughput (target ≥1B/sec)
 * - Filter Benchmarks: Cuckoo filter lookup/deletion throughput and FP rate
 * - Vector Benchmarks: HNSW query throughput, distance computation speed
 * - Distance Benchmarks: Cosine/L2/IP with Vector API
 * - Tail Scan Benchmarks: Brute-force scan throughput
 * - End-to-End Benchmarks: Query latency p50/p99
 * 
 * @author ExcaliburDB Team
 * @since 1.0.0
 */
package com.excaliburdb.integration;
