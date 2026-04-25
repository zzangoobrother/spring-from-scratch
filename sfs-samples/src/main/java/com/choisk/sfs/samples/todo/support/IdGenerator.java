package com.choisk.sfs.samples.todo.support;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 학습용 단순 ID 발급 유틸.
 * AtomicLong으로 멀티스레드 환경을 고려한 순차 ID를 발급하고,
 * Clock 주입으로 테스트 가능성(Clock.fixed)을 시연한다.
 */
public class IdGenerator {

    private final Clock clock;
    private final AtomicLong seq = new AtomicLong(0);

    public IdGenerator(Clock clock) {
        this.clock = clock;
    }

    public long next() {
        return seq.incrementAndGet();
    }

    public Instant nowInstant() {
        return clock.instant();
    }
}
