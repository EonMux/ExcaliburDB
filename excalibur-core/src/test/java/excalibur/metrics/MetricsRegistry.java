/**
 * Metrics registry for testing.
 * 
 * Thread-safety: This class is thread-safe.
 */
package excalibur.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Test registry for MetricsRegistry.
 */
public final class MetricsRegistry {
    
    private final AtomicLong registeredMBeanCount = new AtomicLong(0);
    
    public MetricsRegistry() {
    }
    
    public void registerQueryLatency() {
        registeredMBeanCount.incrementAndGet();
    }
    
    public void registerCuckooFilterFPRate() {
        registeredMBeanCount.incrementAndGet();
    }
    
    public void registerHnswQueueDepth() {
        registeredMBeanCount.incrementAndGet();
    }
    
    public void registerIngestThroughput() {
        registeredMBeanCount.incrementAndGet();
    }
    
    public void registerVisibilityEpoch() {
        registeredMBeanCount.incrementAndGet();
    }
    
    public void registerTailScanSize() {
        registeredMBeanCount.incrementAndGet();
    }
    
    public long registeredMBeanCount() {
        return registeredMBeanCount.get();
    }
}
