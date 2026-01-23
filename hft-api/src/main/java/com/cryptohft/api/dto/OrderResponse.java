package com.cryptohft.api.dto;

import com.cryptohft.common.domain.Order;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Order response DTO.
 */
@Data
@Builder
public class OrderResponse {
    
    private String orderId;
    private String clientOrderId;
    private String symbol;
    private Order.Side side;
    private Order.OrderType orderType;
    private Order.TimeInForce timeInForce;
    private Order.OrderStatus status;
    
    private BigDecimal price;
    private BigDecimal quantity;
    private BigDecimal filledQuantity;
    private BigDecimal remainingQuantity;
    private BigDecimal averagePrice;
    
    private String exchange;
    private String account;
    
    private Instant createdAt;
    private Instant updatedAt;
    
    private String errorCode;
    private String errorMessage;
    
    // Latency metrics (in microseconds)
    private Long submitLatencyUs;
    private Long ackLatencyUs;
    
    public static OrderResponse fromOrder(Order order) {
        return OrderResponse.builder()
                .orderId(order.getOrderId())
                .clientOrderId(order.getClientOrderId())
                .symbol(order.getSymbol())
                .side(order.getSide())
                .orderType(order.getOrderType())
                .timeInForce(order.getTimeInForce())
                .status(order.getStatus())
                .price(order.getPrice())
                .quantity(order.getQuantity())
                .filledQuantity(order.getFilledQuantity())
                .remainingQuantity(order.getRemainingQuantity())
                .averagePrice(order.getAveragePrice())
                .exchange(order.getExchange())
                .account(order.getAccount())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
    
    public static OrderResponse error(String code, String message) {
        return OrderResponse.builder()
                .status(Order.OrderStatus.REJECTED)
                .errorCode(code)
                .errorMessage(message)
                .build();
    }
}
