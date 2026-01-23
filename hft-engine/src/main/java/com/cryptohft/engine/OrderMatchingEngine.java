package com.cryptohft.engine;

import com.cryptohft.common.domain.Order;
import com.cryptohft.common.domain.Trade;
import com.cryptohft.common.util.IdGenerator;
import com.cryptohft.common.util.NanoClock;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.BusySpinWaitStrategy;
import lombok.extern.slf4j.Slf4j;
import org.agrona.collections.Long2ObjectHashMap;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * High-performance order matching engine using LMAX Disruptor pattern.
 * Supports price-time priority matching for limit orders.
 */
@Slf4j
public class OrderMatchingEngine implements AutoCloseable {
    
    private final String symbol;
    private final IdGenerator idGenerator;
    
    // Order books: price -> list of orders at that price (FIFO)
    private final TreeMap<Long, LinkedList<OrderEntry>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, LinkedList<OrderEntry>> asks = new TreeMap<>();
    
    // Order lookup by ID
    private final Long2ObjectHashMap<OrderEntry> orderIndex = new Long2ObjectHashMap<>();
    
    // Sequence numbering
    private final AtomicLong sequenceNumber = new AtomicLong(0);
    
    // Disruptor for async event processing
    private final Disruptor<OrderEvent> disruptor;
    private final RingBuffer<OrderEvent> ringBuffer;
    
    // Event handlers
    private final List<Consumer<Trade>> tradeListeners = new ArrayList<>();
    private final List<Consumer<Order>> orderUpdateListeners = new ArrayList<>();
    
    // Price multiplier for integer arithmetic (8 decimal places)
    private static final long PRICE_MULTIPLIER = 100_000_000L;
    
