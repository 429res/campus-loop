package edu.campusloop.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock rateLimitClock() { return Clock.systemUTC(); }
}
