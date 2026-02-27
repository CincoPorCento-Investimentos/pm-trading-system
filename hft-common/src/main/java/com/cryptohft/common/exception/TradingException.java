package com.cryptohft.common.exception;

/**
 * Base exception for all trading system errors.
 * Carries an error code for programmatic error handling and API responses.
 */
public class TradingException extends RuntimeException {

    private final String errorCode;

    public TradingException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TradingException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
