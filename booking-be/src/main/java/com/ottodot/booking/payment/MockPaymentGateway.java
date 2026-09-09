package com.ottodot.booking.payment;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deterministic stand-in for a real payment provider.
 *
 * <p>Declines are driven explicitly — by the caller's requested outcome, or by
 * a student flagged always_fails_payment in the seed data — so the failure path
 * is reachable on demand rather than by chance.
 *
 * <p>Deliberately NOT simulated: network latency, provider timeouts, async
 * webhooks, partial captures, 3-D Secure. A real integration would be webhook
 * driven, which changes the confirmation path from synchronous to eventual;
 * that is called out in the README as the main thing this mock hides.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    @Override
    public ChargeResult charge(long bookingId, int amountCents, boolean simulateDecline) {
        if (simulateDecline) {
            log.info("mock charge DECLINED bookingId={} amountCents={}", bookingId, amountCents);
            return ChargeResult.declined("card_declined");
        }
        String ref = "mock_" + UUID.randomUUID().toString().substring(0, 12);
        log.info("mock charge APPROVED bookingId={} amountCents={} providerRef={}",
                bookingId, amountCents, ref);
        return ChargeResult.approved(ref);
    }
}
