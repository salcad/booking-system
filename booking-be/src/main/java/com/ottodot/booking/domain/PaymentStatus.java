package com.ottodot.booking.domain;

public enum PaymentStatus {
    SUCCEEDED,
    FAILED,
    /** Charged, but the seat was gone by confirmation time. */
    REFUNDED
}
