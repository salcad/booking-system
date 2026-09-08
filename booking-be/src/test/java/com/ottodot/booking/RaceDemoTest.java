package com.ottodot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.ottodot.booking.demo.RaceDemoService;
import com.ottodot.booking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The demo endpoint must demonstrate the invariant, not merely claim it. */
class RaceDemoTest extends AbstractIntegrationTest {

    @Autowired
    RaceDemoService raceDemo;

    @RepeatedTest(5)
    @DisplayName("the demo reports exactly one winner for the last seat")
    void reportsOneWinnerForOneSeat() {
        long classId = fixtures.trialClassWithConfirmed(4, 3);

        RaceDemoService.RaceReport report = raceDemo.run(classId, 8);

        assertThat(report.seatsBefore()).isEqualTo(1);
        assertThat(report.confirmed()).isEqualTo(1);
        assertThat(report.seatsAfter()).isZero();
        assertThat(report.invariantHolds()).isTrue();
        assertThat(report.attempts()).hasSize(8);
        assertThat(report.attempts().stream()
                .filter(a -> "REJECTED_AT_BOOKING".equals(a.phase()))
                .filter(a -> "CLASS_FULL".equals(a.outcome())).count())
                .as("losers are stopped before payment, so none of them is charged")
                .isEqualTo(7);
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("the demo fills an empty class to capacity and no further")
    void fillsEmptyClassExactlyToCapacity() {
        long classId = fixtures.trialClass(4);

        RaceDemoService.RaceReport report = raceDemo.run(classId, 16);

        assertThat(report.confirmed()).isEqualTo(4);
        assertThat(report.invariantHolds()).isTrue();
        fixtures.assertInvariants(classId);
    }
}
