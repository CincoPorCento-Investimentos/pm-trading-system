package com.cryptohft.common.domain;

import lombok.Builder;
import lombok.Data;
import lombok.With;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Core order domain model for the HFT system.
 * Designed for low-latency with primitive-friendly fields where possible.
 */
@Data
@Builder
@With
public class Order {
    
    private final String orderId;
    private final String clientOrderId;
    private final String symbol;
    private final Side side;
    private final OrderType orderType;
    private final TimeInForce timeInForce;
    
    private final BigDecimal price;
    private final BigDecimal quantity;
    private final BigDecimal filledQuantity;
    private final BigDecimal remainingQuantity;
    private final BigDecimal averagePrice;
    
    private final OrderStatus status;
    private final String exchange;
    private final String account;
    
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long sequenceNumber;
    
    // Latency tracking (in nanoseconds)
    private final long submittedNanos;
    private final long acknowledgedNanos;
    
    public enum Side {
        BUY(1),
        SELL(2);
        
        private final int value;
        
        Side(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
        
        public static Side fromValue(int value) {
            return switch (value) {
                case 1 -> BUY;
                case 2 -> SELL;
                default -> throw new IllegalArgumentException("Unknown side: " + value);
            };
        }
    }
    
    public enum OrderType {
        MARKET(1),
        LIMIT(2),
        STOP(3),
        STOP_LIMIT(4),
        TRAILING_STOP(5);
        
        private final int value;
        
        OrderType(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
        
        public static OrderType fromValue(int value) {
            return switch (value) {
                case 1 -> MARKET;
                case 2 -> LIMIT;
                case 3 -> STOP;
                case 4 -> STOP_LIMIT;
                case 5 -> TRAILING_STOP;
                default -> throw new IllegalArgumentException("Unknown order type: " + value);
            };
        }
    }
    
    public enum TimeInForce {
        GTC(1),  // Good Till Cancelled
        IOC(2),  // Immediate or Cancel
        FOK(3),  // Fill or Kill
        DAY(4),
        GTD(5);  // Good Till Date
        
        private final int value;
        
        TimeInForce(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
        
        public static TimeInForce fromValue(int value) {
            return switch (value) {
                case 1 -> GTC;
                case 2 -> IOC;
                case 3 -> FOK;
                case 4 -> DAY;
                case 5 -> GTD;
                default -> throw new IllegalArgumentException("Unknown time in force: " + value);
            };
        }
    }
    
    public enum OrderStatus {
        NEW(1),
        PARTIALLY_FILLED(2),
        FILLED(3),
        CANCELLED(4),
        REJECTED(5),
        EXPIRED(6),
        PENDING_NEW(7),
        PENDING_CANCEL(8);
        
        private final int value;
        
        OrderStatus(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
        
        public static OrderStatus fromValue(int value) {
            return switch (value) {
                case 1 -> NEW;
                case 2 -> PARTIALLY_FILLED;
                case 3 -> FILLED;
                case 4 -> CANCELLED;
                case 5 -> REJECTED;
                case 6 -> EXPIRED;
                case 7 -> PENDING_NEW;
                case 8 -> PENDING_CANCEL;
                default -> throw new IllegalArgumentException("Unknown order status: " + value);
            };
        }
    }
    
    public boolean isActive() {
        return status == OrderStatus.NEW || 
               status == OrderStatus.PARTIALLY_FILLED ||
               status == OrderStatus.PENDING_NEW;
    }
    
    public boolean isTerminal() {
        return status == OrderStatus.FILLED ||
               status == OrderStatus.CANCELLED ||
               status == OrderStatus.REJECTED ||
               status == OrderStatus.EXPIRED;
    }
}
