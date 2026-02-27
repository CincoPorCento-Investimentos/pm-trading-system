package com.cryptohft.engine;

import com.cryptohft.common.domain.Order;
import com.cryptohft.common.domain.Position;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.agrona.collections.Object2ObjectHashMap;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-time risk management for pre-trade and post-trade checks.
 * Performs ultra-low-latency risk checks before order submission.
 *
 * <p>Risk checks are executed in the following order (fail-fast):
 * <ol>
 *   <li>Circuit breaker - is trading globally enabled?</li>
 *   <li>Order rate limit - orders per second threshold</li>
 *   <li>Order size limits - min/max quantity</li>
 *   <li>Notional value - price * quantity cap</li>
 *   <li>Position limits - projected position after fill</li>
 *   <li>Total exposure - aggregate notional across all positions</li>
 *   <li>Daily loss limit - realized P&amp;L floor</li>
 *   <li>Price deviation - limit order price vs reference price</li>
 * </ol>
 */
@Slf4j
public class RiskManager {

    private final RiskProperties config;
    private final Map<String, Position> positions = new Object2ObjectHashMap<>();
    private final Map<String, BigDecimal> orderExposure = new Object2ObjectHashMap<>();

    // Circuit breaker
    private final AtomicBoolean tradingEnabled = new AtomicBoolean(true);

    // Risk metrics
    private volatile BigDecimal totalPnl = BigDecimal.ZERO;
    private volatile BigDecimal dailyPnl = BigDecimal.ZERO;
    private volatile int orderCount = 0;
    private volatile int rejectCount = 0;

    public RiskManager(RiskProperties config) {
        this.config = config;
        log.info("Risk manager initialized with config: {}", config);
    }

    /**
     * Perform pre-trade risk check.
     * Returns a result indicating acceptance or rejection with specific violation details.
     */
    public RiskCheckResult checkOrder(Order order) {
        List<String> violations = new ArrayList<>();

        // Check circuit breaker
        if (!tradingEnabled.get()) {
            return RiskCheckResult.reject("CIRCUIT_BREAKER", "Trading disabled by circuit breaker");
        }

        // Check order rate limit
        if (orderCount >= config.getMaxOrdersPerSecond()) {
            violations.add("ORDER_RATE_LIMIT_EXCEEDED");
        }

        // Check order size limits
        if (order.getQuantity().compareTo(config.getMaxOrderSize()) > 0) {
            violations.add("ORDER_SIZE_EXCEEDS_MAX: " + order.getQuantity() + " > " + config.getMaxOrderSize());
        }

        if (order.getQuantity().compareTo(config.getMinOrderSize()) < 0) {
            violations.add("ORDER_SIZE_BELOW_MIN: " + order.getQuantity() + " < " + config.getMinOrderSize());
        }

        // Check notional value
        BigDecimal notional = order.getPrice().multiply(order.getQuantity());
        if (notional.compareTo(config.getMaxOrderNotional()) > 0) {
            violations.add("ORDER_NOTIONAL_EXCEEDS_MAX: " + notional + " > " + config.getMaxOrderNotional());
        }

        // Check position limits
        Position currentPosition = positions.get(order.getSymbol());
        BigDecimal currentQty = currentPosition != null ? currentPosition.getQuantity() : BigDecimal.ZERO;
        BigDecimal pendingExposure = orderExposure.getOrDefault(order.getSymbol(), BigDecimal.ZERO);

        BigDecimal projectedPosition = currentQty
                .add(order.getSide() == Order.Side.BUY ? order.getQuantity() : order.getQuantity().negate())
                .add(pendingExposure);

        if (projectedPosition.abs().compareTo(config.getMaxPositionSize()) > 0) {
            violations.add("POSITION_LIMIT_EXCEEDED: projected=" + projectedPosition + ", max=" + config.getMaxPositionSize());
        }

        // Check total exposure
        BigDecimal totalExposure = calculateTotalExposure()
                .add(notional);

        if (totalExposure.compareTo(config.getMaxTotalExposure()) > 0) {
            violations.add("TOTAL_EXPOSURE_EXCEEDED: " + totalExposure + " > " + config.getMaxTotalExposure());
        }

        // Check daily loss limit
        if (dailyPnl.compareTo(config.getMaxDailyLoss().negate()) < 0) {
            violations.add("DAILY_LOSS_LIMIT_EXCEEDED: " + dailyPnl);
        }

        // Check price reasonability (if we have a reference price)
        if (currentPosition != null && order.getOrderType() == Order.OrderType.LIMIT) {
            BigDecimal refPrice = currentPosition.getAverageEntryPrice();
            if (refPrice != null && refPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal deviation = order.getPrice().subtract(refPrice)
                        .divide(refPrice, 4, RoundingMode.HALF_UP)
                        .abs();

                if (deviation.compareTo(config.getMaxPriceDeviation()) > 0) {
                    violations.add("PRICE_DEVIATION_TOO_HIGH: " + deviation.multiply(BigDecimal.valueOf(100)) + "%");
                }
            }
        }

        if (!violations.isEmpty()) {
            rejectCount++;
            log.warn("Order rejected - orderId={}, violations={}", order.getOrderId(), violations);
            return RiskCheckResult.reject("RISK_CHECK_FAILED", String.join("; ", violations));
        }

        orderCount++;
        return RiskCheckResult.accept();
    }

