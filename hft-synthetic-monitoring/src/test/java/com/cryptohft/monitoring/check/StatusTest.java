package com.cryptohft.monitoring.check;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StatusTest {

    @Test
    void hasThreeValues() {
        assertEquals(3, Status.values().length);
    }

    @Test
    void valuesAreCorrect() {
        assertNotNull(Status.valueOf("OK"));
        assertNotNull(Status.valueOf("WARN"));
        assertNotNull(Status.valueOf("FAIL"));
    }
}
