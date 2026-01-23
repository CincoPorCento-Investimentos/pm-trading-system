package com.cryptohft.common.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance ID generator using Snowflake-like algorithm.
 * Generates globally unique, time-ordered 64-bit IDs.
 * 
 * Structure:
 * - 41 bits: timestamp (ms since epoch, ~69 years)
 * - 10 bits: node ID (1024 nodes)
 * - 12 bits: sequence (4096 IDs per ms per node)
 */
public final class IdGenerator {
    
    private static final long EPOCH = 1704067200000L; // 2024-01-01 00:00:00 UTC
    
    private static final long NODE_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    
    private static final long MAX_NODE_ID = ~(-1L << NODE_ID_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);
    
    private static final long NODE_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + NODE_ID_BITS;
    
    private final long nodeId;
    private final AtomicLong lastTimestamp = new AtomicLong(-1L);
    private final AtomicLong sequence = new AtomicLong(0L);
    
    // Singleton with configurable node ID
    private static volatile IdGenerator instance;
    private static final Object lock = new Object();
    
    public IdGenerator(long nodeId) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException("Node ID must be between 0 and " + MAX_NODE_ID);
        }
        this.nodeId = nodeId;
    }
    
    /**
     * Get or create singleton instance.
     */
    public static IdGenerator getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    // Default node ID based on process ID
                    long defaultNodeId = ProcessHandle.current().pid() % (MAX_NODE_ID + 1);
                    instance = new IdGenerator(defaultNodeId);
                }
            }
        }
        return instance;
    }
    
    /**
     * Initialize with specific node ID (call once at startup).
     */
    public static void initialize(long nodeId) {
        synchronized (lock) {
            instance = new IdGenerator(nodeId);
        }
    }
    
    /**
     * Generate next unique ID.
     */
    public long nextId() {
        long timestamp = currentTimestamp();
        long lastTs = lastTimestamp.get();
        
        if (timestamp < lastTs) {
            // Clock moved backwards, wait until we catch up
            timestamp = waitForNextMs(lastTs);
        }
        
        if (timestamp == lastTs) {
            long seq = sequence.incrementAndGet() & MAX_SEQUENCE;
            if (seq == 0) {
                // Sequence overflow, wait for next millisecond
                timestamp = waitForNextMs(lastTs);
            }
        } else {
            sequence.set(0);
        }
        
        lastTimestamp.set(timestamp);
        
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (nodeId << NODE_ID_SHIFT)
                | sequence.get();
    }
    
    /**
     * Generate next ID as string.
     */
    public String nextIdString() {
        return Long.toString(nextId());
    }
    
    /**
     * Generate next ID as hex string.
     */
    public String nextIdHex() {
        return Long.toHexString(nextId());
    }
    
    /**
     * Extract timestamp from ID.
     */
    public static long extractTimestamp(long id) {
        return (id >> TIMESTAMP_SHIFT) + EPOCH;
    }
    
    /**
     * Extract node ID from ID.
     */
    public static long extractNodeId(long id) {
        return (id >> NODE_ID_SHIFT) & MAX_NODE_ID;
    }
    
    /**
     * Extract sequence from ID.
     */
    public static long extractSequence(long id) {
        return id & MAX_SEQUENCE;
    }
    
    private long currentTimestamp() {
        return System.currentTimeMillis();
    }
    
    private long waitForNextMs(long lastTimestamp) {
        long timestamp = currentTimestamp();
        while (timestamp <= lastTimestamp) {
            Thread.onSpinWait();
            timestamp = currentTimestamp();
        }
        return timestamp;
    }
}
