package com.ottodot.booking.payment;

/**
 * The seam a real provider (Stripe, Adyen) would sit behind. Only the mock is
 * implemented; see MockPaymentGateway for what is deliberately not simulated.
 *
 * <p>There is deliberately no refund operation. Every reservation is taken
 * before the charge, so no path can leave a parent charged for a seat they did
 * not get. An asynchronous, webhook-driven provider would reintroduce the need,
 * because the money can arrive after the seat is gone - see the README.
 */
public interface PaymentGateway {

    ChargeResult charge(long bookingId, int amountCents, boolean simulateDecline);
}
