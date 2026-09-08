package com.ottodot.booking.service;

import com.ottodot.booking.domain.Booking;
import com.ottodot.booking.domain.BookingStatus;
import com.ottodot.booking.domain.Student;
import com.ottodot.booking.domain.TrialClass;
import com.ottodot.booking.error.ApiException;
import com.ottodot.booking.repo.BookingEventRepository;
import com.ottodot.booking.repo.BookingRepository;
import com.ottodot.booking.repo.StudentRepository;
import com.ottodot.booking.repo.TrialClassRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final TrialClassRepository classes;
    private final BookingRepository bookings;
    private final StudentRepository students;
    private final BookingEventRepository events;
    private final Clock clock;
    private final Duration holdDuration;

    private final Counter seatConflicts;
    private final Counter duplicatesRejected;

    public BookingService(TrialClassRepository classes,
                          BookingRepository bookings,
                          StudentRepository students,
                          BookingEventRepository events,
                          Clock clock,
                          MeterRegistry metrics,
                          @Value("${booking.hold-duration}") Duration holdDuration) {
        this.classes = classes;
        this.bookings = bookings;
        this.students = students;
        this.events = events;
        this.clock = clock;
        this.holdDuration = holdDuration;
        this.seatConflicts = Counter.builder("booking.seat_claim_conflicts")
                .description("Booking attempts rejected because the class was full").register(metrics);
        this.duplicatesRejected = Counter.builder("booking.duplicates_rejected")
                .description("Booking attempts rejected as duplicates").register(metrics);
    }

    /**
     * Creates a booking and claims a seat immediately.
     *
     * <p>Claiming at booking time rather than at payment time is the central
     * design decision. It means the last-seat race is resolved <em>before</em>
     * anyone is charged: the second parent is rejected here, and never reaches
     * the payment step at all.
     *
     * <p>All of this runs in one transaction, so if the booking insert fails
     * (duplicate) the seat claim is rolled back with it.
     */
    @Transactional
    public Booking createBooking(long studentId, long trialClassId) {
        Student student = students.findById(studentId)
                .orElseThrow(() -> ApiException.notFound("student", studentId));
        TrialClass klass = classes.findById(trialClassId)
                .orElseThrow(() -> ApiException.notFound("trial class", trialClassId));

        // Friendly pre-check. NOT the guarantee — the partial unique index is,
        // and the catch below is what actually holds under concurrency.
        if (bookings.findLive(student.id(), klass.id()).isPresent()) {
            duplicatesRejected.increment();
            throw ApiException.duplicateBooking();
        }

        if (!classes.tryClaimSeat(klass.id())) {
            seatConflicts.increment();
            log.info("seat claim rejected: class {} is full", klass.id());
            throw ApiException.classFull();
        }

        Instant holdExpiresAt = clock.instant().plus(holdDuration);
        long bookingId;
        try {
            bookingId = bookings.insertPending(student.id(), klass.id(), holdExpiresAt);
        } catch (DuplicateKeyException e) {
            // Two concurrent requests for the same child and class both passed
            // the pre-check. The unique index caught the loser; the whole
            // transaction — including its seat claim — rolls back.
            duplicatesRejected.increment();
            throw ApiException.duplicateBooking();
        }

        events.append(bookingId, null, BookingStatus.PENDING_PAYMENT, "seat held", "parent");
        log.info("booking {} created: student={} class={} holdExpiresAt={}",
                bookingId, student.id(), klass.id(), holdExpiresAt);

        return bookings.findById(bookingId).orElseThrow();
    }

    /** Parent-initiated release of a held seat. */
    @Transactional
    public Booking cancel(long bookingId) {
        Booking booking = bookings.lockById(bookingId)
                .orElseThrow(() -> ApiException.notFound("booking", bookingId));

        if (booking.status() == BookingStatus.CANCELLED) {
            return booking;
        }
        if (!booking.status().isLive()) {
            throw ApiException.conflict("NOT_CANCELLABLE",
                    "Booking is " + booking.status() + " and cannot be cancelled.");
        }

        bookings.updateStatus(bookingId, BookingStatus.CANCELLED, null);
        classes.releaseSeat(booking.trialClassId());
        events.append(bookingId, booking.status(), BookingStatus.CANCELLED,
                "cancelled by parent", "parent");

        return bookings.findById(bookingId).orElseThrow();
    }
}
