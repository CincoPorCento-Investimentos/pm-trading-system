package com.cryptohft.common.util;

import java.time.Instant;

/**
 * High-precision clock for latency measurement.
 * Uses System.nanoTime() for relative measurements and correlates
 * with wall clock time for absolute timestamps.
 */
public final class NanoClock {
    
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long NANOS_PER_MICRO = 1_000L;
    
    // Calibration: correlate nanoTime with epochMillis at startup
    private static final long startNanos = System.nanoTime();
    private static final long startEpochMillis = System.currentTimeMillis();
    
    private NanoClock() {
        // Utility class
    }
    
    /**
     * Get current time in nanoseconds (relative, for measuring durations).
     */
    public static long nanoTime() {
        return System.nanoTime();
    }
    
    /**
     * Get current epoch time in nanoseconds (absolute).
     */
    public static long epochNanos() {
        long elapsedNanos = System.nanoTime() - startNanos;
        return (startEpochMillis * NANOS_PER_MILLI) + elapsedNanos;
    }
    
    /**
     * Get current epoch time in microseconds.
     */
    public static long epochMicros() {
        return epochNanos() / NANOS_PER_MICRO;
    }
    
    /**
     * Get current epoch time in milliseconds.
     */
    public static long epochMillis() {
        return System.currentTimeMillis();
    }
    
    /**
     * Convert nanoseconds to Instant.
     */
    public static Instant nanosToInstant(long epochNanos) {
        long seconds = epochNanos / NANOS_PER_SECOND;
        int nanos = (int) (epochNanos % NANOS_PER_SECOND);
        return Instant.ofEpochSecond(seconds, nanos);
    }
    
    /**
     * Convert Instant to nanoseconds.
     */
    public static long instantToNanos(Instant instant) {
        return instant.getEpochSecond() * NANOS_PER_SECOND + instant.getNano();
    }
    
    /**
     * Calculate duration in nanoseconds.
     */
    public static long durationNanos(long startNanos, long endNanos) {
        return endNanos - startNanos;
    }
    
    /**
     * Calculate duration in microseconds.
     */
    public static long durationMicros(long startNanos, long endNanos) {
        return (endNanos - startNanos) / NANOS_PER_MICRO;
    }
    
    /**
     * Calculate duration in milliseconds.
     */
    public static double durationMillis(long startNanos, long endNanos) {
        return (double) (endNanos - startNanos) / NANOS_PER_MILLI;
    }
    
    /**
     * Format nanoseconds as human-readable string.
     */
    public static String formatNanos(long nanos) {
        if (nanos < NANOS_PER_MICRO) {
            return nanos + "ns";
        } else if (nanos < NANOS_PER_MILLI) {
            return String.format("%.2fµs", (double) nanos / NANOS_PER_MICRO);
        } else if (nanos < NANOS_PER_SECOND) {
            return String.format("%.2fms", (double) nanos / NANOS_PER_MILLI);
        } else {
            return String.format("%.2fs", (double) nanos / NANOS_PER_SECOND);
        }
    }
}
