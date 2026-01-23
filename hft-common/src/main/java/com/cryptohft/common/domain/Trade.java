package com.cryptohft.common.domain;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Trade execution record.
 */
@Data
@Builder
public class Trade {
    
    private final String tradeId;
    private final String orderId;
    private final String symbol;
    private final Order.Side side;
    
    private final BigDecimal price;
    private final BigDecimal quantity;
    private final BigDecimal commission;
    private final String commissionAsset;
    
    private final String exchange;
    private final String account;
    private final Instant executedAt;
    private final long sequenceNumber;
    
    // Counterparty info (for matched trades)
    private final String counterpartyOrderId;
    private final boolean isMaker;
    
    // Latency tracking
    private final long matchedNanos;
    private final long reportedNanos;
    
    public BigDecimal getNotionalValue() {
        return price.multiply(quantity);
    }
}
