package com.cryptohft.engine;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Type-safe configuration properties for risk management.
 * Bound from {@code hft.risk.*} in application.yml.
 *
 * <p>All values are required at startup. Defaults are defined in application.yml,
 * not in this class, to maintain a single source of truth for risk parameters.</p>
 */
@Data
public class RiskProperties {

    private BigDecimal maxOrderSize;
    private BigDecimal minOrderSize;
    private BigDecimal maxOrderNotional;
    private BigDecimal maxPositionSize;
    private BigDecimal maxTotalExposure;
    private BigDecimal maxDailyLoss;
    private BigDecimal maxPriceDeviation;
    private int maxOrdersPerSecond;
}
