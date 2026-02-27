package com.cryptohft.api.service;

import com.cryptohft.api.dto.OrderRequest;
import com.cryptohft.api.dto.OrderResponse;
import com.cryptohft.common.domain.Order;
import com.cryptohft.common.exception.InvalidOrderStateException;
import com.cryptohft.common.exception.OrderNotFoundException;
import com.cryptohft.common.util.IdGenerator;
import com.cryptohft.common.util.NanoClock;
import com.cryptohft.engine.OrderMatchingEngine;
import com.cryptohft.engine.RiskManager;
import com.cryptohft.persistence.entity.OrderEntity;
import com.cryptohft.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for order management.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {
    
    private final OrderRepository orderRepository;
    private final RiskManager riskManager;
    private final IdGenerator idGenerator = IdGenerator.getInstance();
    
    // In-memory matching engines per symbol
    private final Map<String, OrderMatchingEngine> matchingEngines = new ConcurrentHashMap<>();
    
    /**
     * Submit a new order.
     */
    @Transactional
    public OrderResponse submitOrder(OrderRequest request) {
        long startNanos = NanoClock.nanoTime();
        
        // Generate order ID
        String orderId = idGenerator.nextIdString();
        String clientOrderId = request.getClientOrderId() != null 
                ? request.getClientOrderId() 
                : idGenerator.nextIdString();
        
        // Build order domain object
        Order order = Order.builder()
                .orderId(orderId)
                .clientOrderId(clientOrderId)
                .symbol(request.getSymbol())
                .side(request.getSide())
                .orderType(request.getOrderType())
                .timeInForce(request.getTimeInForce())
                .price(request.getPrice())
                .quantity(request.getQuantity())
                .remainingQuantity(request.getQuantity())
                .filledQuantity(BigDecimal.ZERO)
                .status(Order.OrderStatus.PENDING_NEW)
                .exchange(request.getExchange() != null ? request.getExchange() : "INTERNAL")
                .account(request.getAccount() != null ? request.getAccount() : "DEFAULT")
                .createdAt(Instant.now())
                .submittedNanos(startNanos)
                .build();
        
        // Perform risk check
        RiskManager.RiskCheckResult riskResult = riskManager.checkOrder(order);
        if (!riskResult.isAccepted()) {
            log.warn("Order rejected by risk manager: orderId={}, reason={}", 
                    orderId, riskResult.getRejectReason());
            
            return OrderResponse.builder()
                    .orderId(orderId)
                    .clientOrderId(clientOrderId)
                    .symbol(request.getSymbol())
                    .side(request.getSide())
                    .status(Order.OrderStatus.REJECTED)
                    .errorCode(riskResult.getRejectCode())
                    .errorMessage(riskResult.getRejectReason())
                    .build();
        }
        
        // Add pending exposure
        riskManager.addPendingExposure(order.getSymbol(), order.getSide(), order.getQuantity());
        
        // Persist order
        OrderEntity entity = OrderEntity.fromDomain(order.withStatus(Order.OrderStatus.NEW));
        orderRepository.save(entity);
        
        // Submit to matching engine
        OrderMatchingEngine engine = getOrCreateMatchingEngine(order.getSymbol());
        engine.submitOrder(order.withStatus(Order.OrderStatus.NEW));
        
        long latencyNanos = NanoClock.nanoTime() - startNanos;
        
        log.info("Order submitted: orderId={}, symbol={}, side={}, qty={}, latency={}µs",
                orderId, request.getSymbol(), request.getSide(), 
                request.getQuantity(), latencyNanos / 1000);
        
        return OrderResponse.builder()
                .orderId(orderId)
                .clientOrderId(clientOrderId)
                .symbol(request.getSymbol())
                .side(request.getSide())
                .orderType(request.getOrderType())
                .timeInForce(request.getTimeInForce())
                .price(request.getPrice())
                .quantity(request.getQuantity())
                .filledQuantity(BigDecimal.ZERO)
                .remainingQuantity(request.getQuantity())
                .status(Order.OrderStatus.NEW)
                .exchange(order.getExchange())
                .account(order.getAccount())
                .createdAt(order.getCreatedAt())
                .submitLatencyUs(latencyNanos / 1000)
                .build();
    }
    
    /**
     * Cancel an order.
     *
     * @throws OrderNotFoundException if the order does not exist
     * @throws InvalidOrderStateException if the order is not in a cancellable state
     */
    @Transactional
    public OrderResponse cancelOrder(String orderId, String symbol) {
        OrderEntity entity = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (!entity.getStatus().equals(Order.OrderStatus.NEW) &&
            !entity.getStatus().equals(Order.OrderStatus.PARTIALLY_FILLED)) {
            throw new InvalidOrderStateException(orderId, entity.getStatus().name(), "cancel");
        }
        
        // Remove pending exposure
        riskManager.removePendingExposure(entity.getSymbol(), entity.getSide(), entity.getRemainingQuantity());
        
        // Cancel in matching engine
        OrderMatchingEngine engine = matchingEngines.get(entity.getSymbol());
        if (engine != null) {
            engine.cancelOrder(orderId);
        }
        
        // Update database
        entity.setStatus(Order.OrderStatus.CANCELLED);
        entity.setUpdatedAt(Instant.now());
        orderRepository.save(entity);
        
        log.info("Order cancelled: orderId={}", orderId);
        
        return OrderResponse.fromOrder(entity.toDomain());
    }
    
    /**
     * Cancel multiple orders.
     */
    @Transactional
    public List<OrderResponse> cancelOrders(List<String> orderIds) {
        return orderIds.stream()
                .map(orderId -> cancelOrder(orderId, null))
                .collect(Collectors.toList());
    }
    
    /**
     * Cancel all open orders.
     */
    @Transactional
    public List<OrderResponse> cancelAllOrders(String symbol) {
        List<OrderEntity> openOrders = symbol != null 
                ? orderRepository.findOpenOrdersBySymbol(symbol)
                : orderRepository.findOpenOrders();
        
        return openOrders.stream()
                .map(entity -> cancelOrder(entity.getOrderId(), entity.getSymbol()))
                .collect(Collectors.toList());
    }
    
    /**
     * Get order by ID.
     *
     * @throws OrderNotFoundException if the order does not exist
     */
    public OrderResponse getOrder(String orderId) {
        return orderRepository.findById(orderId)
                .map(entity -> OrderResponse.fromOrder(entity.toDomain()))
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
    
    /**
     * Get orders with filters.
     */
    public List<OrderResponse> getOrders(String symbol, String status, int limit) {
        List<OrderEntity> orders;
        
        if (symbol != null && status != null) {
            Order.OrderStatus orderStatus = Order.OrderStatus.valueOf(status.toUpperCase());
            orders = orderRepository.findBySymbol(symbol).stream()
                    .filter(o -> o.getStatus() == orderStatus)
                    .limit(limit)
                    .collect(Collectors.toList());
        } else if (symbol != null) {
            orders = orderRepository.findBySymbol(symbol).stream()
                    .limit(limit)
                    .collect(Collectors.toList());
        } else if (status != null) {
            Order.OrderStatus orderStatus = Order.OrderStatus.valueOf(status.toUpperCase());
            orders = orderRepository.findByStatus(orderStatus).stream()
                    .limit(limit)
                    .collect(Collectors.toList());
        } else {
            orders = orderRepository.findAll().stream()
                    .limit(limit)
                    .collect(Collectors.toList());
        }
        
        return orders.stream()
                .map(entity -> OrderResponse.fromOrder(entity.toDomain()))
                .collect(Collectors.toList());
    }
    
    /**
     * Get open orders.
     */
    public List<OrderResponse> getOpenOrders(String symbol) {
        List<OrderEntity> openOrders = symbol != null 
                ? orderRepository.findOpenOrdersBySymbol(symbol)
                : orderRepository.findOpenOrders();
        
        return openOrders.stream()
                .map(entity -> OrderResponse.fromOrder(entity.toDomain()))
                .collect(Collectors.toList());
    }
    
    private OrderMatchingEngine getOrCreateMatchingEngine(String symbol) {
        return matchingEngines.computeIfAbsent(symbol, s -> {
            OrderMatchingEngine engine = new OrderMatchingEngine(s);
            
            // Register listeners
            engine.addTradeListener(trade -> {
                log.debug("Trade executed: {}", trade);
                riskManager.updatePosition(trade.getSymbol(), trade.getAccount(),
                        trade.getSide(), trade.getQuantity(), trade.getPrice());
            });
            
            engine.addOrderUpdateListener(order -> {
                log.debug("Order updated: {}", order);
                try {
                    orderRepository.updateFill(
                            order.getOrderId(),
                            order.getStatus(),
                            order.getFilledQuantity(),
                            order.getRemainingQuantity(),
                            order.getAveragePrice()
                    );
                } catch (Exception e) {
                    log.error("Failed to update order in database", e);
                }
            });
            
            log.info("Created matching engine for symbol: {}", s);
            return engine;
        });
    }
}
