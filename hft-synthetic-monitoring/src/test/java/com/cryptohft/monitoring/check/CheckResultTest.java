package com.cryptohft.monitoring.check;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckResultTest {

    private final List<CheckStep> sampleSteps = List.of(
            CheckStep.ok("step1", 50),
            CheckStep.warn("step2", "slow", 200)
    );

    @Test
    void okFactory() {
        CheckResult result = CheckResult.ok("health-check", "All good", 150, sampleSteps);
        assertEquals(Status.OK, result.status());
        assertEquals("health-check", result.checkName());
        assertEquals("All good", result.message());
        assertEquals(150, result.durationMs());
        assertNotNull(result.timestamp());
    }

    @Test
    void failFactory() {
        CheckResult result = CheckResult.fail("health-check", "Down", 3000, sampleSteps);
        assertEquals(Status.FAIL, result.status());
        assertEquals("Down", result.message());
        assertEquals(3000, result.durationMs());
    }

    @Test
    void warnFactory() {
        CheckResult result = CheckResult.warn("health-check", "Slow", 2000, sampleSteps);
        assertEquals(Status.WARN, result.status());
        assertEquals("Slow", result.message());
        assertEquals(2000, result.durationMs());
    }

    @Test
    void queryMethods() {
        CheckResult ok = CheckResult.ok("check", "ok", 100, sampleSteps);
        assertTrue(ok.isOk());
        assertFalse(ok.isFailed());
        assertFalse(ok.isWarning());

        CheckResult fail = CheckResult.fail("check", "fail", 100, sampleSteps);
        assertTrue(fail.isFailed());
        assertFalse(fail.isOk());
        assertFalse(fail.isWarning());

        CheckResult warn = CheckResult.warn("check", "warn", 100, sampleSteps);
        assertTrue(warn.isWarning());
        assertFalse(warn.isOk());
        assertFalse(warn.isFailed());
    }

    @Test
    void stepsAreImmutable() {
        ArrayList<CheckStep> mutableSteps = new ArrayList<>();
        mutableSteps.add(CheckStep.ok("step1", 50));

        CheckResult result = CheckResult.ok("check", "ok", 100, mutableSteps);

        assertThrows(UnsupportedOperationException.class, () ->
                result.steps().add(CheckStep.ok("step2", 100))
        );
    }

    @Test
    void stepsPreserveContent() {
        CheckResult result = CheckResult.ok("check", "ok", 100, sampleSteps);
        assertEquals(2, result.steps().size());
        assertEquals("step1", result.steps().get(0).name());
        assertEquals("step2", result.steps().get(1).name());
    }

    @Test
    void timestampIsRecent() {
        Instant before = Instant.now();
        CheckResult result = CheckResult.ok("check", "ok", 100, sampleSteps);
        Instant after = Instant.now();

        assertTrue(Duration.between(before, result.timestamp()).toMillis() >= 0);
        assertTrue(Duration.between(result.timestamp(), after).toMillis() >= 0);
        assertTrue(Duration.between(before, after).toSeconds() < 1);
    }
}
