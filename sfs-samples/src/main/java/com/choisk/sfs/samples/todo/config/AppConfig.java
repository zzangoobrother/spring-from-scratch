package com.choisk.sfs.samples.todo.config;

import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.ComponentScan;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.samples.todo.support.IdGenerator;

import java.time.Clock;

@Configuration
@ComponentScan(basePackages = "com.choisk.sfs.samples.todo")
public class AppConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public IdGenerator idGenerator(Clock clock) {
        return new IdGenerator(clock);
    }
}
