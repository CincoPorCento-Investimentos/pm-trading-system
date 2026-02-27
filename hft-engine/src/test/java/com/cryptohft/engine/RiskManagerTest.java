package com.cryptohft.engine;

import com.cryptohft.common.domain.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RiskManagerTest {

    private RiskManager riskManager;

    @BeforeEach
    void setUp() {
        RiskProperties config = new RiskProperties();
        config.setMaxOrderSize(new BigDecimal("1000"));
        config.setMinOrderSize(new BigDecimal("0.001"));
        config.setMaxOrderNotional(new BigDecimal("1000000"));
        config.setMaxPositionSize(new BigDecimal("10000"));
        config.setMaxTotalExposure(new BigDecimal("10000000"));
        config.setMaxDailyLoss(new BigDecimal("100000"));
        config.setMaxPriceDeviation(new BigDecimal("0.10"));
        config.setMaxOrdersPerSecond(100);
        riskManager = new RiskManager(config);
    }

    private Order validBuyOrder(BigDecimal qty, BigDecimal price) {
        return Order.builder()
                .orderId("1001")
                .clientOrderId("client-1001")
                .symbol("BTCUSDT")
                .side(Order.Side.BUY)
                .orderType(Order.OrderType.LIMIT)
                .timeInForce(Order.TimeInForce.GTC)
                .price(price)
                .quantity(qty)
                .remainingQuantity(qty)
                .filledQuantity(BigDecimal.ZERO)
                .status(Order.OrderStatus.PENDING_NEW)
                .exchange("INTERNAL")
                .account("DEFAULT")
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void shouldAcceptValidOrder() {
        Order order = validBuyOrder(new BigDecimal("1"), new BigDecimal("50000"));
        RiskManager.RiskCheckResult result = riskManager.checkOrder(order);

        assertThat(result.isAccepted()).isTrue();
    }

    @Test
    void shouldRejectWhenCircuitBreakerTriggered() {
        riskManager.disableTrading("test");

        Order order = validBuyOrder(new BigDecimal("1"), new BigDecimal("50000"));
        RiskManager.RiskCheckResult result = riskManager.checkOrder(order);

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getRejectCode()).isEqualTo("CIRCUIT_BREAKER");
    }

    @Test
    void shouldRejectWhenOrderSizeExceedsMax() {
        Order order = validBuyOrder(new BigDecimal("2000"), new BigDecimal("50000"));
        RiskManager.RiskCheckResult result = riskManager.checkOrder(order);

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getRejectReason()).contains("ORDER_SIZE_EXCEEDS_MAX");
    }

    @Test
    void shouldRejectWhenOrderSizeBelowMin() {
        Order order = validBuyOrder(new BigDecimal("0.0001"), new BigDecimal("50000"));
        RiskManager.RiskCheckResult result = riskManager.checkOrder(order);

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getRejectReason()).contains("ORDER_SIZE_BELOW_MIN");
    }

    @Test
    void shouldRejectWhenNotionalExceedsMax() {
        // qty=500, price=50000 => notional=25,000,000 > 1,000,000
        Order order = validBuyOrder(new BigDecimal("500"), new BigDecimal("50000"));
        RiskManager.RiskCheckResult result = riskManager.checkOrder(order);

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getRejectReason()).contains("ORDER_NOTIONAL_EXCEEDS_MAX");
    }

    @Test
    void shouldRejectWhenDailyLossLimitExceeded() {
        // Simulate large daily loss by updating position then selling at a big loss
        riskManager.updatePosition("BTCUSDT", "DEFAULT", Order.Side.BUY,
                new BigDecimal("100"), new BigDecimal("50000"));
        riskManager.updatePosition("BTCUSDT", "DEFAULT", Order.Side.SELL,
                new BigDecimal("100"), new BigDecimal("48000"));

        // dailyPnl should be deeply negative: (48000-50000)*100 = -200000
        RiskManager.RiskStats stats = riskManager.getStats();
        assertThat(stats.getDailyPnl()).isLessThan(BigDecimal.ZERO);

        Order order = validBuyOrder(new BigDecimal("1"), new BigDecimal("50000"));
        RiskManager.RiskCheckResult result = riskManager.checkOrder(order);

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getRejectReason()).contains("DAILY_LOSS_LIMIT_EXCEEDED");
    }

    @Test
    void shouldUpdatePositionCorrectly() {
        riskManager.updatePosition("BTCUSDT", "DEFAULT", Order.Side.BUY,
                new BigDecimal("10"), new BigDecimal("50000"));

        var position = riskManager.getPosition("BTCUSDT");
        assertThat(position).isNotNull();
        assertThat(position.getQuantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(position.getAverageEntryPrice()).isEqualByComparingTo(new BigDecimal("50000"));
    }

    @Test
    void shouldCalculateAveragePriceWhenAddingToPosition() {
        riskManager.updatePosition("BTCUSDT", "DEFAULT", Order.Side.BUY,
                new BigDecimal("10"), new BigDecimal("50000"));
        riskManager.updatePosition("BTCUSDT", "DEFAULT", Order.Side.BUY,
                new BigDecimal("10"), new BigDecimal("52000"));

        var position = riskManager.getPosition("BTCUSDT");
        // (10*50000 + 10*52000) / 20 = 51000
        assertThat(position.getAverageEntryPrice()).isEqualByComparingTo(new BigDecimal("51000"));
        assertThat(position.getQuantity()).isEqualByComparingTo(new BigDecimal("20"));
    }

    @Test
    void shouldResetDailyMetrics() {
        riskManager.updatePosition("BTCUSDT", "DEFAULT", Order.Side.BUY,
                new BigDecimal("10"), new BigDecimal("50000"));
        riskManager.updatePosition("BTCUSDT", "DEFAULT", Order.Side.SELL,
                new BigDecimal("10"), new BigDecimal("51000"));

        RiskManager.RiskStats beforeReset = riskManager.getStats();
        assertThat(beforeReset.getDailyPnl()).isNotEqualByComparingTo(BigDecimal.ZERO);

        riskManager.resetDaily();

        RiskManager.RiskStats afterReset = riskManager.getStats();
        assertThat(afterReset.getDailyPnl()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(afterReset.getOrderCount()).isZero();
        assertThat(afterReset.getRejectCount()).isZero();
    }

    @Test
    void shouldEnableAndDisableTrading() {
        assertThat(riskManager.getStats().isTradingEnabled()).isTrue();

        riskManager.disableTrading("test");
        assertThat(riskManager.getStats().isTradingEnabled()).isFalse();

        riskManager.enableTrading();
        assertThat(riskManager.getStats().isTradingEnabled()).isTrue();
    }

    @Test
    void shouldTrackPendingExposure() {
        riskManager.addPendingExposure("BTCUSDT", Order.Side.BUY, new BigDecimal("100"));

        // Now submit an order that would push position over limit
        // maxPositionSize=10000, pending=100, new order=9950 => projected = 10050 > 10000
        Order order = validBuyOrder(new BigDecimal("9950"), new BigDecimal("1"));
        RiskManager.RiskCheckResult result = riskManager.checkOrder(order);

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getRejectReason()).contains("POSITION_LIMIT_EXCEEDED");
    }
}
