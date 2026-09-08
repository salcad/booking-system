package com.ottodot.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ottodot.booking.error.ApiException;
import com.ottodot.booking.service.BookingService;
import com.ottodot.booking.service.PaymentService;
import com.ottodot.booking.support.AbstractIntegrationTest;
import com.ottodot.booking.support.Concurrency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Invariant I2: one live booking per (child, class). */
class DuplicateBookingTest extends AbstractIntegrationTest {

    @Autowired
    BookingService bookingService;

    @Autowired
    PaymentService paymentService;

    @Test
    @DisplayName("a second booking for the same child and class is rejected")
    void rejectsDuplicateWhileHoldIsLive() {
        long classId = fixtures.trialClass(4);
        long studentId = fixtures.student(fixtures.parent());

        bookingService.createBooking(studentId, classId);

        assertThatThrownBy(() -> bookingService.createBooking(studentId, classId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("DUPLICATE_BOOKING"));

        assertThat(fixtures.claimedSeats(classId))
                .as("the rejected attempt must not have consumed a second seat")
                .isEqualTo(1);
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("a confirmed child cannot book the same class twice")
    void rejectsDuplicateAfterConfirmation() {
        long classId = fixtures.trialClass(4);
        long studentId = fixtures.student(fixtures.parent());

        long bookingId = bookingService.createBooking(studentId, classId).id();
        paymentService.pay(bookingId, false, UUID.randomUUID().toString());

        assertThatThrownBy(() -> bookingService.createBooking(studentId, classId))
                .isInstanceOf(ApiException.class);
        assertThat(fixtures.claimedSeats(classId)).isEqualTo(1);
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("after a failed payment the same child may book again")
    void allowsRetryAfterPaymentFailure() {
        long classId = fixtures.trialClass(4);
        long studentId = fixtures.student(fixtures.parent());

        long failed = bookingService.createBooking(studentId, classId).id();
        paymentService.pay(failed, true, UUID.randomUUID().toString());
        assertThat(fixtures.bookingStatus(failed)).isEqualTo("PAYMENT_FAILED");

        // PAYMENT_FAILED is excluded from the partial unique index precisely so
        // that a parent whose card was declined is not locked out of the class.
        long retry = bookingService.createBooking(studentId, classId).id();
        paymentService.pay(retry, false, UUID.randomUUID().toString());

        assertThat(fixtures.bookingStatus(retry)).isEqualTo("CONFIRMED");
        assertThat(fixtures.claimedSeats(classId)).isEqualTo(1);
        fixtures.assertInvariants(classId);
    }

    @RepeatedTest(10)
    @DisplayName("concurrent double-submit for one child yields exactly one booking")
    void concurrentDuplicatesCollapseToOne() {
        int attempts = 8;
        long classId = fixtures.trialClass(4);
        long studentId = fixtures.student(fixtures.parent());

        // The service pre-check cannot help here: all 8 read "no live booking"
        // before any of them writes. Only the partial unique index decides, and
        // the loser's whole transaction - including its seat claim - rolls back.
        List<String> outcomes = Concurrency.inParallel(attempts, i -> {
            try {
                bookingService.createBooking(studentId, classId);
                return "CREATED";
            } catch (ApiException e) {
                return e.getCode();
            }
        });

        assertThat(outcomes.stream().filter("CREATED"::equals).count()).isEqualTo(1);
        assertThat(fixtures.countLive(classId)).isEqualTo(1);
        assertThat(fixtures.claimedSeats(classId))
                .as("rolled-back duplicates must not leak seats")
                .isEqualTo(1);
        fixtures.assertInvariants(classId);
    }
}
