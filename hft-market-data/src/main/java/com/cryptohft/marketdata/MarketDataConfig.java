package com.cryptohft.marketdata;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Configuration for market data connections.
 */
@Data
@Builder
public class MarketDataConfig {
    
    private String exchange;
    private ExchangeType exchangeType;
    private String webSocketUrl;
    private String restApiUrl;
    private List<String> symbols;
    
    @Builder.Default
    private int reconnectDelayMs = 5000;
    
    @Builder.Default
    private int heartbeatIntervalMs = 30000;
    
    @Builder.Default
    private int bufferSize = 10000;
    
    @Builder.Default
    private boolean compression = true;
    
    public enum ExchangeType {
        BINANCE,
        COINBASE,
        GENERIC
    }
    
    /**
     * Create Binance market data config.
     */
    public static MarketDataConfig binance(List<String> symbols) {
        return MarketDataConfig.builder()
                .exchange("BINANCE")
                .exchangeType(ExchangeType.BINANCE)
                .webSocketUrl("wss://stream.binance.com:9443/ws")
                .restApiUrl("https://api.binance.com")
                .symbols(symbols)
                .build();
    }
    
    /**
     * Create Coinbase market data config.
     */
    public static MarketDataConfig coinbase(List<String> symbols) {
        return MarketDataConfig.builder()
                .exchange("COINBASE")
                .exchangeType(ExchangeType.COINBASE)
                .webSocketUrl("wss://ws-feed.exchange.coinbase.com")
                .restApiUrl("https://api.exchange.coinbase.com")
                .symbols(symbols)
                .build();
    }
    
    /**
     * Create custom/generic market data config.
     */
    public static MarketDataConfig custom(String exchange, String wsUrl, List<String> symbols) {
        return MarketDataConfig.builder()
                .exchange(exchange)
                .exchangeType(ExchangeType.GENERIC)
                .webSocketUrl(wsUrl)
                .symbols(symbols)
                .build();
    }
}
