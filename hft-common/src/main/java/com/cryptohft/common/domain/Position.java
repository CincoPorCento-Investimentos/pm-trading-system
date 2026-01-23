package com.cryptohft.common.domain;

import lombok.Builder;
import lombok.Data;
import lombok.With;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Position tracking for a trading account.
 */
@Data
@Builder
@With
public class Position {
    
    private final String symbol;
    private final String account;
    private final String exchange;
    
    private final BigDecimal quantity;
    private final BigDecimal averageEntryPrice;
    private final BigDecimal realizedPnl;
    private final BigDecimal unrealizedPnl;
    
    private final BigDecimal notionalValue;
    private final BigDecimal marginUsed;
    private final int leverage;
    
    private final Instant openedAt;
    private final Instant updatedAt;
    
    /**
     * Calculate unrealized PnL based on current market price.
     */
    public BigDecimal calculateUnrealizedPnl(BigDecimal currentPrice) {
        if (quantity == null || averageEntryPrice == null || currentPrice == null) {
            return BigDecimal.ZERO;
        }
        return currentPrice.subtract(averageEntryPrice).multiply(quantity);
    }
    
    /**
     * Calculate percentage return.
     */
    public BigDecimal calculateReturnPct(BigDecimal currentPrice) {
        if (averageEntryPrice == null || averageEntryPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return currentPrice.subtract(averageEntryPrice)
                .divide(averageEntryPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
    
    /**
     * Check if position is long.
     */
    public boolean isLong() {
        return quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * Check if position is short.
     */
    public boolean isShort() {
        return quantity != null && quantity.compareTo(BigDecimal.ZERO) < 0;
    }
    
    /**
     * Check if position is flat.
     */
    public boolean isFlat() {
        return quantity == null || quantity.compareTo(BigDecimal.ZERO) == 0;
    }
    
    /**
     * Get absolute quantity.
     */
    public BigDecimal getAbsoluteQuantity() {
        return quantity != null ? quantity.abs() : BigDecimal.ZERO;
    }
}
