package com.cryptohft.fix;

import lombok.Builder;
import lombok.Data;

/**
 * Configuration for FIX protocol gateway.
 */
@Data
@Builder
public class FixConfig {
    
    // FIX version
    @Builder.Default
    private String beginString = "FIX.4.4";
    
    // Session identifiers
    private String senderCompId;
    private String targetCompId;
    
    // Connection settings
    private String host;
    private int port;
    
    // Authentication
    private String username;
    private String password;
    
    // Heartbeat and reconnect
    @Builder.Default
    private int heartbeatInterval = 30;
    
    @Builder.Default
    private int reconnectInterval = 5;
    
    // File paths for store and logs
    @Builder.Default
    private String fileStorePath = "./data/fix/store";
    
    @Builder.Default
    private String fileLogPath = "./data/fix/log";
    
    // Performance tuning
    @Builder.Default
    private boolean validateFieldsOutOfOrder = false;
    
    @Builder.Default
    private boolean validateFieldsHaveValues = true;
    
    @Builder.Default
    private boolean validateUserDefinedFields = false;
    
    @Builder.Default
    private int socketSendBufferSize = 1024 * 1024;
    
    @Builder.Default
    private int socketReceiveBufferSize = 1024 * 1024;
    
    @Builder.Default
    private boolean tcpNoDelay = true;
    
    /**
     * Create a sample configuration for testing.
     */
    public static FixConfig testConfig() {
        return FixConfig.builder()
                .senderCompId("HFT_CLIENT")
                .targetCompId("EXCHANGE")
                .host("localhost")
                .port(9876)
                .build();
    }
    
    /**
     * Create configuration for Binance FIX connection.
     */
    public static FixConfig binanceConfig(String apiKey, String secretKey) {
        return FixConfig.builder()
                .senderCompId(apiKey)
                .targetCompId("SPOT")
                .host("fix-oe.binance.com")
                .port(9000)
                .username(apiKey)
                .password(secretKey)
                .build();
    }
}
