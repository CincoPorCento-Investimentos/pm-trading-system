package com.cryptohft.common.exception;

/**
 * Thrown when an operation is attempted on an order in an invalid state.
 * For example, trying to cancel a FILLED order.
 */
public class InvalidOrderStateException extends TradingException {

    public InvalidOrderStateException(String orderId, String currentState, String attemptedOperation) {
        super("INVALID_ORDER_STATE",
                "Cannot " + attemptedOperation + " order " + orderId + " in state: " + currentState);
    }
}
