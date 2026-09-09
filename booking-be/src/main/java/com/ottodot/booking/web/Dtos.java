package com.ottodot.booking.web;

import com.ottodot.booking.domain.Booking;
import com.ottodot.booking.domain.BookingEvent;
import com.ottodot.booking.domain.BookingStatus;
import com.ottodot.booking.domain.TrialClass;
import com.ottodot.booking.repo.StudentRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public final class Dtos {

    private Dtos() {
    }

    public record TrialClassView(long id, String subject, Instant startsAt,
                                 int capacity, int seatsRemaining, boolean full) {
        public static TrialClassView of(TrialClass c) {
            return new TrialClassView(c.id(), c.subject(), c.startsAt(), c.capacity(),
                    c.seatsRemaining(), c.isFull());
        }
    }

    /**
     * A child, optionally with the booking they already hold for the class the
     * caller asked about.
     *
     * <p>{@code existingBooking} is null both when the child has no live
     * booking and when no class was named in the request, which is why the
     * field is a nested object rather than loose id/status columns: absent and
     * "asked, and there is none" collapse to the same JSON either way, and the
     * nesting at least keeps the two fields from drifting apart.
     */
    public record StudentView(long id, String name, String grade,
                              ExistingBooking existingBooking) {
        public static StudentView of(StudentRepository.StudentBooking s) {
            return new StudentView(s.id(), s.name(), s.grade(),
                    s.liveBookingId() == null ? null
                            : new ExistingBooking(s.liveBookingId(), s.liveBookingStatus()));
        }
    }

    /** The live booking blocking a second one for this child, per invariant I2. */
    public record ExistingBooking(long bookingId, BookingStatus status) {
    }

    public record LoginRequest(@NotBlank String password) {
    }

    /** The token the caller must present on every subsequent request. */
    public record LoginResponse(String token, Instant expiresAt) {
    }

    public record SessionView(boolean authenticated) {
    }

    public record CreateBookingRequest(@NotNull Long studentId, @NotNull Long trialClassId) {
    }

    public record BookingView(long id, long studentId, long trialClassId, BookingStatus status,
                              Instant holdExpiresAt, int amountCents, List<EventView> history) {
        public static BookingView of(Booking b, int amountCents, List<EventView> history) {
            return new BookingView(b.id(), b.studentId(), b.trialClassId(), b.status(),
                    b.holdExpiresAt(), amountCents, history);
        }
    }

    public record EventView(BookingStatus fromStatus, BookingStatus toStatus,
                            String reason, String actor, Instant at) {
        public static EventView of(BookingEvent e) {
            return new EventView(e.fromStatus(), e.toStatus(), e.reason(), e.actor(),
                    e.createdAt());
        }
    }

    /**
     * outcome drives the mock gateway so the failure path is demonstrable from
     * the UI. idempotencyKey makes retries and double-clicks safe.
     */
    public record PaymentRequest(@NotNull Outcome outcome, @NotBlank String idempotencyKey) {
        public enum Outcome { SUCCESS, FAILURE }
    }

    public record PaymentResponse(long bookingId, BookingStatus status, String paymentStatus,
                                  String outcome, String message) {
    }
}
