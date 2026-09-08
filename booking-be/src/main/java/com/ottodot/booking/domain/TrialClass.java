package com.ottodot.booking.domain;

import java.time.Instant;

public record TrialClass(
        long id,
        String subject,
        Instant startsAt,
        int capacity,
        int claimedSeats) {

    public int seatsRemaining() {
        return Math.max(0, capacity - claimedSeats);
    }

    public boolean isFull() {
        return claimedSeats >= capacity;
    }
}
