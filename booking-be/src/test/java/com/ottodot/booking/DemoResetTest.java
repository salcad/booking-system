package com.ottodot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.ottodot.booking.demo.DemoResetService;
import com.ottodot.booking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The rebuild button is only useful if it really restores the seed fixtures. */
class DemoResetTest extends AbstractIntegrationTest {

    @Autowired
    DemoResetService demoReset;

    @Test
    @DisplayName("a rebuild discards session data and reinstates the seed")
    void rebuildRestoresSeedState() {
        long parentId = fixtures.parent();
        long studentId = fixtures.student(parentId);
        long classId = fixtures.trialClass(4);
        jdbc.update("INSERT INTO bookings (student_id, trial_class_id, status, hold_expires_at) "
                + "VALUES (?, ?, 'CONFIRMED', NULL)", studentId, classId);

        DemoResetService.ResetReport report = demoReset.reset();

        assertThat(report).isEqualTo(new DemoResetService.ResetReport(3, 5, 4, 6));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trial_classes WHERE id = ?",
                Integer.class, classId))
                .as("rows created during the session are gone")
                .isZero();

        // Class 2 is the race fixture: capacity 4, three seats already taken.
        assertThat(jdbc.queryForObject(
                "SELECT capacity - claimed_seats FROM trial_classes WHERE id = 2",
                Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM students WHERE always_fails_payment", Integer.class))
                .as("the payment-failure fixture is back")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM trial_classes WHERE starts_at < now()", Integer.class))
                .as("class times are regenerated relative to now, not left stale")
                .isZero();
    }

    @Test
    @DisplayName("a rebuild is repeatable: sequences are reset with the rows")
    void rebuildIsRepeatable() {
        demoReset.reset();
        DemoResetService.ResetReport second = demoReset.reset();

        assertThat(second).isEqualTo(new DemoResetService.ResetReport(3, 5, 4, 6));
        assertThat(jdbc.queryForObject(
                "SELECT max(id) FROM bookings", Integer.class)).isEqualTo(6);
    }
}
