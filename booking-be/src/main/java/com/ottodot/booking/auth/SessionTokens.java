package com.ottodot.booking.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Mints and verifies session tokens.
 *
 * <p>The token carries its own expiry and an HMAC over it, so verifying a
 * request needs no session table and no round trip - which matters because
 * every single API call is verified. The tradeoff is that an issued token
 * cannot be revoked before it expires; rotating {@code booking.auth.secret}
 * (or restarting, when it is generated) invalidates every token at once, which
 * is the only revocation this demo needs.
 */
@Component
public class SessionTokens {

    private static final Logger log = LoggerFactory.getLogger(SessionTokens.class);
    private static final String ALGORITHM = "HmacSHA256";
    /** Guards against a future format change being accepted as the current one. */
    private static final String VERSION = "v1";

    private final byte[] key;
    private final Clock clock;
    private final java.time.Duration sessionDuration;

    public SessionTokens(AuthProperties properties, Clock clock) {
        this.clock = clock;
        this.sessionDuration = properties.sessionDuration();
        this.key = resolveKey(properties.secret());
    }

    private static byte[] resolveKey(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.getBytes(StandardCharsets.UTF_8);
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        log.warn("booking.auth.secret is unset - generated a random signing key. "
                + "Sessions will not survive a restart and will not be shared across instances.");
        return random;
    }

    /** @return a token valid until {@code now + sessionDuration}. */
    public Token issue() {
        Instant expiresAt = clock.instant().plus(sessionDuration);
        String payload = VERSION + "." + expiresAt.getEpochSecond();
        return new Token(payload + "." + sign(payload), expiresAt);
    }

    /**
     * @return true if {@code token} is well-formed, correctly signed and not
     *         yet expired. Never throws: any malformed input is simply invalid.
     */
    public boolean isValid(String token) {
        if (token == null) return false;
        int lastDot = token.lastIndexOf('.');
        if (lastDot < 0) return false;

        String payload = token.substring(0, lastDot);
        String signature = token.substring(lastDot + 1);

        // Signature first: an expiry is only worth reading once it is known to
        // be one this server wrote.
        if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        if (!payload.startsWith(VERSION + ".")) return false;

        try {
            long expiry = Long.parseLong(payload.substring(VERSION.length() + 1));
            return clock.instant().getEpochSecond() < expiry;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 is mandatory in every JRE; reaching here means the
            // platform is broken, not that the caller sent something odd.
            throw new IllegalStateException("cannot sign session token", e);
        }
    }

    public record Token(String value, Instant expiresAt) {
    }
}
