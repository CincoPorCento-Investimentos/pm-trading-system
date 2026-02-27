package com.cryptohft.engine;

import com.cryptohft.common.domain.Order;
import com.cryptohft.common.domain.Trade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class OrderMatchingEngineTest {

    private OrderMatchingEngine engine;
    private List<Trade> capturedTrades;
    private List<Order> capturedOrderUpdates;

    @BeforeEach
    void setUp() {
        engine = new OrderMatchingEngine("BTCUSDT");
        capturedTrades = Collections.synchronizedList(new ArrayList<>());
        capturedOrderUpdates = Collections.synchronizedList(new ArrayList<>());
        engine.addTradeListener(capturedTrades::add);
        engine.addOrderUpdateListener(capturedOrderUpdates::add);
    }

    @AfterEach
    void tearDown() throws Exception {
        engine.close();
    }

    private Order limitOrder(String id, Order.Side side, BigDecimal price, BigDecimal qty) {
        return Order.builder()
                .orderId(id)
                .clientOrderId("client-" + id)
                .symbol("BTCUSDT")
                .side(side)
                .orderType(Order.OrderType.LIMIT)
                .timeInForce(Order.TimeInForce.GTC)
                .price(price)
                .quantity(qty)
                .remainingQuantity(qty)
                .filledQuantity(BigDecimal.ZERO)
                .status(Order.OrderStatus.NEW)
                .exchange("INTERNAL")
                .account("DEFAULT")
                .createdAt(Instant.now())
                .build();
    }

    private Order marketOrder(String id, Order.Side side, BigDecimal qty) {
        return Order.builder()
                .orderId(id)
                .clientOrderId("client-" + id)
                .symbol("BTCUSDT")
                .side(side)
                .orderType(Order.OrderType.MARKET)
                .timeInForce(Order.TimeInForce.IOC)
                .price(BigDecimal.ZERO)
                .quantity(qty)
                .remainingQuantity(qty)
                .filledQuantity(BigDecimal.ZERO)
                .status(Order.OrderStatus.NEW)
                .exchange("INTERNAL")
                .account("DEFAULT")
                .createdAt(Instant.now())
                .build();
    }

    private void waitForProcessing() throws InterruptedException {
        // Disruptor processes asynchronously, give it time
        Thread.sleep(100);
    }

    @Test
    void shouldAddLimitOrderToBookWhenNoMatch() throws InterruptedException {
        Order buyOrder = limitOrder("100", Order.Side.BUY, new BigDecimal("50000"), new BigDecimal("1"));
        engine.submitOrder(buyOrder);
        waitForProcessing();

        assertThat(engine.getBestBid()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(engine.getBestAsk()).isNull();
        assertThat(capturedTrades).isEmpty();
    }

    @Test
    void shouldMatchBuyAgainstExistingAsk() throws InterruptedException {
        // Place sell order first
        Order sellOrder = limitOrder("100", Order.Side.SELL, new BigDecimal("50000"), new BigDecimal("1"));
        engine.submitOrder(sellOrder);
        waitForProcessing();

        // Place buy order at matching price
        Order buyOrder = limitOrder("101", Order.Side.BUY, new BigDecimal("50000"), new BigDecimal("1"));
        engine.submitOrder(buyOrder);
        waitForProcessing();

        // Should have 2 trades (one for each side)
        assertThat(capturedTrades).hasSize(2);
        assertThat(capturedTrades.get(0).getPrice()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(capturedTrades.get(0).getQuantity()).isEqualByComparingTo(new BigDecimal("1"));
    }

    @Test
    void shouldPartiallyFillWhenInsufficientLiquidity() throws InterruptedException {
        // Place sell order for 0.5 BTC
        Order sellOrder = limitOrder("100", Order.Side.SELL, new BigDecimal("50000"), new BigDecimal("0.5"));
        engine.submitOrder(sellOrder);
        waitForProcessing();

        // Place buy order for 1 BTC
        Order buyOrder = limitOrder("101", Order.Side.BUY, new BigDecimal("50000"), new BigDecimal("1"));
        engine.submitOrder(buyOrder);
        waitForProcessing();

        // Trade for 0.5 (2 trade records - one per side)
        assertThat(capturedTrades).hasSize(2);
        assertThat(capturedTrades.get(0).getQuantity()).isEqualByComparingTo(new BigDecimal("0.5"));

        // Remaining 0.5 should be on the bid side
        assertThat(engine.getBestBid()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(engine.getBestBidQuantity()).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void shouldMatchAtPassivePriceLevel() throws InterruptedException {
        // Sell at 49000, buy at 50000 -> trade at 49000 (passive price)
        Order sellOrder = limitOrder("100", Order.Side.SELL, new BigDecimal("49000"), new BigDecimal("1"));
        engine.submitOrder(sellOrder);
        waitForProcessing();

        Order buyOrder = limitOrder("101", Order.Side.BUY, new BigDecimal("50000"), new BigDecimal("1"));
        engine.submitOrder(buyOrder);
        waitForProcessing();

        assertThat(capturedTrades).hasSize(2);
        assertThat(capturedTrades.get(0).getPrice()).isEqualByComparingTo(new BigDecimal("49000"));
    }

    @Test
    void shouldNotMatchWhenPricesDoNotCross() throws InterruptedException {
        Order sellOrder = limitOrder("100", Order.Side.SELL, new BigDecimal("51000"), new BigDecimal("1"));
        engine.submitOrder(sellOrder);
        waitForProcessing();

        Order buyOrder = limitOrder("101", Order.Side.BUY, new BigDecimal("50000"), new BigDecimal("1"));
        engine.submitOrder(buyOrder);
        waitForProcessing();

        assertThat(capturedTrades).isEmpty();
        assertThat(engine.getBestBid()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(engine.getBestAsk()).isEqualByComparingTo(new BigDecimal("51000"));
    }

    @Test
    void shouldCancelOrder() throws InterruptedException {
        Order buyOrder = limitOrder("100", Order.Side.BUY, new BigDecimal("50000"), new BigDecimal("1"));
        engine.submitOrder(buyOrder);
        waitForProcessing();

        assertThat(engine.getBestBid()).isEqualByComparingTo(new BigDecimal("50000"));

        engine.cancelOrder("100");
        waitForProcessing();

        assertThat(engine.getBestBid()).isNull();

        // Should have received a CANCELLED order update
        boolean hasCancelled = capturedOrderUpdates.stream()
                .anyMatch(o -> o.getStatus() == Order.OrderStatus.CANCELLED);
        assertThat(hasCancelled).isTrue();
    }

    @Test
    void shouldRejectMarketOrderWithNoLiquidity() throws InterruptedException {
        Order marketBuy = marketOrder("100", Order.Side.BUY, new BigDecimal("1"));
        engine.submitOrder(marketBuy);
        waitForProcessing();

        assertThat(capturedTrades).isEmpty();
        boolean hasRejected = capturedOrderUpdates.stream()
                .anyMatch(o -> o.getStatus() == Order.OrderStatus.REJECTED);
        assertThat(hasRejected).isTrue();
    }

    @Test
    void shouldNotifyListenersOnTrade() throws InterruptedException {
        CountDownLatch tradeLatch = new CountDownLatch(2);
        engine.addTradeListener(t -> tradeLatch.countDown());

        Order sellOrder = limitOrder("100", Order.Side.SELL, new BigDecimal("50000"), new BigDecimal("1"));
        Order buyOrder = limitOrder("101", Order.Side.BUY, new BigDecimal("50000"), new BigDecimal("1"));

        engine.submitOrder(sellOrder);
        waitForProcessing();
        engine.submitOrder(buyOrder);

        boolean completed = tradeLatch.await(2, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
    }

    @Test
    void shouldReplaceOrder() throws InterruptedException {
        Order buyOrder = limitOrder("100", Order.Side.BUY, new BigDecimal("50000"), new BigDecimal("1"));
        engine.submitOrder(buyOrder);
        waitForProcessing();

        assertThat(engine.getBestBid()).isEqualByComparingTo(new BigDecimal("50000"));

        engine.replaceOrder("100", new BigDecimal("51000"), new BigDecimal("2"));
        waitForProcessing();

        assertThat(engine.getBestBid()).isEqualByComparingTo(new BigDecimal("51000"));
        assertThat(engine.getBestBidQuantity()).isEqualByComparingTo(new BigDecimal("2"));
    }
}
