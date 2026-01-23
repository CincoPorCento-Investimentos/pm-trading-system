package com.cryptohft.common.metrics;

import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;
import org.agrona.collections.Long2LongHashMap;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * High-performance latency metrics collection.
 * Uses lock-free data structures for minimal overhead.
 */
@Slf4j
public class LatencyMetrics {
    
    private final String name;
    private final MeterRegistry registry;
    
    // Histogram buckets for latency distribution (in nanoseconds)
    private static final long[] LATENCY_BUCKETS = {
            100,           // 100ns
            500,           // 500ns
            1_000,         // 1µs
            5_000,         // 5µs
            10_000,        // 10µs
            50_000,        // 50µs
            100_000,       // 100µs
            500_000,       // 500µs
            1_000_000,     // 1ms
            5_000_000,     // 5ms
            10_000_000,    // 10ms
            50_000_000,    // 50ms
            100_000_000,   // 100ms
            500_000_000,   // 500ms
            1_000_000_000  // 1s
    };
    
    private final LongAdder[] bucketCounts;
    private final LongAdder totalCount;
    private final AtomicLong totalLatency;
    private final AtomicLong minLatency;
    private final AtomicLong maxLatency;
    
    // Recent latencies for percentile calculation
    private final Long2LongHashMap recentLatencies;
    private final AtomicLong recentIndex;
    private static final int RECENT_SIZE = 10_000;
    
    private final Timer micrometerTimer;
    
    public LatencyMetrics(String name, MeterRegistry registry) {
        this.name = name;
        this.registry = registry;
        
        this.bucketCounts = new LongAdder[LATENCY_BUCKETS.length + 1];
        for (int i = 0; i < bucketCounts.length; i++) {
            bucketCounts[i] = new LongAdder();
        }
        
        this.totalCount = new LongAdder();
        this.totalLatency = new AtomicLong(0);
        this.minLatency = new AtomicLong(Long.MAX_VALUE);
        this.maxLatency = new AtomicLong(0);
        
        this.recentLatencies = new Long2LongHashMap(Long.MIN_VALUE);
        this.recentIndex = new AtomicLong(0);
        
        // Register with Micrometer
        this.micrometerTimer = Timer.builder(name)
                .description("Latency distribution for " + name)
                .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99, 0.999)
                .publishPercentileHistogram()
                .register(registry);
        
        // Register gauges
        Gauge.builder(name + ".min", minLatency, AtomicLong::get)
                .baseUnit("nanoseconds")
                .register(registry);
        
        Gauge.builder(name + ".max", maxLatency, AtomicLong::get)
                .baseUnit("nanoseconds")
                .register(registry);
    }
    
    /**
     * Record a latency measurement in nanoseconds.
     */
    public void record(long latencyNanos) {
        // Update bucket
        int bucket = findBucket(latencyNanos);
        bucketCounts[bucket].increment();
        
        // Update totals
        totalCount.increment();
        totalLatency.addAndGet(latencyNanos);
        
        // Update min/max atomically
        updateMin(latencyNanos);
        updateMax(latencyNanos);
        
        // Store in recent buffer
        long index = recentIndex.getAndIncrement() % RECENT_SIZE;
        recentLatencies.put(index, latencyNanos);
        
        // Record in Micrometer
        micrometerTimer.record(latencyNanos, TimeUnit.NANOSECONDS);
    }
    
    /**
     * Record latency between two nanoTime values.
     */
    public void recordDuration(long startNanos, long endNanos) {
        record(endNanos - startNanos);
    }
    
    /**
     * Get average latency in nanoseconds.
     */
    public double getAverageNanos() {
        long count = totalCount.sum();
        return count > 0 ? (double) totalLatency.get() / count : 0;
    }
    
    /**
     * Get minimum latency in nanoseconds.
     */
    public long getMinNanos() {
        long min = minLatency.get();
        return min == Long.MAX_VALUE ? 0 : min;
    }
    
    /**
     * Get maximum latency in nanoseconds.
     */
    public long getMaxNanos() {
        return maxLatency.get();
    }
    
    /**
     * Get total count of recorded latencies.
     */
    public long getCount() {
        return totalCount.sum();
    }
    
    /**
     * Get approximate percentile from recent samples.
     */
    public long getPercentileNanos(double percentile) {
        long count = Math.min(recentIndex.get(), RECENT_SIZE);
        if (count == 0) return 0;
        
        long[] samples = new long[(int) count];
        for (int i = 0; i < count; i++) {
            samples[i] = recentLatencies.get(i);
        }
        java.util.Arrays.sort(samples);
        
        int index = (int) Math.ceil(percentile * count) - 1;
        return samples[Math.max(0, Math.min(index, samples.length - 1))];
    }
    
    /**
     * Reset all metrics.
     */
    public void reset() {
        for (LongAdder counter : bucketCounts) {
            counter.reset();
        }
        totalCount.reset();
        totalLatency.set(0);
        minLatency.set(Long.MAX_VALUE);
        maxLatency.set(0);
        recentLatencies.clear();
        recentIndex.set(0);
    }
    
    /**
     * Get formatted summary.
     */
    public String getSummary() {
        return String.format(
                "%s: count=%d, avg=%.2fµs, min=%.2fµs, max=%.2fµs, p50=%.2fµs, p99=%.2fµs",
                name,
                getCount(),
                getAverageNanos() / 1000.0,
                getMinNanos() / 1000.0,
                getMaxNanos() / 1000.0,
                getPercentileNanos(0.50) / 1000.0,
                getPercentileNanos(0.99) / 1000.0
        );
    }
    
    private int findBucket(long latencyNanos) {
        for (int i = 0; i < LATENCY_BUCKETS.length; i++) {
            if (latencyNanos <= LATENCY_BUCKETS[i]) {
                return i;
            }
        }
        return LATENCY_BUCKETS.length;
    }
    
    private void updateMin(long value) {
        long current;
        do {
            current = minLatency.get();
            if (value >= current) return;
        } while (!minLatency.compareAndSet(current, value));
    }
    
    private void updateMax(long value) {
        long current;
        do {
            current = maxLatency.get();
            if (value <= current) return;
        } while (!maxLatency.compareAndSet(current, value));
    }
}
