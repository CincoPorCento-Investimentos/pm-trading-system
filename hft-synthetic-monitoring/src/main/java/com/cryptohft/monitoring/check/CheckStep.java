package com.cryptohft.monitoring.check;

public record CheckStep(String name, Status status, String detail, long durationMs) {

    public static CheckStep ok(String name, long durationMs) {
        return new CheckStep(name, Status.OK, "", durationMs);
    }

    public static CheckStep ok(String name, String detail, long durationMs) {
        return new CheckStep(name, Status.OK, detail, durationMs);
    }

    public static CheckStep warn(String name, String detail, long durationMs) {
        return new CheckStep(name, Status.WARN, detail, durationMs);
    }

    public static CheckStep fail(String name, String detail, long durationMs) {
        return new CheckStep(name, Status.FAIL, detail, durationMs);
    }
}