    public OrderMatchingEngine(String symbol) {
        this.symbol = symbol;
        this.idGenerator = IdGenerator.getInstance();
        
        // Initialize Disruptor
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "matching-engine-" + symbol);
            t.setDaemon(true);
            return t;
        };
        
        this.disruptor = new Disruptor<>(
                OrderEvent::new,
                1024 * 64, // Ring buffer size (must be power of 2)
                threadFactory,
                ProducerType.MULTI,
                new BusySpinWaitStrategy()
        );
        
        disruptor.handleEventsWith(this::processEvent);
        this.ringBuffer = disruptor.start();
        
        log.info("Order matching engine started for symbol: {}", symbol);
    }
    
    /**
     * Submit a new order to the matching engine.
     */
    public void submitOrder(Order order) {
        long sequence = ringBuffer.next();
        try {
            OrderEvent event = ringBuffer.get(sequence);
            event.setType(OrderEvent.Type.NEW);
            event.setOrder(order);
            event.setTimestampNanos(NanoClock.nanoTime());
        } finally {
            ringBuffer.publish(sequence);
        }
    }
    
    /**
     * Cancel an existing order.
     */
    public void cancelOrder(String orderId) {
        long sequence = ringBuffer.next();
        try {
            OrderEvent event = ringBuffer.get(sequence);
            event.setType(OrderEvent.Type.CANCEL);
            event.setOrderId(orderId);
            event.setTimestampNanos(NanoClock.nanoTime());
        } finally {
            ringBuffer.publish(sequence);
        }
    }
    
    /**
     * Replace an existing order with new price/quantity.
     */
    public void replaceOrder(String orderId, BigDecimal newPrice, BigDecimal newQuantity) {
        long sequence = ringBuffer.next();
        try {
            OrderEvent event = ringBuffer.get(sequence);
            event.setType(OrderEvent.Type.REPLACE);
            event.setOrderId(orderId);
            event.setNewPrice(newPrice);
            event.setNewQuantity(newQuantity);
            event.setTimestampNanos(NanoClock.nanoTime());
        } finally {
            ringBuffer.publish(sequence);
        }
    }
    
    /**
     * Process an order event from the ring buffer.
     */
    private void processEvent(OrderEvent event, long sequence, boolean endOfBatch) {
        try {
            switch (event.getType()) {
                case NEW -> processNewOrder(event.getOrder());
                case CANCEL -> processCancelOrder(event.getOrderId());
                case REPLACE -> processReplaceOrder(event.getOrderId(), 
                        event.getNewPrice(), event.getNewQuantity());
            }
        } catch (Exception e) {
            log.error("Error processing order event", e);
        }
    }
    
    /**
     * Process a new order - attempt matching then add to book.
     */
    private void processNewOrder(Order order) {
        long seqNum = sequenceNumber.incrementAndGet();
        
        // Create order entry
        OrderEntry entry = new OrderEntry(
                Long.parseLong(order.getOrderId()),
                order,
                priceToLong(order.getPrice()),
                order.getQuantity(),
                NanoClock.nanoTime()
        );
        
        // Match against opposite side
        BigDecimal remainingQty = matchOrder(entry);
        
        // If order not fully filled and is a limit order, add to book
        if (remainingQty.compareTo(BigDecimal.ZERO) > 0 && 
            order.getOrderType() == Order.OrderType.LIMIT) {
            
            entry.setRemainingQuantity(remainingQty);
            addToBook(entry);
            
            // Notify listeners of partial fill or new order
            Order.OrderStatus status = entry.getRemainingQuantity().compareTo(order.getQuantity()) < 0
                    ? Order.OrderStatus.PARTIALLY_FILLED
                    : Order.OrderStatus.NEW;
            
            notifyOrderUpdate(order.withStatus(status)
                    .withFilledQuantity(order.getQuantity().subtract(remainingQty))
                    .withRemainingQuantity(remainingQty)
                    .withSequenceNumber(seqNum));
        } else if (remainingQty.compareTo(BigDecimal.ZERO) == 0) {
            // Fully filled
            notifyOrderUpdate(order.withStatus(Order.OrderStatus.FILLED)
                    .withFilledQuantity(order.getQuantity())
                    .withRemainingQuantity(BigDecimal.ZERO)
                    .withSequenceNumber(seqNum));
        } else {
            // Market order with no liquidity - reject
            notifyOrderUpdate(order.withStatus(Order.OrderStatus.REJECTED)
                    .withSequenceNumber(seqNum));
        }
    }
    
    /**
     * Match an order against the opposite side of the book.
     */
    private BigDecimal matchOrder(OrderEntry aggressor) {
        TreeMap<Long, LinkedList<OrderEntry>> oppositeBook = 
                aggressor.getOrder().getSide() == Order.Side.BUY ? asks : bids;
        
        BigDecimal remainingQty = aggressor.getRemainingQuantity();
        
        Iterator<Map.Entry<Long, LinkedList<OrderEntry>>> priceIterator = oppositeBook.entrySet().iterator();
        
        while (priceIterator.hasNext() && remainingQty.compareTo(BigDecimal.ZERO) > 0) {
            Map.Entry<Long, LinkedList<OrderEntry>> priceLevel = priceIterator.next();
            
            // Check price constraint for limit orders
            if (aggressor.getOrder().getOrderType() == Order.OrderType.LIMIT) {
                if (aggressor.getOrder().getSide() == Order.Side.BUY && 
                    priceLevel.getKey() > aggressor.getPriceKey()) {
                    break; // Ask price too high
                }
                if (aggressor.getOrder().getSide() == Order.Side.SELL && 
                    priceLevel.getKey() < aggressor.getPriceKey()) {
                    break; // Bid price too low
                }
            }
            
            LinkedList<OrderEntry> ordersAtPrice = priceLevel.getValue();
            Iterator<OrderEntry> orderIterator = ordersAtPrice.iterator();
            
            while (orderIterator.hasNext() && remainingQty.compareTo(BigDecimal.ZERO) > 0) {
                OrderEntry passive = orderIterator.next();
                
                // Calculate fill quantity
                BigDecimal fillQty = remainingQty.min(passive.getRemainingQuantity());
                BigDecimal fillPrice = longToPrice(passive.getPriceKey());
                
                // Execute trade
                executeTrade(aggressor, passive, fillPrice, fillQty);
                
                remainingQty = remainingQty.subtract(fillQty);
                passive.setRemainingQuantity(passive.getRemainingQuantity().subtract(fillQty));
                
                // Remove fully filled order
                if (passive.getRemainingQuantity().compareTo(BigDecimal.ZERO) == 0) {
                    orderIterator.remove();
                    orderIndex.remove(passive.getOrderIdLong());
                    
                    notifyOrderUpdate(passive.getOrder()
                            .withStatus(Order.OrderStatus.FILLED)
                            .withFilledQuantity(passive.getOrder().getQuantity())
                            .withRemainingQuantity(BigDecimal.ZERO));
                } else {
                    notifyOrderUpdate(passive.getOrder()
                            .withStatus(Order.OrderStatus.PARTIALLY_FILLED)
                            .withFilledQuantity(passive.getOrder().getQuantity()
                                    .subtract(passive.getRemainingQuantity()))
                            .withRemainingQuantity(passive.getRemainingQuantity()));
                }
            }
            
            // Remove empty price level
            if (ordersAtPrice.isEmpty()) {
                priceIterator.remove();
            }
        }
        
        return remainingQty;
    }
    
    /**
     * Execute a trade between two orders.
     */
    private void executeTrade(OrderEntry aggressor, OrderEntry passive, 
                              BigDecimal price, BigDecimal quantity) {
        long tradeId = idGenerator.nextId();
        long execNanos = NanoClock.nanoTime();
        
        // Create trade for aggressor (taker)
        Trade aggressorTrade = Trade.builder()
                .tradeId(String.valueOf(tradeId))
                .orderId(aggressor.getOrder().getOrderId())
                .symbol(symbol)
                .side(aggressor.getOrder().getSide())
                .price(price)
                .quantity(quantity)
                .commission(BigDecimal.ZERO) // Calculate based on fee structure
                .exchange(aggressor.getOrder().getExchange())
                .account(aggressor.getOrder().getAccount())
                .executedAt(Instant.now())
                .counterpartyOrderId(passive.getOrder().getOrderId())
                .isMaker(false)
                .matchedNanos(execNanos)
                .build();
        
        // Create trade for passive (maker)
        Trade passiveTrade = Trade.builder()
                .tradeId(String.valueOf(tradeId + 1))
                .orderId(passive.getOrder().getOrderId())
                .symbol(symbol)
                .side(passive.getOrder().getSide())
                .price(price)
                .quantity(quantity)
                .commission(BigDecimal.ZERO)
                .exchange(passive.getOrder().getExchange())
                .account(passive.getOrder().getAccount())
                .executedAt(Instant.now())
                .counterpartyOrderId(aggressor.getOrder().getOrderId())
                .isMaker(true)
                .matchedNanos(execNanos)
                .build();
        
        // Notify listeners
        notifyTrade(aggressorTrade);
        notifyTrade(passiveTrade);
        
        log.debug("Trade executed: {} {} @ {} (aggressor={}, passive={})",
                quantity, symbol, price, 
                aggressor.getOrder().getOrderId(), 
                passive.getOrder().getOrderId());
    }
    
    /**
     * Add an order to the order book.
     */
    private void addToBook(OrderEntry entry) {
        TreeMap<Long, LinkedList<OrderEntry>> book = 
                entry.getOrder().getSide() == Order.Side.BUY ? bids : asks;
        
        book.computeIfAbsent(entry.getPriceKey(), k -> new LinkedList<>()).add(entry);
        orderIndex.put(entry.getOrderIdLong(), entry);
        
        log.debug("Order added to book: {} {} @ {} ({})",
                entry.getRemainingQuantity(), symbol,
                longToPrice(entry.getPriceKey()),
                entry.getOrder().getSide());
    }
    
    /**
     * Process a cancel order request.
     */
    private void processCancelOrder(String orderId) {
        long orderIdLong = Long.parseLong(orderId);
        OrderEntry entry = orderIndex.remove(orderIdLong);
        
        if (entry != null) {
            TreeMap<Long, LinkedList<OrderEntry>> book = 
                    entry.getOrder().getSide() == Order.Side.BUY ? bids : asks;
            
            LinkedList<OrderEntry> priceLevel = book.get(entry.getPriceKey());
            if (priceLevel != null) {
                priceLevel.remove(entry);
                if (priceLevel.isEmpty()) {
                    book.remove(entry.getPriceKey());
                }
            }
            
            notifyOrderUpdate(entry.getOrder()
                    .withStatus(Order.OrderStatus.CANCELLED)
                    .withSequenceNumber(sequenceNumber.incrementAndGet()));
            
            log.debug("Order cancelled: {}", orderId);
        }
    }
    
    /**
     * Process a replace order request.
     */
    private void processReplaceOrder(String orderId, BigDecimal newPrice, BigDecimal newQuantity) {
        long orderIdLong = Long.parseLong(orderId);
        OrderEntry entry = orderIndex.get(orderIdLong);
        
        if (entry != null) {
            // Remove from current price level
            TreeMap<Long, LinkedList<OrderEntry>> book = 
                    entry.getOrder().getSide() == Order.Side.BUY ? bids : asks;
            
            LinkedList<OrderEntry> priceLevel = book.get(entry.getPriceKey());
            if (priceLevel != null) {
                priceLevel.remove(entry);
                if (priceLevel.isEmpty()) {
                    book.remove(entry.getPriceKey());
                }
            }
            
            // Update order
            entry.setPriceKey(priceToLong(newPrice));
            entry.setRemainingQuantity(newQuantity);
            entry.setOrder(entry.getOrder()
                    .withPrice(newPrice)
                    .withQuantity(newQuantity)
                    .withRemainingQuantity(newQuantity));
            
            // Re-add to book at new price level
            addToBook(entry);
            
            notifyOrderUpdate(entry.getOrder()
                    .withSequenceNumber(sequenceNumber.incrementAndGet()));
            
            log.debug("Order replaced: {} -> price={}, qty={}", orderId, newPrice, newQuantity);
        }
    }
    
    /**
     * Get best bid price.
     */
    public BigDecimal getBestBid() {
        return bids.isEmpty() ? null : longToPrice(bids.firstKey());
    }
    
    /**
     * Get best ask price.
     */
    public BigDecimal getBestAsk() {
        return asks.isEmpty() ? null : longToPrice(asks.firstKey());
    }
    
    /**
     * Get total bid quantity at best price.
     */
    public BigDecimal getBestBidQuantity() {
        if (bids.isEmpty()) return BigDecimal.ZERO;
        return bids.firstEntry().getValue().stream()
                .map(OrderEntry::getRemainingQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    
    /**
     * Get total ask quantity at best price.
     */
    public BigDecimal getBestAskQuantity() {
        if (asks.isEmpty()) return BigDecimal.ZERO;
        return asks.firstEntry().getValue().stream()
                .map(OrderEntry::getRemainingQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    
    /**
     * Register a trade listener.
     */
    public void addTradeListener(Consumer<Trade> listener) {
        tradeListeners.add(listener);
    }
    
    /**
     * Register an order update listener.
     */
    public void addOrderUpdateListener(Consumer<Order> listener) {
        orderUpdateListeners.add(listener);
    }
    
    private void notifyTrade(Trade trade) {
        for (Consumer<Trade> listener : tradeListeners) {
            try {
                listener.accept(trade);
            } catch (Exception e) {
                log.error("Error notifying trade listener", e);
            }
        }
    }
    
    private void notifyOrderUpdate(Order order) {
        for (Consumer<Order> listener : orderUpdateListeners) {
            try {
                listener.accept(order);
            } catch (Exception e) {
                log.error("Error notifying order update listener", e);
            }
        }
    }
    
    private long priceToLong(BigDecimal price) {
        return price.multiply(BigDecimal.valueOf(PRICE_MULTIPLIER)).longValue();
    }
    
    private BigDecimal longToPrice(long priceKey) {
        return BigDecimal.valueOf(priceKey).divide(BigDecimal.valueOf(PRICE_MULTIPLIER));
    }
    
    @Override
    public void close() {
        disruptor.shutdown();
        log.info("Order matching engine stopped for symbol: {}", symbol);
    }
    
    /**
     * Internal order entry for the order book.
     */
    @lombok.Data
    private static class OrderEntry {
        private final long orderIdLong;
        private Order order;
        private long priceKey;
        private BigDecimal remainingQuantity;
        private final long createdNanos;
        
        public OrderEntry(long orderIdLong, Order order, long priceKey, 
                          BigDecimal remainingQuantity, long createdNanos) {
            this.orderIdLong = orderIdLong;
            this.order = order;
            this.priceKey = priceKey;
            this.remainingQuantity = remainingQuantity;
            this.createdNanos = createdNanos;
        }
    }
    
    /**
     * Order event for the Disruptor ring buffer.
     */
    @lombok.Data
    private static class OrderEvent {
        public enum Type { NEW, CANCEL, REPLACE }
        
        private Type type;
        private Order order;
        private String orderId;
        private BigDecimal newPrice;
        private BigDecimal newQuantity;
        private long timestampNanos;
    }
}
