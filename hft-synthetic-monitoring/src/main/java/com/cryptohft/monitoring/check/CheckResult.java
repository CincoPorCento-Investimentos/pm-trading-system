package com.cryptohft.monitoring.check;

import java.time.Instant;
import java.util.List;

public record CheckResult(
        String checkName,
        Status status,
        String message,
        long durationMs,
        Instant timestamp,
        List<CheckStep> steps) {

    public CheckResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static CheckResult ok(String checkName, String message, long durationMs, List<CheckStep> steps) {
        return new CheckResult(checkName, Status.OK, message, durationMs, Instant.now(), steps);
    }

    public static CheckResult fail(String checkName, String message, long durationMs, List<CheckStep> steps) {
        return new CheckResult(checkName, Status.FAIL, message, durationMs, Instant.now(), steps);
    }

    public static CheckResult warn(String checkName, String message, long durationMs, List<CheckStep> steps) {
        return new CheckResult(checkName, Status.WARN, message, durationMs, Instant.now(), steps);
    }

    public boolean isFailed() {
        return status == Status.FAIL;
    }

    public boolean isWarning() {
        return status == Status.WARN;
    }

    public boolean isOk() {
        return status == Status.OK;
    }
}
