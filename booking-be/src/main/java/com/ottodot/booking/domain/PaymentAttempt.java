package com.ottodot.booking.domain;

import java.time.Instant;

public record PaymentAttempt(
        long id,
        long bookingId,
        String idempotencyKey,
        int amountCents,
        PaymentStatus status,
        String providerRef,
        Instant createdAt) {
}
