package com.choisk.sfs.samples.todo.support;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class IdGeneratorTest {

    @Test
    void nextProducesIncreasingIdsStartingFromOne() {
        IdGenerator gen = new IdGenerator(Clock.systemDefaultZone());
        assertThat(gen.next()).isEqualTo(1L);
        assertThat(gen.next()).isEqualTo(2L);
        assertThat(gen.next()).isEqualTo(3L);
    }

    @Test
    void nowInstantDelegatesToInjectedClock() {
        Instant fixed = Instant.parse("2026-01-01T00:00:00Z");
        Clock fixedClock = Clock.fixed(fixed, ZoneId.of("UTC"));
        IdGenerator gen = new IdGenerator(fixedClock);
        assertThat(gen.nowInstant()).isEqualTo(fixed);
    }
}
