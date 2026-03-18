package com.excaliburdb.integration;

/**
 * Integration Tests Module (Phase 10)
 * 
 * Provides comprehensive integration tests for the complete ExcaliburDB
 * system. All tests exercise multiple phases of the architecture
 * simultaneously to verify end-to-end functionality.
 * 
 * Test Categories:
 * - Full Pipeline Tests: End-to-end data flow from ingestion to query
 * - Live Indexing Tests: Concurrent ingestion and query operations
 * - Re-index Tests: Zero-downtime segment replacement
 * - Cold Start Tests: Graceful degradation with empty index
 * - LLM Unavailability Tests: Circuit breaker and fallback behavior
 * - Deletion Correctness Tests: Cuckoo filter deletion verification
 * 
 * @author ExcaliburDB Team
 * @since 1.0.0
 */
package com.excaliburdb.integration;
