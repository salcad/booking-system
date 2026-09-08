package com.ottodot.booking.domain;

import java.time.Instant;

public record Booking(
        long id,
        long studentId,
        long trialClassId,
        BookingStatus status,
        Instant holdExpiresAt,
        Instant createdAt,
        Instant updatedAt) {
}
