package com.ottodot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.ottodot.booking.scheduler.HoldReaper;
import com.ottodot.booking.service.BookingService;
import com.ottodot.booking.service.PaymentResult;
import com.ottodot.booking.service.PaymentService;
import com.ottodot.booking.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The residual race: a payment that lands after its own hold expired.
 *
 * <p>Claiming the seat at booking time removes the race the brief describes,
 * but it cannot remove this one — the parent's checkout simply took longer than
 * the hold window. This is the only place in the system where money is
 * returned, and the only reason the refund path exists at all.
 */
class ConfirmAfterExpiryTest extends AbstractIntegrationTest {

    @Autowired
    BookingService bookingService;

    @Autowired
    PaymentService paymentService;

    @Autowired
    HoldReaper reaper;

    @Test
    @DisplayName("payment after expiry reclaims the seat when it is still free")
    void reclaimsSeatIfStillAvailable() {
        long classId = fixtures.trialClass(4);
        long bookingId = bookingService
                .createBooking(fixtures.student(fixtures.parent()), classId).id();

        clock.advance(Duration.ofMinutes(11));
        reaper.releaseExpiredHolds();
        assertThat(fixtures.bookingStatus(bookingId)).isEqualTo("EXPIRED");

        // Nobody took the seat in the meantime, so the late payment is honoured
        // rather than punished - refunding here would be needless friction.
        PaymentResult result = paymentService.pay(bookingId, false, UUID.randomUUID().toString());

        assertThat(result.outcome()).isEqualTo(PaymentResult.Outcome.CONFIRMED);
        assertThat(fixtures.bookingStatus(bookingId)).isEqualTo("CONFIRMED");
        assertThat(fixtures.claimedSeats(classId)).isEqualTo(1);
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("payment after expiry is refunded when the seat is gone")
    void refundsWhenSeatWasTakenDuringCheckout() {
        long classId = fixtures.trialClassWithConfirmed(4, 3);
        long slowParentsChild = fixtures.student(fixtures.parent());
        long fastParentsChild = fixtures.student(fixtures.parent());

        // A holds the last seat, then stalls in checkout past the hold window.
        long slowBooking = bookingService.createBooking(slowParentsChild, classId).id();
        clock.advance(Duration.ofMinutes(11));
        reaper.releaseExpiredHolds();

        // B takes the freed seat and pays.
        long fastBooking = bookingService.createBooking(fastParentsChild, classId).id();
        paymentService.pay(fastBooking, false, UUID.randomUUID().toString());
        assertThat(fixtures.claimedSeats(classId)).isEqualTo(4);

        // A's payment finally lands. The charge succeeds; the seat does not.
        PaymentResult result = paymentService.pay(slowBooking, false, UUID.randomUUID().toString());

        assertThat(result.outcome()).isEqualTo(PaymentResult.Outcome.SEAT_UNAVAILABLE);
        assertThat(fixtures.bookingStatus(slowBooking)).isEqualTo("CANCELLED");
        assertThat(fixtures.countPaymentAttempts(slowBooking, "REFUNDED"))
                .as("the charge must be returned, not kept")
                .isEqualTo(1);
        assertThat(fixtures.countByStatus(classId, "CONFIRMED"))
                .as("the class must not be overbooked by the late payment")
                .isEqualTo(4);
        assertThat(fixtures.claimedSeats(classId)).isEqualTo(4);
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("a cancelled booking releases its seat and cannot then be paid")
    void cancellationReleasesSeatAndBlocksPayment() {
        long classId = fixtures.trialClass(4);
        long bookingId = bookingService
                .createBooking(fixtures.student(fixtures.parent()), classId).id();

        bookingService.cancel(bookingId);

        assertThat(fixtures.bookingStatus(bookingId)).isEqualTo("CANCELLED");
        assertThat(fixtures.claimedSeats(classId)).isEqualTo(0);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> paymentService.pay(bookingId, false, "k-" + bookingId))
                .isInstanceOf(com.ottodot.booking.error.ApiException.class);
        fixtures.assertInvariants(classId);
    }
}
