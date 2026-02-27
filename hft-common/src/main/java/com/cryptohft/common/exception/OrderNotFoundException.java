package com.cryptohft.common.exception;

/**
 * Thrown when an order cannot be found by its ID.
 */
public class OrderNotFoundException extends TradingException {

    public OrderNotFoundException(String orderId) {
        super("ORDER_NOT_FOUND", "Order not found: " + orderId);
    }
}
