package com.ottodot.booking.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Caps failed logins per client.
 *
 * <p>A single shared password is guessable at HTTP speed, so the login
 * endpoint - the one route the gate leaves open - counts failures per client
 * address and stops answering once a client has spent its allowance.
 *
 * <p>In memory and per instance, which is the honest limit: a caller behind
 * many source addresses, or hitting several replicas, gets a proportionally
 * larger allowance. Slowing down a casual guesser is what this is for.
 */
@Component
public class LoginThrottle {

    private final Clock clock;
    private final int maxAttempts;
    private final Duration window;
    private final Map<String, Attempts> byClient = new ConcurrentHashMap<>();

    public LoginThrottle(AuthProperties properties, Clock clock) {
        this.clock = clock;
        this.maxAttempts = properties.maxAttempts();
        this.window = properties.lockoutWindow();
    }

    public boolean isBlocked(String client) {
        Attempts a = byClient.get(client);
        return a != null && !a.hasExpired(clock.instant(), window) && a.count() >= maxAttempts;
    }

    public void recordFailure(String client) {
        Instant now = clock.instant();
        byClient.merge(client, new Attempts(1, now),
                (existing, fresh) -> existing.hasExpired(now, window)
                        ? fresh
                        : new Attempts(existing.count() + 1, existing.first()));
        // Bounded cleanup: without it a spray of forged addresses would grow
        // the map without limit. Cheap because it only runs on failures.
        if (byClient.size() > 10_000) {
            byClient.entrySet().removeIf(e -> e.getValue().hasExpired(now, window));
        }
    }

    /** A success clears the record, so a legitimate typo costs nothing later. */
    public void recordSuccess(String client) {
        byClient.remove(client);
    }

    /**
     * Forgets every recorded attempt. Nothing in the application calls this;
     * it exists so tests sharing one application context - and therefore one
     * client address - can start from a known allowance.
     */
    public void clear() {
        byClient.clear();
    }

    private record Attempts(int count, Instant first) {
        boolean hasExpired(Instant now, Duration window) {
            return first.plus(window).isBefore(now);
        }
    }
}
