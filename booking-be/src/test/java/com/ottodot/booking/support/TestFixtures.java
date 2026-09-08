package com.ottodot.booking.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Builds per-test data instead of leaning on the seed file.
 *
 * <p>Every test creates its own class and students, so tests never contend for
 * the same rows and can run against a container shared by the whole suite. The
 * seed data in V2 exists for the demo; asserting against it here would couple
 * the tests to fixtures that are meant to be edited freely.
 */
public class TestFixtures {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final JdbcTemplate jdbc;

    public TestFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long parent() {
        int n = SEQ.incrementAndGet();
        return jdbc.queryForObject(
                "INSERT INTO parents (name, email) VALUES (?, ?) RETURNING id",
                Long.class, "Test Parent " + n, "parent" + n + "@test.example");
    }

    public long student(long parentId) {
        return student(parentId, false);
    }

    public long student(long parentId, boolean alwaysFailsPayment) {
        int n = SEQ.incrementAndGet();
        return jdbc.queryForObject(
                "INSERT INTO students (parent_id, name, grade, always_fails_payment) "
                        + "VALUES (?, ?, 'P4', ?) RETURNING id",
                Long.class, parentId, "Test Student " + n, alwaysFailsPayment);
    }

    /** A fresh class with no seats claimed. */
    public long trialClass(int capacity) {
        return jdbc.queryForObject(
                "INSERT INTO trial_classes (subject, starts_at, capacity, claimed_seats) "
                        + "VALUES (?, now() + INTERVAL '7 days', ?, 0) RETURNING id",
                Long.class, "Test Class " + SEQ.incrementAndGet(), capacity);
    }

    /**
     * A class with {@code confirmed} students already on the roster, each a
     * distinct child. Used to build the "3 of 4 seats gone" race fixture.
     */
    public long trialClassWithConfirmed(int capacity, int confirmed) {
        long classId = trialClass(capacity);
        long parentId = parent();
        for (int i = 0; i < confirmed; i++) {
            confirmBooking(student(parentId), classId);
        }
        return classId;
    }

    /** Inserts a CONFIRMED booking and claims its seat, as the service would. */
    public long confirmBooking(long studentId, long classId) {
        long bookingId = jdbc.queryForObject(
                "INSERT INTO bookings (student_id, trial_class_id, status, hold_expires_at) "
                        + "VALUES (?, ?, 'CONFIRMED', NULL) RETURNING id",
                Long.class, studentId, classId);
        int claimed = jdbc.update(
                "UPDATE trial_classes SET claimed_seats = claimed_seats + 1 "
                        + "WHERE id = ? AND claimed_seats < capacity", classId);
        assertThat(claimed).as("fixture overbooked class %s", classId).isEqualTo(1);
        return bookingId;
    }

    public int claimedSeats(long classId) {
        return jdbc.queryForObject(
                "SELECT claimed_seats FROM trial_classes WHERE id = ?", Integer.class, classId);
    }

    public int capacity(long classId) {
        return jdbc.queryForObject(
                "SELECT capacity FROM trial_classes WHERE id = ?", Integer.class, classId);
    }

    public int countByStatus(long classId, String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM bookings WHERE trial_class_id = ? "
                        + "AND status = ?::booking_status",
                Integer.class, classId, status);
    }

    public int countLive(long classId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM bookings WHERE trial_class_id = ? "
                        + "AND status IN ('PENDING_PAYMENT', 'CONFIRMED')",
                Integer.class, classId);
    }

    public String bookingStatus(long bookingId) {
        return jdbc.queryForObject(
                "SELECT status::text FROM bookings WHERE id = ?", String.class, bookingId);
    }

    public int countPaymentAttempts(long bookingId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payment_attempts WHERE booking_id = ?",
                Integer.class, bookingId);
    }

    public int countPaymentAttempts(long bookingId, String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payment_attempts WHERE booking_id = ? "
                        + "AND status = ?::payment_status",
                Integer.class, bookingId, status);
    }

    public void expireHoldNow(long bookingId) {
        jdbc.update("UPDATE bookings SET hold_expires_at = now() - INTERVAL '1 minute' "
                + "WHERE id = ?", bookingId);
    }

    /**
     * The invariants from the design doc, asserted as a unit.
     *
     * <p>I4 (claimed_seats == live bookings) is the one that catches bugs the
     * others miss: any path that forgets to release or re-claim a seat shows up
     * here as drift, even when every individual status looks correct.
     */
    public void assertInvariants(long classId) {
        int claimed = claimedSeats(classId);
        int live = countLive(classId);
        int capacity = capacity(classId);

        assertThat(claimed)
                .as("I1: claimed_seats (%s) must never exceed capacity (%s)", claimed, capacity)
                .isLessThanOrEqualTo(capacity);
        assertThat(claimed)
                .as("I1: claimed_seats must never go negative")
                .isGreaterThanOrEqualTo(0);
        assertThat(claimed)
                .as("I4: claimed_seats (%s) must equal live bookings (%s) for class %s",
                        claimed, live, classId)
                .isEqualTo(live);
        assertThat(countByStatus(classId, "CONFIRMED"))
                .as("I1: confirmed roster must never exceed capacity")
                .isLessThanOrEqualTo(capacity);
    }
}
