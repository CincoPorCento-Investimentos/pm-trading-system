package com.cryptohft.common.domain;

import lombok.Getter;
import org.agrona.collections.Long2ObjectHashMap;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.locks.StampedLock;

/**
 * High-performance order book implementation using primitive collections
 * and lock-free reads where possible.
 */
public class OrderBook {
    
    @Getter
    private final String symbol;
    
    @Getter
    private final String exchange;
    
    // Price levels stored as long (price * 10^8 for precision)
    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();
    
    // Order ID to price level mapping for fast cancellation
    private final Long2ObjectHashMap<PriceLevel> orderToPriceLevel = new Long2ObjectHashMap<>();
    
    private final StampedLock lock = new StampedLock();
    
    private volatile long sequenceNumber = 0;
    private volatile long lastUpdateNanos = 0;
    
    private static final long PRICE_MULTIPLIER = 100_000_000L; // 10^8
    
    public OrderBook(String symbol, String exchange) {
        this.symbol = symbol;
        this.exchange = exchange;
    }
    
    /**
     * Add or update a price level (for snapshot/delta updates).
     */
    public void updateLevel(Order.Side side, BigDecimal price, BigDecimal quantity) {
        long priceKey = priceToLong(price);
        long stamp = lock.writeLock();
        try {
            TreeMap<Long, PriceLevel> levels = side == Order.Side.BUY ? bids : asks;
            
            if (quantity.compareTo(BigDecimal.ZERO) == 0) {
                levels.remove(priceKey);
            } else {
                PriceLevel level = levels.computeIfAbsent(priceKey, 
                    k -> new PriceLevel(price, side));
                level.setTotalQuantity(quantity);
            }
            
            sequenceNumber++;
            lastUpdateNanos = System.nanoTime();
        } finally {
            lock.unlockWrite(stamp);
        }
    }
    
    /**
     * Get best bid price.
     */
    public BigDecimal getBestBid() {
        long stamp = lock.tryOptimisticRead();
        BigDecimal result = bids.isEmpty() ? null : bids.firstEntry().getValue().getPrice();
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                result = bids.isEmpty() ? null : bids.firstEntry().getValue().getPrice();
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return result;
    }
    
    /**
     * Get best ask price.
     */
    public BigDecimal getBestAsk() {
        long stamp = lock.tryOptimisticRead();
        BigDecimal result = asks.isEmpty() ? null : asks.firstEntry().getValue().getPrice();
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                result = asks.isEmpty() ? null : asks.firstEntry().getValue().getPrice();
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return result;
    }
    
    /**
     * Get top N price levels for bids.
     */
    public List<PriceLevel> getTopBids(int n) {
        long stamp = lock.readLock();
        try {
            return bids.values().stream()
                    .limit(n)
                    .map(PriceLevel::copy)
                    .toList();
        } finally {
            lock.unlockRead(stamp);
        }
    }
    
    /**
     * Get top N price levels for asks.
     */
    public List<PriceLevel> getTopAsks(int n) {
        long stamp = lock.readLock();
        try {
            return asks.values().stream()
                    .limit(n)
                    .map(PriceLevel::copy)
                    .toList();
        } finally {
            lock.unlockRead(stamp);
        }
    }
    
    /**
     * Get spread in basis points.
     */
    public BigDecimal getSpreadBps() {
        BigDecimal bid = getBestBid();
        BigDecimal ask = getBestAsk();
        
        if (bid != null && ask != null && bid.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal mid = bid.add(ask).divide(BigDecimal.valueOf(2), 10, java.math.RoundingMode.HALF_UP);
            return ask.subtract(bid)
                    .divide(mid, 6, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(10000));
        }
        return null;
    }
    
    /**
     * Clear the order book.
     */
    public void clear() {
        long stamp = lock.writeLock();
        try {
            bids.clear();
            asks.clear();
            orderToPriceLevel.clear();
            sequenceNumber++;
        } finally {
            lock.unlockWrite(stamp);
        }
    }
    
    public long getSequenceNumber() {
        return sequenceNumber;
    }
    
    public long getLastUpdateNanos() {
        return lastUpdateNanos;
    }
    
    private long priceToLong(BigDecimal price) {
        return price.multiply(BigDecimal.valueOf(PRICE_MULTIPLIER)).longValue();
    }
    
    /**
     * Represents a single price level in the order book.
     */
    @Getter
    public static class PriceLevel {
        private final BigDecimal price;
        private final Order.Side side;
        private BigDecimal totalQuantity;
        private int orderCount;
        
        public PriceLevel(BigDecimal price, Order.Side side) {
            this.price = price;
            this.side = side;
            this.totalQuantity = BigDecimal.ZERO;
            this.orderCount = 0;
        }
        
        public void setTotalQuantity(BigDecimal quantity) {
            this.totalQuantity = quantity;
        }
        
        public void addQuantity(BigDecimal quantity) {
            this.totalQuantity = this.totalQuantity.add(quantity);
            this.orderCount++;
        }
        
        public void removeQuantity(BigDecimal quantity) {
            this.totalQuantity = this.totalQuantity.subtract(quantity);
            this.orderCount--;
        }
        
        public PriceLevel copy() {
            PriceLevel copy = new PriceLevel(this.price, this.side);
            copy.totalQuantity = this.totalQuantity;
            copy.orderCount = this.orderCount;
            return copy;
        }
    }
}
