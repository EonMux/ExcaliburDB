package io.excalibur.core.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Atomic counter that returns primitive long values.
 */
public final class AtomicLongCounter {
    private final AtomicLong counter;
    
    public AtomicLongCounter(long initialValue) {
        this.counter = new AtomicLong(initialValue);
    }
    
    public long get() {
        return counter.get();
    }
    
    public long add(long delta) {
        long result = counter.addAndGet(delta);
        if (delta > 0 && result < 0) {
            throw new IllegalArgumentException("Overflow");
        }
        if (delta < 0 && result > Long.MAX_VALUE) {
            throw new IllegalArgumentException("Overflow");
        }
        return result;
    }
    
    public long increment() {
        return add(1);
    }
    
    public long set(long value) {
        long result = counter.getAndSet(value);
        return result;
    }
}
