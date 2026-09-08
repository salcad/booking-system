package com.ottodot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.ottodot.booking.service.BookingService;
import com.ottodot.booking.service.PaymentResult;
import com.ottodot.booking.service.PaymentService;
import com.ottodot.booking.service.RosterService;
import com.ottodot.booking.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Invariant I3: a child reaches the roster only by paying.
 *
 * <p>The failure mode this guards against is the expensive one — a class that
 * looks full to the teacher but contains a student who never paid.
 */
class PaymentFailureTest extends AbstractIntegrationTest {

    @Autowired
    BookingService bookingService;

    @Autowired
    PaymentService paymentService;

    @Autowired
    RosterService rosterService;

    @Test
    @DisplayName("a declined payment keeps the child off the roster and frees the seat")
    void declinedPaymentReleasesSeatAndStaysOffRoster() {
        long classId = fixtures.trialClass(4);
        long studentId = fixtures.student(fixtures.parent());

        long bookingId = bookingService.createBooking(studentId, classId).id();
        assertThat(fixtures.claimedSeats(classId)).as("the hold claims a real seat").isEqualTo(1);

        PaymentResult result = paymentService.pay(bookingId, true, UUID.randomUUID().toString());

        assertThat(result.outcome()).isEqualTo(PaymentResult.Outcome.DECLINED);
        assertThat(fixtures.bookingStatus(bookingId)).isEqualTo("PAYMENT_FAILED");
        assertThat(fixtures.claimedSeats(classId))
                .as("the seat returns to the pool immediately")
                .isEqualTo(0);
        assertThat(rosterService.forClass(classId).confirmed()).isEmpty();
        assertThat(rosterService.forClass(classId).pendingHolds()).isEmpty();
        assertThat(fixtures.countPaymentAttempts(bookingId, "FAILED")).isEqualTo(1);
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("a seed student flagged always_fails_payment always declines")
    void studentFlaggedForFailureAlwaysDeclines() {
        long classId = fixtures.trialClass(4);
        long studentId = fixtures.student(fixtures.parent(), true);

        long bookingId = bookingService.createBooking(studentId, classId).id();
        // Requesting SUCCESS: the student flag must still force a decline, so
        // the failure path is reachable from the UI without special input.
        PaymentResult result = paymentService.pay(bookingId, false, UUID.randomUUID().toString());

        assertThat(result.outcome()).isEqualTo(PaymentResult.Outcome.DECLINED);
        assertThat(fixtures.bookingStatus(bookingId)).isEqualTo("PAYMENT_FAILED");
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("a freed seat is immediately usable by another parent")
    void releasedSeatBecomesAvailableToSomeoneElse() {
        // Last seat: taken by a hold that then fails payment.
        long classId = fixtures.trialClassWithConfirmed(4, 3);
        long loserId = fixtures.student(fixtures.parent());
        long winnerId = fixtures.student(fixtures.parent());

        long failing = bookingService.createBooking(loserId, classId).id();
        paymentService.pay(failing, true, UUID.randomUUID().toString());

        long booking = bookingService.createBooking(winnerId, classId).id();
        paymentService.pay(booking, false, UUID.randomUUID().toString());

        assertThat(fixtures.bookingStatus(booking)).isEqualTo("CONFIRMED");
        assertThat(fixtures.countByStatus(classId, "CONFIRMED")).isEqualTo(4);
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("held-but-unpaid bookings are reported apart from the roster")
    void holdsAreNeverMergedIntoTheRoster() {
        long classId = fixtures.trialClass(4);
        long studentId = fixtures.student(fixtures.parent());
        bookingService.createBooking(studentId, classId);

        RosterService.Roster roster = rosterService.forClass(classId);

        assertThat(roster.confirmed()).as("an unpaid hold is not a roster entry").isEmpty();
        assertThat(roster.pendingHolds()).hasSize(1);
        fixtures.assertInvariants(classId);
    }
}
