package com.ottodot.booking.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the shared-password gate in front of the API.
 *
 * @param password  the one password that opens the demo
 * @param secret    HMAC key for session tokens; blank means "mint a random one
 *                  at startup", which logs every session out on restart but is
 *                  far better than shipping a guessable default
 * @param sessionDuration how long a token stays valid once issued
 * @param maxAttempts how many failed logins one client may make per window
 * @param lockoutWindow the window those attempts are counted over
 */
@ConfigurationProperties("booking.auth")
public record AuthProperties(
        String password,
        String secret,
        Duration sessionDuration,
        int maxAttempts,
        Duration lockoutWindow) {

    public AuthProperties {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "booking.auth.password must be set - the API refuses to start unprotected");
        }
        if (sessionDuration == null) sessionDuration = Duration.ofHours(12);
        if (lockoutWindow == null) lockoutWindow = Duration.ofMinutes(15);
        if (maxAttempts <= 0) maxAttempts = 10;
    }
}
