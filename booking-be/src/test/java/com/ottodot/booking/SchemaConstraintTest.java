package com.ottodot.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ottodot.booking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Defence in depth: the invariants must survive a broken service layer.
 *
 * <p>These tests bypass the application entirely and attack the tables with raw
 * SQL. If a future refactor loses the conditional UPDATE, the database itself
 * still refuses to overbook — that is the difference between an invariant and a
 * convention.
 */
class SchemaConstraintTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("the database rejects claiming more seats than capacity")
    void checkConstraintBlocksOverbooking() {
        long classId = fixtures.trialClass(4);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE trial_classes SET claimed_seats = capacity + 1 WHERE id = ?", classId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(fixtures.claimedSeats(classId)).isZero();
    }

    @Test
    @DisplayName("the database rejects a negative seat count")
    void checkConstraintBlocksNegativeSeats() {
        long classId = fixtures.trialClass(4);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE trial_classes SET claimed_seats = -1 WHERE id = ?", classId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the conditional claim matches nothing once the class is full")
    void conditionalClaimStopsAtCapacity() {
        long classId = fixtures.trialClass(2);

        assertThat(claim(classId)).isEqualTo(1);
        assertThat(claim(classId)).isEqualTo(1);
        assertThat(claim(classId))
                .as("the third claim must match zero rows rather than overbook")
                .isZero();
        assertThat(fixtures.claimedSeats(classId)).isEqualTo(2);
    }

    @Test
    @DisplayName("the partial unique index rejects a second live booking")
    void uniqueIndexBlocksDuplicateLiveBooking() {
        long classId = fixtures.trialClass(4);
        long studentId = fixtures.student(fixtures.parent());
        insertBooking(studentId, classId, "CONFIRMED");

        assertThatThrownBy(() -> insertBooking(studentId, classId, "PENDING_PAYMENT"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the index is partial, so terminal bookings never block a retry")
    void terminalStatusesAreExcludedFromTheIndex() {
        long classId = fixtures.trialClass(4);
        long studentId = fixtures.student(fixtures.parent());

        insertBooking(studentId, classId, "PAYMENT_FAILED");
        insertBooking(studentId, classId, "EXPIRED");
        insertBooking(studentId, classId, "CANCELLED");

        assertThatCode(() -> insertBooking(studentId, classId, "CONFIRMED"))
                .as("a child with three dead bookings may still book the class")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("payment idempotency keys are unique per booking")
    void idempotencyKeyIsUniquePerBooking() {
        long classId = fixtures.trialClass(4);
        long studentId = fixtures.student(fixtures.parent());
        long bookingId = insertBooking(studentId, classId, "PENDING_PAYMENT");

        insertAttempt(bookingId, "same-key");
        assertThatThrownBy(() -> insertAttempt(bookingId, "same-key"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private int claim(long classId) {
        return jdbc.update("UPDATE trial_classes SET claimed_seats = claimed_seats + 1 "
                + "WHERE id = ? AND claimed_seats < capacity", classId);
    }

    private long insertBooking(long studentId, long classId, String status) {
        return jdbc.queryForObject(
                "INSERT INTO bookings (student_id, trial_class_id, status) "
                        + "VALUES (?, ?, ?::booking_status) RETURNING id",
                Long.class, studentId, classId, status);
    }

    private void insertAttempt(long bookingId, String key) {
        jdbc.update("INSERT INTO payment_attempts "
                + "(booking_id, idempotency_key, amount_cents, status) "
                + "VALUES (?, ?, 4900, 'SUCCEEDED')", bookingId, key);
    }
}
