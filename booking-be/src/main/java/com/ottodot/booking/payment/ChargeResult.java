package com.ottodot.booking.payment;

public record ChargeResult(boolean approved, String providerRef, String declineReason) {

    public static ChargeResult approved(String providerRef) {
        return new ChargeResult(true, providerRef, null);
    }

    public static ChargeResult declined(String reason) {
        return new ChargeResult(false, null, reason);
    }
}
