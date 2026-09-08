package com.ottodot.booking.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock the tests can drive forward.
 *
 * <p>Hold expiry is a time-dependent behaviour, and the alternative to this is
 * Thread.sleep — which would make the suite slow and flaky while proving less.
 * Advancing an injected clock tests the same code path deterministically and
 * instantly.
 */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    public MutableClock() {
        this(Instant.now(), ZoneId.of("UTC"));
    }

    private MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void advance(Duration amount) {
        instant = instant.plus(amount);
    }

    /** Called before every test: the clock is shared across a cached context. */
    public void reset() {
        instant = Instant.now();
    }
}
