package com.cryptohft.marketdata;

import com.cryptohft.common.domain.MarketData;
import com.cryptohft.common.domain.OrderBook;
import com.cryptohft.common.util.NanoClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * WebSocket client for connecting to cryptocurrency exchange market data feeds.
 * Supports Binance, Coinbase, and generic WebSocket endpoints.
 */
@Slf4j
public class WebSocketMarketDataClient implements AutoCloseable {
    
    private final MarketDataConfig config;
    private final ObjectMapper objectMapper;
    private final Map<String, OrderBook> orderBooks = new ConcurrentHashMap<>();
    private final List<Consumer<MarketData>> tickListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<OrderBook>> bookListeners = new CopyOnWriteArrayList<>();
    
    private WebSocketClient webSocketClient;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong lastMessageNanos = new AtomicLong(0);
    
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> reconnectTask;
    
    private static final int RECONNECT_DELAY_MS = 5000;
    private static final int HEARTBEAT_INTERVAL_MS = 30000;
    
    public WebSocketMarketDataClient(MarketDataConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "market-data-scheduler");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Connect to the WebSocket endpoint.
     */
    public void connect() {
        try {
            URI uri = new URI(config.getWebSocketUrl());
            
            webSocketClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    connected.set(true);
                    log.info("WebSocket connected to {}", config.getWebSocketUrl());
                    
                    // Subscribe to configured symbols
                    for (String symbol : config.getSymbols()) {
                        subscribe(symbol);
                    }
                    
                    // Start heartbeat
                    startHeartbeat();
                }
                
                @Override
                public void onMessage(String message) {
                    long receivedNanos = NanoClock.nanoTime();
                    messageCount.incrementAndGet();
                    lastMessageNanos.set(receivedNanos);
                    
                    try {
                        processMessage(message, receivedNanos);
                    } catch (Exception e) {
                        log.error("Error processing message: {}", message, e);
                    }
                }
                
                @Override
                public void onClose(int code, String reason, boolean remote) {
                    connected.set(false);
                    log.warn("WebSocket closed: code={}, reason={}, remote={}", code, reason, remote);
                    
                    // Schedule reconnect
                    scheduleReconnect();
                }
                
                @Override
                public void onError(Exception ex) {
                    log.error("WebSocket error", ex);
                }
            };
            
