package com.ottodot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.ottodot.booking.scheduler.HoldReaper;
import com.ottodot.booking.service.BookingService;
import com.ottodot.booking.service.PaymentService;
import com.ottodot.booking.support.AbstractIntegrationTest;
import com.ottodot.booking.support.Concurrency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Regression test for the reaper/payment collision.
 *
 * <p>The reaper and the payment path both mutate a booking and its class's seat
 * count. If the sweep is not genuinely transactional, its FOR UPDATE lock is
 * released the moment the SELECT returns, and it can then expire a booking that
 * a payment confirmed in the meantime — decrementing claimed_seats for a
 * student who is on the roster. Nothing about the individual statuses looks
 * wrong afterwards; only invariant I4 catches it.
 *
 * <p>Every hold here is already past its expiry when the race starts, so the
 * two paths are guaranteed to contend for exactly the same rows.
 */
class ReaperPaymentRaceTest extends AbstractIntegrationTest {

    @Autowired
    BookingService bookingService;

    @Autowired
    PaymentService paymentService;

    @Autowired
    HoldReaper reaper;

    @RepeatedTest(10)
    @DisplayName("paying while the reaper sweeps never desynchronises seats from bookings")
    void concurrentSweepAndPaymentKeepSeatsConsistent() {
        int payers = 12;
        int sweepers = 4;
        // Capacity comfortably exceeds the contenders: this test is about the
        // reaper interaction, not about capacity, so nobody should be refused.
        long classId = fixtures.trialClass(20);
        long parentId = fixtures.parent();

        List<Long> bookingIds = java.util.stream.IntStream.range(0, payers)
                .mapToObj(i -> bookingService.createBooking(fixtures.student(parentId), classId).id())
                .toList();
        bookingIds.forEach(fixtures::expireHoldNow);

        Concurrency.inParallel(payers + sweepers, i -> {
            if (i < payers) {
                return paymentService.pay(bookingIds.get(i), false,
                        UUID.randomUUID().toString()).outcome().name();
            }
            for (int s = 0; s < 5; s++) {
                reaper.releaseExpiredHolds();
            }
            return "SWEPT";
        });

        // Whichever path won each row, a confirmed booking must still own a
        // seat and an expired one must not.
        for (long bookingId : bookingIds) {
            assertThat(fixtures.bookingStatus(bookingId))
                    .as("booking %s ended in an unexpected state", bookingId)
                    .isIn("CONFIRMED", "EXPIRED", "CANCELLED");
        }
        assertThat(fixtures.countByStatus(classId, "CONFIRMED"))
                .as("a late payment reclaims its seat when the class has room")
                .isEqualTo(payers);
        fixtures.assertInvariants(classId);
    }
}
