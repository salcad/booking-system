package com.ottodot.booking.service;

import com.ottodot.booking.domain.Booking;
import com.ottodot.booking.domain.PaymentStatus;

/**
 * Returned rather than thrown. A declined payment is a legitimate outcome that
 * must be persisted (booking -> PAYMENT_FAILED, seat released), so it cannot
 * travel as an exception — that would roll the transaction back.
 */
public record PaymentResult(Booking booking, PaymentStatus paymentStatus,
                            Outcome outcome, String message) {

    public enum Outcome {
        CONFIRMED,
        /** Idempotent replay, or the booking was already confirmed. */
        ALREADY_CONFIRMED,
        DECLINED,
        /** Charged, then the seat was gone. Payment has been refunded. */
        SEAT_UNAVAILABLE
    }
}