    /**
     * Update position after trade execution.
     *
     * <p>Average price calculation:
     * <ul>
     *   <li>Adding to position: weighted average of existing + new trade</li>
     *   <li>Reducing position: average price stays the same (only realized P&amp;L changes)</li>
     *   <li>Flat position: average price resets to zero</li>
     * </ul>
     *
     * <p>Realized P&amp;L is computed when reducing a position:
     * {@code (tradePrice - avgEntryPrice) * reducedQty} (negated for short positions).
     */
    public void updatePosition(String symbol, String account, Order.Side side,
                                BigDecimal quantity, BigDecimal price) {
        Position current = positions.get(symbol);

        BigDecimal currentQty = current != null ? current.getQuantity() : BigDecimal.ZERO;
        BigDecimal currentAvgPrice = current != null ? current.getAverageEntryPrice() : BigDecimal.ZERO;

        BigDecimal tradeQty = side == Order.Side.BUY ? quantity : quantity.negate();
        BigDecimal newQty = currentQty.add(tradeQty);

        // Calculate new average price
        BigDecimal newAvgPrice;
        if (newQty.compareTo(BigDecimal.ZERO) == 0) {
            newAvgPrice = BigDecimal.ZERO;
        } else if ((currentQty.compareTo(BigDecimal.ZERO) >= 0 && side == Order.Side.BUY) ||
                   (currentQty.compareTo(BigDecimal.ZERO) <= 0 && side == Order.Side.SELL)) {
            // Adding to position
            BigDecimal totalCost = currentQty.multiply(currentAvgPrice)
                    .add(tradeQty.multiply(price));
            newAvgPrice = totalCost.divide(newQty, 8, RoundingMode.HALF_UP);
        } else {
            // Reducing position
            newAvgPrice = currentAvgPrice;
        }

        // Calculate realized PnL for position reduction
        BigDecimal realizedPnl = BigDecimal.ZERO;
        if (currentQty.abs().compareTo(newQty.abs()) > 0) {
            BigDecimal reducedQty = currentQty.abs().subtract(newQty.abs()).min(quantity);
            realizedPnl = price.subtract(currentAvgPrice).multiply(reducedQty);
            if (currentQty.compareTo(BigDecimal.ZERO) < 0) {
                realizedPnl = realizedPnl.negate();
            }
            totalPnl = totalPnl.add(realizedPnl);
            dailyPnl = dailyPnl.add(realizedPnl);
        }

        Position newPosition = Position.builder()
                .symbol(symbol)
                .account(account)
                .quantity(newQty)
                .averageEntryPrice(newAvgPrice)
                .realizedPnl(realizedPnl)
                .build();

        positions.put(symbol, newPosition);

        log.debug("Position updated: symbol={}, qty={}, avgPrice={}, realizedPnl={}",
                symbol, newQty, newAvgPrice, realizedPnl);
    }

    /**
     * Update pending order exposure.
     */
    public void addPendingExposure(String symbol, Order.Side side, BigDecimal quantity) {
        BigDecimal current = orderExposure.getOrDefault(symbol, BigDecimal.ZERO);
        BigDecimal delta = side == Order.Side.BUY ? quantity : quantity.negate();
        orderExposure.put(symbol, current.add(delta));
    }

    /**
     * Remove pending order exposure.
     */
    public void removePendingExposure(String symbol, Order.Side side, BigDecimal quantity) {
        BigDecimal current = orderExposure.getOrDefault(symbol, BigDecimal.ZERO);
        BigDecimal delta = side == Order.Side.BUY ? quantity : quantity.negate();
        orderExposure.put(symbol, current.subtract(delta));
    }

    /**
     * Enable trading (after circuit breaker recovery).
     */
    public void enableTrading() {
        tradingEnabled.set(true);
        log.info("Trading enabled");
    }

    /**
     * Disable trading (trigger circuit breaker).
     */
    public void disableTrading(String reason) {
        tradingEnabled.set(false);
        log.warn("Trading disabled: {}", reason);
    }

    /**
     * Reset daily metrics (call at start of trading day).
     */
    public void resetDaily() {
        dailyPnl = BigDecimal.ZERO;
        orderCount = 0;
        rejectCount = 0;
        log.info("Daily risk metrics reset");
    }

    /**
     * Get current position for a symbol.
     */
    public Position getPosition(String symbol) {
        return positions.get(symbol);
    }

    /**
     * Calculate total exposure across all positions.
     */
    public BigDecimal calculateTotalExposure() {
        return positions.values().stream()
                .map(p -> p.getNotionalValue() != null ? p.getNotionalValue() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Get risk statistics.
     */
    public RiskStats getStats() {
        return RiskStats.builder()
                .totalPnl(totalPnl)
                .dailyPnl(dailyPnl)
                .orderCount(orderCount)
                .rejectCount(rejectCount)
                .tradingEnabled(tradingEnabled.get())
                .totalExposure(calculateTotalExposure())
                .positionCount(positions.size())
                .build();
    }

    /**
     * Risk check result.
     */
    @Data
    @Builder
    public static class RiskCheckResult {
        private final boolean accepted;
        private final String rejectCode;
        private final String rejectReason;

        public static RiskCheckResult accept() {
            return RiskCheckResult.builder().accepted(true).build();
        }

        public static RiskCheckResult reject(String code, String reason) {
            return RiskCheckResult.builder()
                    .accepted(false)
                    .rejectCode(code)
                    .rejectReason(reason)
                    .build();
        }
    }

    /**
     * Risk statistics.
     */
    @Data
    @Builder
    public static class RiskStats {
        private BigDecimal totalPnl;
        private BigDecimal dailyPnl;
        private int orderCount;
        private int rejectCount;
        private boolean tradingEnabled;
        private BigDecimal totalExposure;
        private int positionCount;
    }
}
