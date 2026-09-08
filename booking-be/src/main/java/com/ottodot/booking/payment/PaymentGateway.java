package com.ottodot.booking.payment;

/**
 * The seam a real provider (Stripe, Adyen) would sit behind. Only the mock is
 * implemented; see MockPaymentGateway for what is deliberately not simulated.
 */
public interface PaymentGateway {

    ChargeResult charge(long bookingId, int amountCents, boolean simulateDecline);

    /**
     * Compensating action for the one case where a charge succeeds but the seat
     * cannot be granted. See PaymentService.
     */
    void refund(String providerRef);
}
