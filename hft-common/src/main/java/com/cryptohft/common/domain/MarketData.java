package com.cryptohft.common.domain;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Market data tick representing a price update.
 */
@Data
@Builder
public class MarketData {
    
    private final String symbol;
    private final String exchange;
    
    private final BigDecimal bidPrice;
    private final BigDecimal bidQuantity;
    private final BigDecimal askPrice;
    private final BigDecimal askQuantity;
    
    private final BigDecimal lastPrice;
    private final BigDecimal lastQuantity;
    
    private final BigDecimal volume24h;
    private final BigDecimal high24h;
    private final BigDecimal low24h;
    private final BigDecimal open24h;
    
    private final Instant timestamp;
    private final long sequenceNumber;
    private final long receivedNanos;
    
    public BigDecimal getSpread() {
        if (askPrice != null && bidPrice != null) {
            return askPrice.subtract(bidPrice);
        }
        return null;
    }
    
    public BigDecimal getMidPrice() {
        if (askPrice != null && bidPrice != null) {
            return askPrice.add(bidPrice).divide(BigDecimal.valueOf(2));
        }
        return null;
    }
    
    public BigDecimal getSpreadBps() {
        BigDecimal mid = getMidPrice();
        BigDecimal spread = getSpread();
        if (mid != null && spread != null && mid.compareTo(BigDecimal.ZERO) != 0) {
            return spread.divide(mid, 6, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(10000));
        }
        return null;
    }
}