            webSocketClient.connect();
            
        } catch (Exception e) {
            log.error("Failed to connect to WebSocket", e);
            scheduleReconnect();
        }
    }
    
    /**
     * Subscribe to market data for a symbol.
     */
    public void subscribe(String symbol) {
        if (!connected.get()) {
            log.warn("Cannot subscribe, not connected");
            return;
        }
        
        // Create subscription message based on exchange type
        String subscribeMessage = createSubscriptionMessage(symbol);
        webSocketClient.send(subscribeMessage);
        
        // Initialize order book
        orderBooks.computeIfAbsent(symbol, s -> new OrderBook(s, config.getExchange()));
        
        log.info("Subscribed to market data for {}", symbol);
    }
    
    /**
     * Unsubscribe from market data for a symbol.
     */
    public void unsubscribe(String symbol) {
        if (!connected.get()) {
            return;
        }
        
        String unsubscribeMessage = createUnsubscriptionMessage(symbol);
        webSocketClient.send(unsubscribeMessage);
        
        orderBooks.remove(symbol);
        log.info("Unsubscribed from market data for {}", symbol);
    }
    
    /**
     * Add a tick data listener.
     */
    public void addTickListener(Consumer<MarketData> listener) {
        tickListeners.add(listener);
    }
    
    /**
     * Add an order book listener.
     */
    public void addBookListener(Consumer<OrderBook> listener) {
        bookListeners.add(listener);
    }
    
    /**
     * Get order book for a symbol.
     */
    public OrderBook getOrderBook(String symbol) {
        return orderBooks.get(symbol);
    }
    
    /**
     * Check if connected.
     */
    public boolean isConnected() {
        return connected.get();
    }
    
    /**
     * Get message count since connection.
     */
    public long getMessageCount() {
        return messageCount.get();
    }
    
    /**
     * Process incoming WebSocket message.
     */
    private void processMessage(String message, long receivedNanos) throws Exception {
        JsonNode root = objectMapper.readTree(message);
        
        // Handle different message formats based on exchange
        switch (config.getExchangeType()) {
            case BINANCE -> processBinanceMessage(root, receivedNanos);
            case COINBASE -> processCoinbaseMessage(root, receivedNanos);
            case GENERIC -> processGenericMessage(root, receivedNanos);
        }
    }
    
    /**
     * Process Binance WebSocket message.
     */
    private void processBinanceMessage(JsonNode root, long receivedNanos) {
        String eventType = root.has("e") ? root.get("e").asText() : null;
        
        if ("trade".equals(eventType)) {
            // Trade/ticker update
            String symbol = root.get("s").asText();
            
            MarketData tick = MarketData.builder()
                    .symbol(symbol)
                    .exchange(config.getExchange())
                    .lastPrice(new BigDecimal(root.get("p").asText()))
                    .lastQuantity(new BigDecimal(root.get("q").asText()))
                    .timestamp(Instant.ofEpochMilli(root.get("T").asLong()))
                    .receivedNanos(receivedNanos)
                    .build();
            
            notifyTickListeners(tick);
            
        } else if ("depthUpdate".equals(eventType)) {
            // Order book update
            String symbol = root.get("s").asText();
            OrderBook book = orderBooks.get(symbol);
            
            if (book != null) {
                // Process bid updates
                if (root.has("b")) {
                    for (JsonNode bid : root.get("b")) {
                        BigDecimal price = new BigDecimal(bid.get(0).asText());
                        BigDecimal qty = new BigDecimal(bid.get(1).asText());
                        book.updateLevel(com.cryptohft.common.domain.Order.Side.BUY, price, qty);
                    }
                }
                
                // Process ask updates
                if (root.has("a")) {
                    for (JsonNode ask : root.get("a")) {
                        BigDecimal price = new BigDecimal(ask.get(0).asText());
                        BigDecimal qty = new BigDecimal(ask.get(1).asText());
                        book.updateLevel(com.cryptohft.common.domain.Order.Side.SELL, price, qty);
                    }
                }
                
                notifyBookListeners(book);
            }
        } else if ("24hrTicker".equals(eventType)) {
            // 24h ticker
            String symbol = root.get("s").asText();
            
            MarketData tick = MarketData.builder()
                    .symbol(symbol)
                    .exchange(config.getExchange())
                    .bidPrice(new BigDecimal(root.get("b").asText()))
                    .bidQuantity(new BigDecimal(root.get("B").asText()))
                    .askPrice(new BigDecimal(root.get("a").asText()))
                    .askQuantity(new BigDecimal(root.get("A").asText()))
                    .lastPrice(new BigDecimal(root.get("c").asText()))
                    .volume24h(new BigDecimal(root.get("v").asText()))
                    .high24h(new BigDecimal(root.get("h").asText()))
                    .low24h(new BigDecimal(root.get("l").asText()))
                    .open24h(new BigDecimal(root.get("o").asText()))
                    .timestamp(Instant.ofEpochMilli(root.get("E").asLong()))
                    .receivedNanos(receivedNanos)
                    .build();
            
            notifyTickListeners(tick);
        }
    }
    
    /**
     * Process Coinbase WebSocket message.
     */
    private void processCoinbaseMessage(JsonNode root, long receivedNanos) {
        String type = root.has("type") ? root.get("type").asText() : null;
        
        if ("ticker".equals(type)) {
            String symbol = root.get("product_id").asText();
            
            MarketData tick = MarketData.builder()
                    .symbol(symbol)
                    .exchange(config.getExchange())
                    .bidPrice(root.has("best_bid") ? new BigDecimal(root.get("best_bid").asText()) : null)
                    .askPrice(root.has("best_ask") ? new BigDecimal(root.get("best_ask").asText()) : null)
                    .lastPrice(root.has("price") ? new BigDecimal(root.get("price").asText()) : null)
                    .volume24h(root.has("volume_24h") ? new BigDecimal(root.get("volume_24h").asText()) : null)
                    .timestamp(Instant.parse(root.get("time").asText()))
                    .receivedNanos(receivedNanos)
                    .build();
            
            notifyTickListeners(tick);
            
        } else if ("l2update".equals(type)) {
            String symbol = root.get("product_id").asText();
            OrderBook book = orderBooks.get(symbol);
            
            if (book != null && root.has("changes")) {
                for (JsonNode change : root.get("changes")) {
                    String side = change.get(0).asText();
                    BigDecimal price = new BigDecimal(change.get(1).asText());
                    BigDecimal qty = new BigDecimal(change.get(2).asText());
                    
                    com.cryptohft.common.domain.Order.Side orderSide = 
                            "buy".equals(side) ? com.cryptohft.common.domain.Order.Side.BUY 
                                               : com.cryptohft.common.domain.Order.Side.SELL;
                    
                    book.updateLevel(orderSide, price, qty);
                }
                
                notifyBookListeners(book);
            }
        }
    }
    
    /**
     * Process generic WebSocket message (JSON format).
     */
    private void processGenericMessage(JsonNode root, long receivedNanos) {
        // Generic message format with configurable field mapping
        String symbol = root.has("symbol") ? root.get("symbol").asText() : null;
        if (symbol == null) return;
        
        MarketData.MarketDataBuilder builder = MarketData.builder()
                .symbol(symbol)
                .exchange(config.getExchange())
                .receivedNanos(receivedNanos);
        
        if (root.has("bid")) builder.bidPrice(new BigDecimal(root.get("bid").asText()));
        if (root.has("ask")) builder.askPrice(new BigDecimal(root.get("ask").asText()));
        if (root.has("last")) builder.lastPrice(new BigDecimal(root.get("last").asText()));
        if (root.has("volume")) builder.volume24h(new BigDecimal(root.get("volume").asText()));
        if (root.has("timestamp")) builder.timestamp(Instant.ofEpochMilli(root.get("timestamp").asLong()));
        
        notifyTickListeners(builder.build());
    }
    
    /**
     * Create subscription message for the exchange.
     */
    private String createSubscriptionMessage(String symbol) {
        return switch (config.getExchangeType()) {
            case BINANCE -> String.format("""
                    {"method": "SUBSCRIBE", "params": ["%s@trade", "%s@depth@100ms", "%s@ticker"], "id": 1}
                    """, symbol.toLowerCase(), symbol.toLowerCase(), symbol.toLowerCase());
            case COINBASE -> String.format("""
                    {"type": "subscribe", "product_ids": ["%s"], "channels": ["ticker", "level2"]}
                    """, symbol);
            case GENERIC -> String.format("""
                    {"action": "subscribe", "symbol": "%s"}
                    """, symbol);
        };
    }
    
    /**
     * Create unsubscription message for the exchange.
     */
    private String createUnsubscriptionMessage(String symbol) {
        return switch (config.getExchangeType()) {
            case BINANCE -> String.format("""
                    {"method": "UNSUBSCRIBE", "params": ["%s@trade", "%s@depth@100ms"], "id": 2}
                    """, symbol.toLowerCase(), symbol.toLowerCase());
            case COINBASE -> String.format("""
                    {"type": "unsubscribe", "product_ids": ["%s"], "channels": ["ticker", "level2"]}
                    """, symbol);
            case GENERIC -> String.format("""
                    {"action": "unsubscribe", "symbol": "%s"}
                    """, symbol);
        };
    }
    
    private void notifyTickListeners(MarketData tick) {
        for (Consumer<MarketData> listener : tickListeners) {
            try {
                listener.accept(tick);
            } catch (Exception e) {
                log.error("Error in tick listener", e);
            }
        }
    }
    
    private void notifyBookListeners(OrderBook book) {
        for (Consumer<OrderBook> listener : bookListeners) {
            try {
                listener.accept(book);
            } catch (Exception e) {
                log.error("Error in book listener", e);
            }
        }
    }
    
    private void startHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (connected.get()) {
                try {
                    // Send ping/pong based on exchange
                    String pingMessage = config.getExchangeType() == MarketDataConfig.ExchangeType.BINANCE
                            ? "{\"method\": \"PING\"}"
                            : "{\"type\": \"ping\"}";
                    webSocketClient.send(pingMessage);
                } catch (Exception e) {
                    log.warn("Failed to send heartbeat", e);
                }
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    private void scheduleReconnect() {
        if (reconnectTask != null && !reconnectTask.isDone()) {
            return;
        }
        
        reconnectTask = scheduler.schedule(() -> {
            log.info("Attempting to reconnect...");
            connect();
        }, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }
    
    @Override
    public void close() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
        }
        if (reconnectTask != null) {
            reconnectTask.cancel(true);
        }
        scheduler.shutdown();
        
        if (webSocketClient != null) {
            webSocketClient.close();
        }
        
        connected.set(false);
        log.info("WebSocket market data client closed");
    }
}
