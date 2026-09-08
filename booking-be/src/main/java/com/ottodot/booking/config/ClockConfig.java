package com.ottodot.booking.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every read of "now" goes through this bean so that hold expiry can be driven
 * forward in tests without Thread.sleep. Tests replace it with a mutable Clock.
 */
@Configuration
public class ClockConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
