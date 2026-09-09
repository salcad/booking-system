package com.ottodot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ottodot.booking.scheduler.HoldReaper;
import com.ottodot.booking.service.BookingService;
import com.ottodot.booking.service.PaymentService;
import com.ottodot.booking.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * The read side of invariant I2.
 *
 * <p>{@code DuplicateBookingTest} proves the write side: a second live booking
 * for one child is refused. This proves the UI can see that coming. The two
 * must agree on what "live" means, because the booking form disables a child
 * on the strength of this endpoint — if it reported a child as free who is then
 * refused, or as booked when they could in fact book, the form would either
 * show an error it promised not to or lock a parent out of a class they are
 * entitled to.
 *
 * <p>So every status that the partial unique index excludes is asserted here
 * too, from the outside, over HTTP.
 */
class StudentBookingStateTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    BookingService bookingService;

    @Autowired
    PaymentService paymentService;

    @Autowired
    HoldReaper reaper;

    @Test
    @DisplayName("a child with no booking reports none")
    void reportsNoBookingForAFreeChild() {
        long parentId = fixtures.parent();
        long studentId = fixtures.student(parentId);
        long classId = fixtures.trialClass(4);

        assertThat(existingBooking(parentId, classId, studentId).isNull()).isTrue();
    }

    @Test
    @DisplayName("a live hold is reported, with its booking id and status")
    void reportsAPendingHold() {
        long parentId = fixtures.parent();
        long studentId = fixtures.student(parentId);
        long classId = fixtures.trialClass(4);

        long bookingId = bookingService.createBooking(studentId, classId).id();

        JsonNode existing = existingBooking(parentId, classId, studentId);
        assertThat(existing.get("bookingId").asLong()).isEqualTo(bookingId);
        assertThat(existing.get("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("a confirmed booking is reported")
    void reportsAConfirmedBooking() {
        long parentId = fixtures.parent();
        long studentId = fixtures.student(parentId);
        long classId = fixtures.trialClass(4);

        long bookingId = bookingService.createBooking(studentId, classId).id();
        paymentService.pay(bookingId, false, UUID.randomUUID().toString());

        JsonNode existing = existingBooking(parentId, classId, studentId);
        assertThat(existing.get("bookingId").asLong()).isEqualTo(bookingId);
        assertThat(existing.get("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("a declined payment leaves the child free to book again")
    void reportsNoBookingAfterPaymentFailure() {
        long parentId = fixtures.parent();
        long studentId = fixtures.student(parentId);
        long classId = fixtures.trialClass(4);

        long bookingId = bookingService.createBooking(studentId, classId).id();
        paymentService.pay(bookingId, true, UUID.randomUUID().toString());
        assertThat(fixtures.bookingStatus(bookingId)).isEqualTo("PAYMENT_FAILED");

        // PAYMENT_FAILED is outside uq_live_booking, so the write side would
        // accept a retry. Reporting it as booked would grey out a button the
        // API is willing to honour.
        assertThat(existingBooking(parentId, classId, studentId).isNull()).isTrue();
    }

    @Test
    @DisplayName("an expired hold leaves the child free to book again")
    void reportsNoBookingAfterHoldExpiry() {
        long parentId = fixtures.parent();
        long studentId = fixtures.student(parentId);
        long classId = fixtures.trialClass(4);

        bookingService.createBooking(studentId, classId);
        clock.advance(Duration.ofMinutes(11));
        reaper.releaseExpiredHolds();

        assertThat(existingBooking(parentId, classId, studentId).isNull()).isTrue();
        fixtures.assertInvariants(classId);
    }

    @Test
    @DisplayName("a booking in another class does not block this one")
    void isScopedToTheClassAsked() {
        long parentId = fixtures.parent();
        long studentId = fixtures.student(parentId);
        long booked = fixtures.trialClass(4);
        long other = fixtures.trialClass(4);

        bookingService.createBooking(studentId, booked);

        assertThat(existingBooking(parentId, booked, studentId).isNull()).isFalse();
        assertThat(existingBooking(parentId, other, studentId).isNull()).isTrue();
    }

    @Test
    @DisplayName("one child's booking does not mark a sibling as booked")
    void isScopedToTheChild() {
        long parentId = fixtures.parent();
        long booked = fixtures.student(parentId);
        long free = fixtures.student(parentId);
        long classId = fixtures.trialClass(4);

        bookingService.createBooking(booked, classId);

        assertThat(existingBooking(parentId, classId, booked).isNull()).isFalse();
        assertThat(existingBooking(parentId, classId, free).isNull()).isTrue();
    }

    @Test
    @DisplayName("without a class the endpoint still lists children, with no booking state")
    void omitsBookingStateWhenNoClassIsNamed() {
        long parentId = fixtures.parent();
        long studentId = fixtures.student(parentId);
        long classId = fixtures.trialClass(4);

        bookingService.createBooking(studentId, classId);

        JsonNode students = http.getForObject("/api/parents/" + parentId + "/students",
                JsonNode.class);
        assertThat(students).hasSize(1);
        assertThat(students.get(0).get("id").asLong()).isEqualTo(studentId);
        assertThat(students.get(0).get("existingBooking").isNull())
                .as("no class was named, so there is nothing to report against")
                .isTrue();
    }

    /** The {@code existingBooking} node for one child, as the booking form sees it. */
    private JsonNode existingBooking(long parentId, long classId, long studentId) {
        JsonNode students = http.getForObject(
                "/api/parents/" + parentId + "/students?trialClassId=" + classId, JsonNode.class);
        for (JsonNode student : students) {
            if (student.get("id").asLong() == studentId) {
                return student.get("existingBooking");
            }
        }
        throw new AssertionError("child " + studentId + " missing from parent " + parentId);
    }
}
