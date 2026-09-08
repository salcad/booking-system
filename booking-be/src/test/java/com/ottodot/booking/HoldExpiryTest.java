package com.ottodot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.ottodot.booking.scheduler.HoldReaper;
import com.ottodot.booking.service.BookingService;
import com.ottodot.booking.service.PaymentService;
import com.ottodot.booking.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Abandoned checkouts must not hold a seat forever.
 *
 * <p>Claiming the seat at booking time is what makes the last-seat race safe,
 * but it borrows a seat from the pool on the strength of an intention to pay.
 * The reaper is what makes that loan temporary.
 */
class HoldExpiryTest extends AbstractIntegrationTest {

    @Autowired
    BookingService bookingService;

    @Autowired
    PaymentService paymentService;

    @Autowired
    HoldReaper reaper;

    @Test
    @DisplayName("an abandoned hold expires and returns its seat to the pool")
    void expiredHoldReleasesItsSeat() {
        long classId = fixtures.trialClass(4);
        long bookingId = bookingService
                .createBooking(fixtures.student(fixtures.parent()), classId).id();
        assertThat(fixtures.claimedSeats(classId)).isEqualTo(1);

        clock.advance(Duration.ofMinutes(11));
        int released = reaper.releaseExpiredHolds();

        // The sweep is global, so it may also collect holds left by sibling
        // tests sharing this database. Only this class's outcome is asserted.
        assertThat(released).isGreaterThanOrEqualTo(1);
        assertThat(fixtures.bookingStatus(bookingId)).isEqualTo("EXPIRED");
        assertThat(fixtures.claimedSeats(classId)).isEqualTo(0);
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("a hold inside its window is left alone")
    void liveHoldSurvivesTheSweep() {
        long classId = fixtures.trialClass(4);
        long bookingId = bookingService
                .createBooking(fixtures.student(fixtures.parent()), classId).id();

        clock.advance(Duration.ofMinutes(9));
        reaper.releaseExpiredHolds();

        assertThat(fixtures.bookingStatus(bookingId)).isEqualTo("PENDING_PAYMENT");
        assertThat(fixtures.claimedSeats(classId)).isEqualTo(1);
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("the reaper never touches a confirmed booking")
    void confirmedBookingsAreNotExpired() {
        long classId = fixtures.trialClass(4);
        long bookingId = bookingService
                .createBooking(fixtures.student(fixtures.parent()), classId).id();
        paymentService.pay(bookingId, false, UUID.randomUUID().toString());

        clock.advance(Duration.ofHours(2));
        reaper.releaseExpiredHolds();

        assertThat(fixtures.countByStatus(classId, "EXPIRED"))
                .as("nothing in this class was eligible for expiry")
                .isZero();
        assertThat(fixtures.bookingStatus(bookingId)).isEqualTo("CONFIRMED");
        assertThat(fixtures.claimedSeats(classId))
                .as("a paid seat must survive any number of sweeps")
                .isEqualTo(1);
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("a released seat lets a waiting parent into a previously full class")
    void expiredHoldUnblocksAFullClass() {
        long classId = fixtures.trialClassWithConfirmed(4, 3);
        long abandonerId = fixtures.student(fixtures.parent());
        long waiterId = fixtures.student(fixtures.parent());

        bookingService.createBooking(abandonerId, classId);
        assertThat(fixtures.claimedSeats(classId)).as("class is now full").isEqualTo(4);

        clock.advance(Duration.ofMinutes(11));
        reaper.releaseExpiredHolds();

        long booking = bookingService.createBooking(waiterId, classId).id();
        paymentService.pay(booking, false, UUID.randomUUID().toString());

        assertThat(fixtures.bookingStatus(booking)).isEqualTo("CONFIRMED");
        assertThat(fixtures.countByStatus(classId, "CONFIRMED")).isEqualTo(4);
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("the sweep is idempotent")
    void repeatedSweepsDoNotDoubleRelease() {
        long classId = fixtures.trialClass(4);
        bookingService.createBooking(fixtures.student(fixtures.parent()), classId);

        clock.advance(Duration.ofMinutes(11));
        reaper.releaseExpiredHolds();
        reaper.releaseExpiredHolds();
        reaper.releaseExpiredHolds();

        assertThat(fixtures.claimedSeats(classId))
                .as("a second sweep must not decrement the count again")
                .isEqualTo(0);
        fixtures.assertInvariants(classId);
    }
}
