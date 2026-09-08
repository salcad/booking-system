package com.ottodot.booking.domain;

public enum BookingStatus {
    /** Seat is held. Counts against capacity. Expires at hold_expires_at. */
    PENDING_PAYMENT,
    /** Paid. Counts against capacity. On the roster. */
    CONFIRMED,
    /** Payment declined. Seat released. Parent may book again. */
    PAYMENT_FAILED,
    /** Released by parent, or by refund after losing the last seat. */
    CANCELLED,
    /** Hold lapsed before payment. Seat released by the reaper. */
    EXPIRED;

    /** A live booking occupies a seat and blocks a duplicate for the same child. */
    public boolean isLive() {
        return this == PENDING_PAYMENT || this == CONFIRMED;
    }
}
