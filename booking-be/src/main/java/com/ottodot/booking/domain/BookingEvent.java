package com.ottodot.booking.domain;

import java.time.Instant;

public record BookingEvent(
        long id,
        long bookingId,
        BookingStatus fromStatus,
        BookingStatus toStatus,
        String reason,
        String actor,
        Instant createdAt) {
}
