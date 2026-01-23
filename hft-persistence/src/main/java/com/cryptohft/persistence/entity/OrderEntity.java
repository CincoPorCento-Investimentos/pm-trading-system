package com.cryptohft.persistence.entity;

import com.cryptohft.common.domain.Order;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity for orders.
 */
@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_symbol", columnList = "symbol"),
        @Index(name = "idx_orders_account", columnList = "account"),
        @Index(name = "idx_orders_status", columnList = "status"),
        @Index(name = "idx_orders_created", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEntity {
    
    @Id
    @Column(name = "order_id", length = 64)
    private String orderId;
    
    @Column(name = "client_order_id", length = 64)
    private String clientOrderId;
    
    @Column(name = "symbol", length = 32, nullable = false)
    private String symbol;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "side", length = 10, nullable = false)
    private Order.Side side;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", length = 20, nullable = false)
    private Order.OrderType orderType;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "time_in_force", length = 10)
    private Order.TimeInForce timeInForce;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private Order.OrderStatus status;
    
    @Column(name = "price", precision = 24, scale = 8)
    private BigDecimal price;
    
    @Column(name = "quantity", precision = 24, scale = 8, nullable = false)
    private BigDecimal quantity;
    
    @Column(name = "filled_quantity", precision = 24, scale = 8)
    private BigDecimal filledQuantity;
    
    @Column(name = "remaining_quantity", precision = 24, scale = 8)
    private BigDecimal remainingQuantity;
    
    @Column(name = "average_price", precision = 24, scale = 8)
    private BigDecimal averagePrice;
    
    @Column(name = "exchange", length = 32)
    private String exchange;
    
    @Column(name = "account", length = 64)
    private String account;
    
    @Column(name = "sequence_number")
    private Long sequenceNumber;
    
    @Column(name = "submitted_nanos")
    private Long submittedNanos;
    
    @Column(name = "acknowledged_nanos")
    private Long acknowledgedNanos;
    
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
    
    /**
     * Convert to domain object.
     */
    public Order toDomain() {
        return Order.builder()
                .orderId(orderId)
                .clientOrderId(clientOrderId)
                .symbol(symbol)
                .side(side)
                .orderType(orderType)
                .timeInForce(timeInForce)
                .status(status)
                .price(price)
                .quantity(quantity)
                .filledQuantity(filledQuantity)
                .remainingQuantity(remainingQuantity)
                .averagePrice(averagePrice)
                .exchange(exchange)
                .account(account)
                .sequenceNumber(sequenceNumber != null ? sequenceNumber : 0)
                .submittedNanos(submittedNanos != null ? submittedNanos : 0)
                .acknowledgedNanos(acknowledgedNanos != null ? acknowledgedNanos : 0)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }
    
    /**
     * Create from domain object.
     */
    public static OrderEntity fromDomain(Order order) {
        return OrderEntity.builder()
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
                .sequenceNumber(order.getSequenceNumber())
                .submittedNanos(order.getSubmittedNanos())
                .acknowledgedNanos(order.getAcknowledgedNanos())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
}
