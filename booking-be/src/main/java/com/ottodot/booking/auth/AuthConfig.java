package com.ottodot.booking.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {

    /**
     * Registered against /api/* only, so the actuator health and metrics
     * endpoints stay reachable for probes and scraping. Ordered first: nothing
     * else should get to run on an unauthenticated request.
     */
    @Bean
    public FilterRegistrationBean<AuthFilter> authFilter(SessionTokens tokens, ObjectMapper json) {
        FilterRegistrationBean<AuthFilter> registration =
                new FilterRegistrationBean<>(new AuthFilter(tokens, json));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
