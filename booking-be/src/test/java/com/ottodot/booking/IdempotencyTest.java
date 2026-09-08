package com.ottodot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.ottodot.booking.service.BookingService;
import com.ottodot.booking.service.PaymentResult;
import com.ottodot.booking.service.PaymentService;
import com.ottodot.booking.support.AbstractIntegrationTest;
import com.ottodot.booking.support.Concurrency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A parent double-clicking "Pay" must not be charged twice.
 *
 * <p>Every charge writes exactly one payment_attempts row before anything else
 * happens, so the row count is a faithful proxy for the number of times the
 * gateway was called.
 */
class IdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    BookingService bookingService;

    @Autowired
    PaymentService paymentService;

    @Test
    @DisplayName("replaying a payment with the same key charges once")
    void replayWithSameKeyDoesNotChargeTwice() {
        long classId = fixtures.trialClass(4);
        long bookingId = bookingService
                .createBooking(fixtures.student(fixtures.parent()), classId).id();
        String key = UUID.randomUUID().toString();

        PaymentResult first = paymentService.pay(bookingId, false, key);
        PaymentResult replay = paymentService.pay(bookingId, false, key);

        assertThat(first.outcome()).isEqualTo(PaymentResult.Outcome.CONFIRMED);
        assertThat(replay.paymentStatus()).isEqualTo(first.paymentStatus());
        assertThat(fixtures.countPaymentAttempts(bookingId))
                .as("one key must mean one charge")
                .isEqualTo(1);
        assertThat(fixtures.bookingStatus(bookingId)).isEqualTo("CONFIRMED");
        assertThat(fixtures.claimedSeats(classId)).isEqualTo(1);
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("replaying a declined payment returns the decline, not a new charge")
    void replayOfDeclineIsStable() {
        long classId = fixtures.trialClass(4);
        long bookingId = bookingService
                .createBooking(fixtures.student(fixtures.parent()), classId).id();
        String key = UUID.randomUUID().toString();

        paymentService.pay(bookingId, true, key);
        PaymentResult replay = paymentService.pay(bookingId, true, key);

        assertThat(replay.outcome()).isEqualTo(PaymentResult.Outcome.DECLINED);
        assertThat(fixtures.countPaymentAttempts(bookingId)).isEqualTo(1);
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("paying an already-confirmed booking is a no-op, not a second charge")
    void payingConfirmedBookingWithNewKeyDoesNotCharge() {
        long classId = fixtures.trialClass(4);
        long bookingId = bookingService
                .createBooking(fixtures.student(fixtures.parent()), classId).id();

        paymentService.pay(bookingId, false, UUID.randomUUID().toString());
        // A different key: the idempotency table cannot help here, so the
        // status check is what prevents the second charge.
        PaymentResult second = paymentService.pay(bookingId, false, UUID.randomUUID().toString());

        assertThat(second.outcome()).isEqualTo(PaymentResult.Outcome.ALREADY_CONFIRMED);
        assertThat(fixtures.countPaymentAttempts(bookingId)).isEqualTo(1);
        assertThat(fixtures.claimedSeats(classId)).isEqualTo(1);
        fixtures.assertInvariants(classId);
    }

    @RepeatedTest(10)
    @DisplayName("a concurrent double-submit charges once")
    void concurrentSubmitsWithSameKeyChargeOnce() {
        long classId = fixtures.trialClass(4);
        long bookingId = bookingService
                .createBooking(fixtures.student(fixtures.parent()), classId).id();
        String key = UUID.randomUUID().toString();

        List<String> outcomes = Concurrency.inParallel(6,
                i -> paymentService.pay(bookingId, false, key).outcome().name());

        assertThat(outcomes).allSatisfy(o ->
                assertThat(o).isIn("CONFIRMED", "ALREADY_CONFIRMED"));
        assertThat(fixtures.countPaymentAttempts(bookingId))
                .as("six simultaneous clicks, one charge")
                .isEqualTo(1);
        assertThat(fixtures.claimedSeats(classId)).isEqualTo(1);
        fixtures.assertInvariants(classId);
    }
}
