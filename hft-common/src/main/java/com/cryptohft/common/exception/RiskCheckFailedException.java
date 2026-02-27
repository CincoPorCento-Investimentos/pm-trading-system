package com.cryptohft.common.exception;

import java.util.List;

/**
 * Thrown when a pre-trade risk check fails.
 * Contains the list of specific violations that caused the rejection.
 */
public class RiskCheckFailedException extends TradingException {

    private final List<String> violations;

    public RiskCheckFailedException(String rejectCode, String rejectReason, List<String> violations) {
        super(rejectCode, rejectReason);
        this.violations = violations != null ? List.copyOf(violations) : List.of();
    }

    public RiskCheckFailedException(String rejectCode, String rejectReason) {
        this(rejectCode, rejectReason, List.of());
    }

    public List<String> getViolations() {
        return violations;
    }
}
