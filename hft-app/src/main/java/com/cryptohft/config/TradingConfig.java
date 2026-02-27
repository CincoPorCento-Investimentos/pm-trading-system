package com.cryptohft.config;

import com.cryptohft.aeron.AeronConfig;
import com.cryptohft.aeron.AeronTransport;
import com.cryptohft.engine.OrderMatchingEngine;
import com.cryptohft.engine.RiskManager;
import com.cryptohft.engine.RiskProperties;
import com.cryptohft.marketdata.MarketDataConfig;
import com.cryptohft.marketdata.TcpMarketDataServer;
import com.cryptohft.marketdata.WebSocketMarketDataClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring configuration for trading components.
 */
@Slf4j
@Configuration
public class TradingConfig {

    @Value("${hft.aeron.directory:/dev/shm/aeron-hft}")
    private String aeronDirectory;

    @Value("${hft.aeron.embedded:false}")
    private boolean embeddedMediaDriver;

    @Value("${hft.aeron.enabled:false}")
    private boolean aeronEnabled;

    @Value("${hft.market-data.tcp-port:9500}")
    private int marketDataTcpPort;

    @Value("${hft.market-data.exchange:BINANCE}")
    private String exchange;

    @Value("${hft.market-data.symbols:BTCUSDT,ETHUSDT}")
    private List<String> symbols;

    private AeronTransport aeronTransport;
    private TcpMarketDataServer tcpMarketDataServer;
    private final Map<String, OrderMatchingEngine> matchingEngines = new ConcurrentHashMap<>();

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    @ConfigurationProperties(prefix = "hft.risk")
    public RiskProperties riskProperties() {
        return new RiskProperties();
    }

    @Bean
    public AeronConfig aeronConfig() {
        return AeronConfig.builder()
                .aeronDirectory(aeronDirectory)
                .embeddedMediaDriver(embeddedMediaDriver)
                .idleStrategy(AeronConfig.IdleStrategyType.YIELDING)
                .build();
    }

    @Bean
    public AeronTransport aeronTransport(AeronConfig config) {
        if (!aeronEnabled) {
            log.info("Aeron transport disabled");
            return null;
        }
        this.aeronTransport = new AeronTransport(config);
        return aeronTransport;
    }

    @Bean
    public RiskManager riskManager(RiskProperties riskProperties) {
        return new RiskManager(riskProperties);
    }

    @Bean
    public MarketDataConfig marketDataConfig() {
        return MarketDataConfig.builder()
                .exchange(exchange)
                .exchangeType(MarketDataConfig.ExchangeType.valueOf(exchange))
                .webSocketUrl(getWebSocketUrl(exchange))
                .symbols(symbols)
                .build();
    }

    @Bean
    public WebSocketMarketDataClient marketDataClient(MarketDataConfig config) {
        WebSocketMarketDataClient client = new WebSocketMarketDataClient(config);
        // Note: Connection is started by TradingService on application startup
        return client;
    }

    @Bean
    public TcpMarketDataServer tcpMarketDataServer() throws InterruptedException {
        this.tcpMarketDataServer = new TcpMarketDataServer(marketDataTcpPort);
        tcpMarketDataServer.start();
        return tcpMarketDataServer;
    }

    /**
     * Get or create matching engine for a symbol.
     */
    public OrderMatchingEngine getMatchingEngine(String symbol) {
        return matchingEngines.computeIfAbsent(symbol, s -> {
            OrderMatchingEngine engine = new OrderMatchingEngine(s);
            log.info("Created matching engine for symbol: {}", s);
            return engine;
        });
    }

    private String getWebSocketUrl(String exchange) {
        return switch (exchange.toUpperCase()) {
            case "BINANCE" -> "wss://stream.binance.com:9443/ws";
            case "COINBASE" -> "wss://ws-feed.exchange.coinbase.com";
            default -> "ws://localhost:8080/ws";
        };
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down trading components...");

        matchingEngines.values().forEach(engine -> {
            try {
                engine.close();
            } catch (Exception e) {
                log.error("Error closing matching engine", e);
            }
        });

        if (tcpMarketDataServer != null) {
            tcpMarketDataServer.close();
        }

        if (aeronTransport != null) {
            aeronTransport.close();
        }

        log.info("Trading components shutdown complete");
    }
}
