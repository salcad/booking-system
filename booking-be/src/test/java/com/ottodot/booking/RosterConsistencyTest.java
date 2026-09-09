package com.ottodot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.ottodot.booking.service.BookingService;
import com.ottodot.booking.service.PaymentService;
import com.ottodot.booking.service.RosterService;
import com.ottodot.booking.support.AbstractIntegrationTest;
import com.ottodot.booking.support.Concurrency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A roster response must describe one moment in time.
 *
 * <p>Regression: the roster used to read the class row, the confirmed
 * bookings and the pending holds as three statements in a READ COMMITTED
 * transaction. Read-only is not the same as consistent - each statement took
 * its own snapshot, so a payment committing between two of them could drop a
 * child out of both lists: no longer PENDING_PAYMENT when the pending query
 * ran, not yet visible to the confirmed query that had already returned.
 *
 * <p>The teacher-facing symptom is a child who is paid and holds a seat, but
 * appears nowhere on the roster page.
 */
class RosterConsistencyTest extends AbstractIntegrationTest {

    @Autowired
    BookingService bookingService;

    @Autowired
    PaymentService paymentService;

    @Autowired
    RosterService rosterService;

    private static final int CHILDREN = 8;

    @Test
    @DisplayName("no child disappears from the roster while payments are committing")
    void everyLiveBookingAppearsInExactlyOneListDuringConcurrentPayments() {
        long classId = fixtures.trialClass(CHILDREN);
        long parentId = fixtures.parent();

        List<Long> bookingIds = java.util.stream.IntStream.range(0, CHILDREN)
                .mapToObj(i -> bookingService.createBooking(fixtures.student(parentId), classId).id())
                .toList();

        AtomicBoolean paymentsDone = new AtomicBoolean(false);

        // One thread pays every booking in turn; the other reads the roster in
        // a tight loop. Under the old implementation the reader observes a
        // total below CHILDREN as soon as a payment lands mid-read.
        List<Integer> results = Concurrency.inParallel(2, index -> {
            if (index == 0) {
                for (long bookingId : bookingIds) {
                    paymentService.pay(bookingId, false, UUID.randomUUID().toString());
                }
                paymentsDone.set(true);
                return -1;
            }

            int worstTotal = CHILDREN;
            int reads = 0;
            while (!paymentsDone.get() || reads < 50) {
                RosterService.Roster roster = rosterService.forClass(classId);
                worstTotal = Math.min(worstTotal,
                        roster.confirmed().size() + roster.pendingHolds().size());
                reads++;
            }
            return worstTotal;
        });

        int worstTotal = results.get(1);
        assertThat(worstTotal)
                .as("every one of the %s children must be in exactly one list on every read, "
                        + "but one read saw only %s", CHILDREN, worstTotal)
                .isEqualTo(CHILDREN);

        RosterService.Roster finalRoster = rosterService.forClass(classId);
        assertThat(finalRoster.confirmed()).hasSize(CHILDREN);
        assertThat(finalRoster.pendingHolds()).isEmpty();
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("the seat count agrees with the lists in the same response")
    void classCountsMatchTheListsInTheSameSnapshot() {
        long classId = fixtures.trialClass(4);
        long parentId = fixtures.parent();

        long paid = bookingService.createBooking(fixtures.student(parentId), classId).id();
        paymentService.pay(paid, false, UUID.randomUUID().toString());
        bookingService.createBooking(fixtures.student(parentId), classId);

        RosterService.Roster roster = rosterService.forClass(classId);

        assertThat(roster.confirmed()).hasSize(1);
        assertThat(roster.pendingHolds()).hasSize(1);
        assertThat(roster.trialClass().claimedSeats())
                .as("I4 must hold within a single response, not just in the database")
                .isEqualTo(roster.confirmed().size() + roster.pendingHolds().size());
        fixtures.assertInvariants(classId);
    }
}
