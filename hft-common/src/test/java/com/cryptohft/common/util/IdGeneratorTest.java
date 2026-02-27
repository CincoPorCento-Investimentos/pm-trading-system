package com.cryptohft.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdGeneratorTest {

    @Test
    void shouldGeneratePositiveIds() {
        IdGenerator gen = new IdGenerator(0);
        for (int i = 0; i < 100; i++) {
            assertThat(gen.nextId()).isPositive();
        }
    }

    @Test
    void shouldGenerateUniqueIds() {
        IdGenerator gen = new IdGenerator(1);
        Set<Long> ids = new HashSet<>();
        int count = 10_000;
        for (int i = 0; i < count; i++) {
            ids.add(gen.nextId());
        }
        assertThat(ids).hasSize(count);
    }

    @Test
    void shouldGenerateIncreasingIds() {
        IdGenerator gen = new IdGenerator(2);
        long prev = 0;
        for (int i = 0; i < 100; i++) {
            long id = gen.nextId();
            assertThat(id).isGreaterThan(prev);
            prev = id;
        }
    }

    @Test
    void shouldExtractTimestamp() {
        IdGenerator gen = new IdGenerator(0);
        long before = System.currentTimeMillis();
        long id = gen.nextId();
        long after = System.currentTimeMillis();

        long extracted = IdGenerator.extractTimestamp(id);
        assertThat(extracted).isBetween(before, after);
    }

    @Test
    void shouldExtractNodeId() {
        long nodeId = 42;
        IdGenerator gen = new IdGenerator(nodeId);
        long id = gen.nextId();

        assertThat(IdGenerator.extractNodeId(id)).isEqualTo(nodeId);
    }

    @Test
    void shouldGenerateStringIds() {
        IdGenerator gen = new IdGenerator(0);
        String idStr = gen.nextIdString();
        assertThat(idStr).isNotBlank();
        assertThat(Long.parseLong(idStr)).isPositive();
    }

    @Test
    void shouldRejectInvalidNodeId() {
        assertThatThrownBy(() -> new IdGenerator(-1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new IdGenerator(1024))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
