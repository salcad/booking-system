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
import org.slf4j.Logger;
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
    private final int priceCents;

    private final Counter paymentFailures;
    private final Counter refundsIssued;

    public PaymentService(BookingRepository bookings,
                          TrialClassRepository classes,
                          StudentRepository students,
                          PaymentAttemptRepository payments,
                          BookingEventRepository events,
                          PaymentGateway gateway,
                          MeterRegistry metrics,
                          @Value("${booking.trial-price-cents}") int priceCents) {
        this.bookings = bookings;
        this.classes = classes;
        this.students = students;
        this.payments = payments;
        this.events = events;
        this.gateway = gateway;
        this.priceCents = priceCents;
        this.paymentFailures = Counter.builder("booking.payment_failures")
                .description("Declined payment attempts").register(metrics);
        this.refundsIssued = Counter.builder("booking.refunds_issued")
                .description("Charges refunded because the seat was lost mid-payment")
                .register(metrics);
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

        ChargeResult charge = gateway.charge(bookingId, priceCents, decline);

        if (!charge.approved()) {
            return recordDecline(booking, idempotencyKey, charge);
        }

        long attemptId = payments.insert(bookingId, idempotencyKey, priceCents,
                PaymentStatus.SUCCEEDED, charge.providerRef());

        if (booking.status() == BookingStatus.PENDING_PAYMENT) {
            // The reaper has not run, and cannot run while we hold this row
            // lock. The seat is therefore provably still claimed: confirming
            // converts a held seat into a confirmed one, so claimed_seats is
            // deliberately left unchanged.
            return confirm(booking, "payment succeeded");
        }

        // booking.status() == EXPIRED: the reaper won the row and released the
        // seat before this payment landed. Try to take it back.
        if (classes.tryClaimSeat(booking.trialClassId())) {
            return confirm(booking, "payment succeeded after hold expiry; seat reclaimed");
        }

        // The only path in the system that returns money: charged, but the seat
        // was taken while this parent was checking out.
        gateway.refund(charge.providerRef());
        payments.markRefunded(attemptId);
        refundsIssued.increment();
        bookings.updateStatus(bookingId, BookingStatus.CANCELLED, null);
        events.append(bookingId, booking.status(), BookingStatus.CANCELLED,
                "SEAT_UNAVAILABLE: refunded", "system");
        log.warn("booking {} refunded: seat taken during checkout", bookingId);

        return new PaymentResult(bookings.findById(bookingId).orElseThrow(),
                PaymentStatus.REFUNDED, PaymentResult.Outcome.SEAT_UNAVAILABLE,
                "Your payment was refunded - this seat was taken while you were checking out.");
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
        PaymentResult.Outcome outcome = switch (attempt.status()) {
            case SUCCEEDED -> booking.status() == BookingStatus.CONFIRMED
                    ? PaymentResult.Outcome.ALREADY_CONFIRMED : PaymentResult.Outcome.CONFIRMED;
            case FAILED -> PaymentResult.Outcome.DECLINED;
            case REFUNDED -> PaymentResult.Outcome.SEAT_UNAVAILABLE;
        };
        return new PaymentResult(booking, attempt.status(), outcome,
                "Replayed a previous payment attempt; no new charge was made.");
    }
}
