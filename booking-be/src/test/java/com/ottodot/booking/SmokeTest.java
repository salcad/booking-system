package com.ottodot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.ottodot.booking.support.AbstractIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SmokeTest extends AbstractIntegrationTest {

    @Test
    void contextLoadsAndMigrationsRan() {
        Integer classes = jdbc.queryForObject("SELECT count(*) FROM trial_classes", Integer.class);
        assertThat(classes).isGreaterThanOrEqualTo(4);
    }

    @Test
    void fixturesAndClockWork() {
        long classId = fixtures.trialClassWithConfirmed(4, 3);
        assertThat(fixtures.claimedSeats(classId)).isEqualTo(3);
        fixtures.assertInvariants(classId);

        var before = clock.instant();
        clock.advance(Duration.ofMinutes(11));
        assertThat(clock.instant()).isAfter(before);
    }
}
