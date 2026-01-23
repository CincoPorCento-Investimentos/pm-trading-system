package com.cryptohft.persistence.entity;

import com.cryptohft.common.domain.Order;
import com.cryptohft.common.domain.Trade;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity for trades.
 */
@Entity
@Table(name = "trades", indexes = {
        @Index(name = "idx_trades_order_id", columnList = "order_id"),
        @Index(name = "idx_trades_symbol", columnList = "symbol"),
        @Index(name = "idx_trades_account", columnList = "account"),
        @Index(name = "idx_trades_executed", columnList = "executed_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeEntity {
    
    @Id
    @Column(name = "trade_id", length = 64)
    private String tradeId;
    
    @Column(name = "order_id", length = 64, nullable = false)
    private String orderId;
    
    @Column(name = "symbol", length = 32, nullable = false)
    private String symbol;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "side", length = 10, nullable = false)
    private Order.Side side;
    
    @Column(name = "price", precision = 24, scale = 8, nullable = false)
    private BigDecimal price;
    
    @Column(name = "quantity", precision = 24, scale = 8, nullable = false)
    private BigDecimal quantity;
    
    @Column(name = "commission", precision = 24, scale = 8)
    private BigDecimal commission;
    
    @Column(name = "commission_asset", length = 16)
    private String commissionAsset;
    
    @Column(name = "exchange", length = 32)
    private String exchange;
    
    @Column(name = "account", length = 64)
    private String account;
    
    @Column(name = "counterparty_order_id", length = 64)
    private String counterpartyOrderId;
    
    @Column(name = "is_maker")
    private Boolean isMaker;
    
    @Column(name = "sequence_number")
    private Long sequenceNumber;
    
    @Column(name = "matched_nanos")
    private Long matchedNanos;
    
    @Column(name = "reported_nanos")
    private Long reportedNanos;
    
    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;
    
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    
    /**
     * Convert to domain object.
     */
    public Trade toDomain() {
        return Trade.builder()
                .tradeId(tradeId)
                .orderId(orderId)
                .symbol(symbol)
                .side(side)
                .price(price)
                .quantity(quantity)
                .commission(commission)
                .commissionAsset(commissionAsset)
                .exchange(exchange)
                .account(account)
                .counterpartyOrderId(counterpartyOrderId)
                .isMaker(isMaker != null && isMaker)
                .sequenceNumber(sequenceNumber != null ? sequenceNumber : 0)
                .matchedNanos(matchedNanos != null ? matchedNanos : 0)
                .reportedNanos(reportedNanos != null ? reportedNanos : 0)
                .executedAt(executedAt)
                .build();
    }
    
    /**
     * Create from domain object.
     */
    public static TradeEntity fromDomain(Trade trade) {
        return TradeEntity.builder()
                .tradeId(trade.getTradeId())
                .orderId(trade.getOrderId())
                .symbol(trade.getSymbol())
                .side(trade.getSide())
                .price(trade.getPrice())
                .quantity(trade.getQuantity())
                .commission(trade.getCommission())
                .commissionAsset(trade.getCommissionAsset())
                .exchange(trade.getExchange())
                .account(trade.getAccount())
                .counterpartyOrderId(trade.getCounterpartyOrderId())
                .isMaker(trade.isMaker())
                .sequenceNumber(trade.getSequenceNumber())
                .matchedNanos(trade.getMatchedNanos())
                .reportedNanos(trade.getReportedNanos())
                .executedAt(trade.getExecutedAt())
                .build();
    }
}
