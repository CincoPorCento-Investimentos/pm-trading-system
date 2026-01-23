package com.cryptohft.aeron;

import lombok.Builder;
import lombok.Data;

/**
 * Configuration for Aeron messaging system.
 */
@Data
@Builder
public class AeronConfig {
    
    // Aeron directories
    @Builder.Default
    private String aeronDirectory = "/dev/shm/aeron-hft";
    
    // Media driver configuration
    @Builder.Default
    private boolean embeddedMediaDriver = true;
    
    @Builder.Default
    private boolean dirDeleteOnStart = true;
    
    @Builder.Default
    private boolean dirDeleteOnShutdown = true;
    
    // Channel configurations
    @Builder.Default
    private String marketDataChannel = "aeron:ipc";
    
    @Builder.Default
    private int marketDataStreamId = 1001;
    
    @Builder.Default
    private String orderChannel = "aeron:ipc";
    
    @Builder.Default
    private int orderStreamId = 1002;
    
    @Builder.Default
    private String executionChannel = "aeron:ipc";
    
    @Builder.Default
    private int executionStreamId = 1003;
    
    // Network channels (for distributed deployment)
    @Builder.Default
    private String networkChannel = "aeron:udp?endpoint=localhost:40123";
    
    // Buffer sizes
    @Builder.Default
    private int termBufferLength = 64 * 1024 * 1024; // 64MB
    
    @Builder.Default
    private int initialWindowLength = 1024 * 1024; // 1MB
    
    @Builder.Default
    private int mtuLength = 1408; // Optimal for typical networks
    
    // Idle strategies
    @Builder.Default
    private IdleStrategyType idleStrategy = IdleStrategyType.SPINNING;
    
    // Timeouts
    @Builder.Default
    private long publicationConnectionTimeout = 5000; // ms
    
    @Builder.Default
    private long clientLivenessTimeout = 5000; // ms
    
    public enum IdleStrategyType {
        SPINNING,       // Lowest latency, highest CPU
        YIELDING,       // Low latency, high CPU
        SLEEPING,       // Medium latency, low CPU
        BACK_OFF        // Adaptive
    }
    
    public static AeronConfig defaultConfig() {
        return AeronConfig.builder().build();
    }
    
    public static AeronConfig lowLatencyConfig() {
        return AeronConfig.builder()
                .idleStrategy(IdleStrategyType.SPINNING)
                .termBufferLength(128 * 1024 * 1024)
                .initialWindowLength(2 * 1024 * 1024)
                .build();
    }
    
    public static AeronConfig networkConfig(String host, int basePort) {
        return AeronConfig.builder()
                .embeddedMediaDriver(true)
                .marketDataChannel("aeron:udp?endpoint=" + host + ":" + basePort)
                .orderChannel("aeron:udp?endpoint=" + host + ":" + (basePort + 1))
                .executionChannel("aeron:udp?endpoint=" + host + ":" + (basePort + 2))
                .build();
    }
}
