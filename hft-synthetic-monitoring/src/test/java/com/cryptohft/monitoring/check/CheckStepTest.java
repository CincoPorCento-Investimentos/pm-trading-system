package com.cryptohft.monitoring.check;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CheckStepTest {

    @Test
    void okFactoryWithoutDetail() {
        CheckStep step = CheckStep.ok("step1", 100);
        assertEquals(Status.OK, step.status());
        assertEquals("", step.detail());
        assertEquals("step1", step.name());
        assertEquals(100, step.durationMs());
    }

    @Test
    void okFactoryWithDetail() {
        CheckStep step = CheckStep.ok("step1", "some detail", 100);
        assertEquals(Status.OK, step.status());
        assertEquals("some detail", step.detail());
    }

    @Test
    void warnFactory() {
        CheckStep step = CheckStep.warn("step1", "slow", 500);
        assertEquals(Status.WARN, step.status());
        assertEquals("slow", step.detail());
        assertEquals(500, step.durationMs());
    }

    @Test
    void failFactory() {
        CheckStep step = CheckStep.fail("step1", "timeout", 3000);
        assertEquals(Status.FAIL, step.status());
        assertEquals("timeout", step.detail());
        assertEquals(3000, step.durationMs());
    }

    @Test
    void recordFieldsAccessible() {
        CheckStep step = new CheckStep("connect", Status.OK, "connected", 42);
        assertEquals("connect", step.name());
        assertEquals(Status.OK, step.status());
        assertEquals("connected", step.detail());
        assertEquals(42, step.durationMs());
    }
}
