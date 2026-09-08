package com.ottodot.booking.web;

import com.ottodot.booking.domain.Booking;
import com.ottodot.booking.domain.BookingEvent;
import com.ottodot.booking.domain.BookingStatus;
import com.ottodot.booking.domain.TrialClass;
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

    public record StudentView(long id, String name, String grade) {
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
