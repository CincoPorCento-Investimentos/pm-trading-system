package com.cryptohft.api.websocket;

import com.cryptohft.common.domain.MarketData;
import com.cryptohft.common.domain.Order;
import com.cryptohft.common.domain.Trade;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket handler for real-time trading updates.
 * Supports market data streaming, order updates, and trade notifications.
 */
@Slf4j
@Component
public class TradingWebSocketHandler extends TextWebSocketHandler {
    
    private final ObjectMapper objectMapper;
    
    // Session management
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final Map<String, Set<WebSocketSession>> symbolSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocketSession>> accountSubscriptions = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, Set<String>> sessionSubscriptions = new ConcurrentHashMap<>();
    
    public TradingWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        sessionSubscriptions.put(session, ConcurrentHashMap.newKeySet());
        log.info("WebSocket connected: sessionId={}, address={}", 
                session.getId(), session.getRemoteAddress());
    }
    
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        
        // Remove all subscriptions for this session
        Set<String> subs = sessionSubscriptions.remove(session);
        if (subs != null) {
            for (String key : subs) {
                Set<WebSocketSession> subscribers = symbolSubscriptions.get(key);
                if (subscribers != null) {
                    subscribers.remove(session);
                }
                subscribers = accountSubscriptions.get(key);
                if (subscribers != null) {
                    subscribers.remove(session);
                }
            }
        }
        
        log.info("WebSocket disconnected: sessionId={}, status={}", session.getId(), status);
    }
    
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        
        try {
            WebSocketMessage wsMessage = objectMapper.readValue(payload, WebSocketMessage.class);
            
            switch (wsMessage.getType()) {
                case "subscribe" -> handleSubscribe(session, wsMessage);
                case "unsubscribe" -> handleUnsubscribe(session, wsMessage);
                case "ping" -> handlePing(session);
                default -> log.warn("Unknown message type: {}", wsMessage.getType());
            }
        } catch (Exception e) {
            log.error("Error processing WebSocket message: {}", payload, e);
            sendError(session, "INVALID_MESSAGE", "Failed to process message");
        }
    }
    
    private void handleSubscribe(WebSocketSession session, WebSocketMessage message) {
        String channel = message.getChannel();
        String symbol = message.getSymbol();
        String account = message.getAccount();
        
        if ("marketdata".equals(channel) && symbol != null) {
            symbolSubscriptions.computeIfAbsent(symbol, k -> ConcurrentHashMap.newKeySet()).add(session);
            sessionSubscriptions.get(session).add(symbol);
            log.debug("Session {} subscribed to market data for {}", session.getId(), symbol);
        }
        
        if ("orders".equals(channel) && account != null) {
            accountSubscriptions.computeIfAbsent(account, k -> ConcurrentHashMap.newKeySet()).add(session);
            sessionSubscriptions.get(session).add("orders:" + account);
            log.debug("Session {} subscribed to orders for account {}", session.getId(), account);
        }
        
        if ("trades".equals(channel) && account != null) {
            accountSubscriptions.computeIfAbsent(account, k -> ConcurrentHashMap.newKeySet()).add(session);
            sessionSubscriptions.get(session).add("trades:" + account);
            log.debug("Session {} subscribed to trades for account {}", session.getId(), account);
        }
        
        // Send confirmation
        sendMessage(session, WebSocketMessage.builder()
                .type("subscribed")
                .channel(channel)
                .symbol(symbol)
                .account(account)
                .build());
    }
    
    private void handleUnsubscribe(WebSocketSession session, WebSocketMessage message) {
        String channel = message.getChannel();
        String symbol = message.getSymbol();
        String account = message.getAccount();
        
        if ("marketdata".equals(channel) && symbol != null) {
            Set<WebSocketSession> subscribers = symbolSubscriptions.get(symbol);
            if (subscribers != null) {
                subscribers.remove(session);
            }
            sessionSubscriptions.get(session).remove(symbol);
        }
        
        if (account != null) {
            Set<WebSocketSession> subscribers = accountSubscriptions.get(account);
            if (subscribers != null) {
                subscribers.remove(session);
            }
            sessionSubscriptions.get(session).remove("orders:" + account);
            sessionSubscriptions.get(session).remove("trades:" + account);
        }
        
        // Send confirmation
        sendMessage(session, WebSocketMessage.builder()
                .type("unsubscribed")
                .channel(channel)
                .symbol(symbol)
                .account(account)
                .build());
    }
    
    private void handlePing(WebSocketSession session) {
        sendMessage(session, WebSocketMessage.builder()
                .type("pong")
                .timestamp(System.currentTimeMillis())
                .build());
    }
    
    /**
     * Broadcast market data update to subscribers.
     */
    public void broadcastMarketData(MarketData data) {
        Set<WebSocketSession> subscribers = symbolSubscriptions.get(data.getSymbol());
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        
        WebSocketMessage message = WebSocketMessage.builder()
                .type("marketdata")
                .channel("marketdata")
                .symbol(data.getSymbol())
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
        
        for (WebSocketSession session : subscribers) {
            sendMessage(session, message);
        }
    }
    
    /**
     * Broadcast order update to account subscribers.
     */
    public void broadcastOrderUpdate(Order order) {
        Set<WebSocketSession> subscribers = accountSubscriptions.get(order.getAccount());
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        
        WebSocketMessage message = WebSocketMessage.builder()
                .type("order")
                .channel("orders")
                .account(order.getAccount())
                .symbol(order.getSymbol())
                .data(order)
                .timestamp(System.currentTimeMillis())
                .build();
        
        for (WebSocketSession session : subscribers) {
            sendMessage(session, message);
        }
    }
    
    /**
     * Broadcast trade update to account subscribers.
     */
    public void broadcastTrade(Trade trade) {
        Set<WebSocketSession> subscribers = accountSubscriptions.get(trade.getAccount());
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        
        WebSocketMessage message = WebSocketMessage.builder()
                .type("trade")
                .channel("trades")
                .account(trade.getAccount())
                .symbol(trade.getSymbol())
                .data(trade)
                .timestamp(System.currentTimeMillis())
                .build();
        
        for (WebSocketSession session : subscribers) {
            sendMessage(session, message);
        }
    }
    
    private void sendMessage(WebSocketSession session, WebSocketMessage message) {
        if (!session.isOpen()) {
            return;
        }
        
        try {
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.error("Failed to send WebSocket message to session {}", session.getId(), e);
        }
    }
    
    private void sendError(WebSocketSession session, String code, String message) {
        sendMessage(session, WebSocketMessage.builder()
                .type("error")
                .errorCode(code)
                .errorMessage(message)
                .timestamp(System.currentTimeMillis())
                .build());
    }
    
    /**
     * Get number of connected sessions.
     */
    public int getSessionCount() {
        return sessions.size();
    }
    
    /**
     * WebSocket message format.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class WebSocketMessage {
        private String type;
        private String channel;
        private String symbol;
        private String account;
        private Object data;
        private Long timestamp;
        private String errorCode;
        private String errorMessage;
    }
}
