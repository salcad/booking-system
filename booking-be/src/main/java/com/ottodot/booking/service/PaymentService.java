package com.ottodot.booking.service;

import com.ottodot.booking.domain.Booking;
import com.ottodot.booking.domain.BookingStatus;
import com.ottodot.booking.domain.PaymentAttempt;
import com.ottodot.booking.domain.PaymentStatus;
import com.ottodot.booking.domain.Student;
import com.ottodot.booking.error.ApiException;
import com.ottodot.booking.payment.ChargeResult;
import com.ottodot.booking.payment.PaymentGateway;
import com.ottodot.booking.repo.BookingEventRepository;
import com.ottodot.booking.repo.BookingRepository;
import com.ottodot.booking.repo.PaymentAttemptRepository;
import com.ottodot.booking.repo.StudentRepository;
import com.ottodot.booking.repo.TrialClassRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final BookingRepository bookings;
    private final TrialClassRepository classes;
    private final StudentRepository students;
    private final PaymentAttemptRepository payments;
    private final BookingEventRepository events;
    private final PaymentGateway gateway;
    private final Clock clock;
    private final Duration holdDuration;
    private final int priceCents;

    private final Counter paymentFailures;

    public PaymentService(BookingRepository bookings,
                          TrialClassRepository classes,
                          StudentRepository students,
                          PaymentAttemptRepository payments,
                          BookingEventRepository events,
                          PaymentGateway gateway,
                          MeterRegistry metrics,
                          Clock clock,
                          @Value("${booking.hold-duration}") Duration holdDuration,
                          @Value("${booking.trial-price-cents}") int priceCents) {
        this.bookings = bookings;
        this.classes = classes;
        this.students = students;
        this.payments = payments;
        this.events = events;
        this.gateway = gateway;
        this.clock = clock;
        this.holdDuration = holdDuration;
        this.priceCents = priceCents;
        this.paymentFailures = Counter.builder("booking.payment_failures")
                .description("Declined payment attempts").register(metrics);
    }

    /**
     * Charges for a booking and confirms it.
     *
     * <p>The booking row is locked for the whole transaction, which serialises
     * this against the hold reaper — exactly one of the two acts on a given
     * booking. That lock is what makes the three status branches below safe.
     */
    @Transactional
    public PaymentResult pay(long bookingId, boolean requestDecline, String idempotencyKey) {
        Booking booking = bookings.lockById(bookingId)
                .orElseThrow(() -> ApiException.notFound("booking", bookingId));

        // Idempotency: replaying a request returns the original outcome and
        // never charges twice. Backed by UNIQUE (booking_id, idempotency_key).
        var replay = payments.findByKey(bookingId, idempotencyKey);
        if (replay.isPresent()) {
            log.info("idempotent replay for booking {} key {}", bookingId, idempotencyKey);
            return describeExisting(booking, replay.get());
        }

        if (booking.status() == BookingStatus.CONFIRMED) {
            return new PaymentResult(booking, PaymentStatus.SUCCEEDED,
                    PaymentResult.Outcome.ALREADY_CONFIRMED, "Booking is already confirmed.");
        }
        if (booking.status() != BookingStatus.PENDING_PAYMENT
                && booking.status() != BookingStatus.EXPIRED) {
            throw ApiException.conflict("NOT_PAYABLE",
                    "Booking is " + booking.status() + " and cannot be paid.");
        }

        Student student = students.findById(booking.studentId()).orElseThrow();
        boolean decline = requestDecline || student.alwaysFailsPayment();

        String reason = "payment succeeded";
        if (booking.status() == BookingStatus.EXPIRED) {
            // The reaper won the row and released the seat before this payment
            // landed. Re-acquire everything BEFORE charging, so the gateway is
            // never called unless the outcome is already guaranteed.
            booking = reacquire(booking);
            reason = "payment succeeded after hold expiry; seat reclaimed";
        }

        ChargeResult charge = gateway.charge(bookingId, priceCents, decline);

        if (!charge.approved()) {
            return recordDecline(booking, idempotencyKey, charge);
        }

        payments.insert(bookingId, idempotencyKey, priceCents,
                PaymentStatus.SUCCEEDED, charge.providerRef());

        // The booking is PENDING_PAYMENT and its row is locked, so the reaper
        // cannot touch it; the seat is provably claimed and the live-booking
        // slot is provably ours. Confirming therefore cannot fail: it changes
        // status within the set the unique index covers, and leaves
        // claimed_seats alone because a held seat is already a claimed one.
        return confirm(booking, reason);
    }

    /**
     * Takes back the seat and the live-booking slot for an expired hold.
     *
     * <p>Both reservations happen before any charge, which is the whole point.
     * Charging first and reserving afterwards means every failure past that
     * line is a failure holding someone's money: a lost seat becomes a refund,
     * and a duplicate booking becomes a constraint violation that rolls the
     * payment record back while the money stays gone.
     *
     * <p>Neither failure below charges anyone.
     */
    private Booking reacquire(Booking booking) {
        if (!classes.tryClaimSeat(booking.trialClassId())) {
            log.info("booking {} cannot be paid: seat taken during checkout", booking.id());
            throw ApiException.conflict("SEAT_UNAVAILABLE",
                    "This seat was taken while you were checking out. You have not been charged.");
        }

        try {
            if (!bookings.reopenHold(booking.id(), clock.instant().plus(holdDuration))) {
                // Another request reopened it first; that request owns the slot.
                throw ApiException.conflict("NOT_PAYABLE",
                        "This booking is already being paid for.");
            }
        } catch (DuplicateKeyException e) {
            // The child acquired another live booking for this class while this
            // hold was expired. The whole transaction rolls back, which undoes
            // the seat claim above - and, crucially, no charge has happened.
            log.info("booking {} cannot be paid: child already has a live booking", booking.id());
            throw ApiException.duplicateBooking();
        }

        return bookings.findById(booking.id()).orElseThrow();
    }

    private PaymentResult confirm(Booking booking, String reason) {
        bookings.updateStatus(booking.id(), BookingStatus.CONFIRMED, null);
        events.append(booking.id(), booking.status(), BookingStatus.CONFIRMED, reason, "parent");
        log.info("booking {} CONFIRMED ({})", booking.id(), reason);
        return new PaymentResult(bookings.findById(booking.id()).orElseThrow(),
                PaymentStatus.SUCCEEDED, PaymentResult.Outcome.CONFIRMED, "Booking confirmed.");
    }

    private PaymentResult recordDecline(Booking booking, String idempotencyKey,
                                        ChargeResult charge) {
        payments.insert(booking.id(), idempotencyKey, priceCents,
                PaymentStatus.FAILED, null);
        paymentFailures.increment();

        // Release the seat so it is immediately available to someone else. The
        // child is never added to the roster: only CONFIRMED appears there.
        if (booking.status() == BookingStatus.PENDING_PAYMENT) {
            classes.releaseSeat(booking.trialClassId());
        }
        bookings.updateStatus(booking.id(), BookingStatus.PAYMENT_FAILED, null);
        events.append(booking.id(), booking.status(), BookingStatus.PAYMENT_FAILED,
                charge.declineReason(), "parent");
        log.info("booking {} PAYMENT_FAILED ({})", booking.id(), charge.declineReason());

        return new PaymentResult(bookings.findById(booking.id()).orElseThrow(),
                PaymentStatus.FAILED, PaymentResult.Outcome.DECLINED,
                "Payment was declined. The seat has been released; you can try booking again.");
    }

    private PaymentResult describeExisting(Booking booking, PaymentAttempt attempt) {
        if (attempt.status() == PaymentStatus.SUCCEEDED
                && booking.status() != BookingStatus.CONFIRMED) {
            // A successful charge always confirms in the same transaction, so
            // this combination should be unreachable. Say so loudly rather than
            // reporting the booking as confirmed when it demonstrably is not.
            log.error("booking {} has a SUCCEEDED payment but status {}",
                    booking.id(), booking.status());
        }
        PaymentResult.Outcome outcome = switch (attempt.status()) {
            case SUCCEEDED -> PaymentResult.Outcome.ALREADY_CONFIRMED;
            case FAILED -> PaymentResult.Outcome.DECLINED;
            case REFUNDED -> PaymentResult.Outcome.SEAT_UNAVAILABLE;
        };
        return new PaymentResult(booking, attempt.status(), outcome,
                "Replayed a previous payment attempt; no new charge was made.");
    }
}
