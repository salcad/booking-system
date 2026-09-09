package com.ottodot.booking.auth;

import com.ottodot.booking.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Checks the shared password and hands back a session token. */
@Service
public class AuthService {

    private final byte[] password;
    private final SessionTokens tokens;
    private final LoginThrottle throttle;

    public AuthService(AuthProperties properties, SessionTokens tokens, LoginThrottle throttle) {
        this.password = properties.password().getBytes(StandardCharsets.UTF_8);
        this.tokens = tokens;
        this.throttle = throttle;
    }

    /**
     * @param client opaque identifier used for throttling - the caller's address
     * @throws ApiException 429 once the client has spent its attempts, 401 when
     *         the password is wrong
     */
    public SessionTokens.Token login(String candidate, String client) {
        if (throttle.isBlocked(client)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                    "Too many failed sign-in attempts. Try again later.");
        }

        // Constant-time: a length-sensitive or short-circuiting compare leaks
        // the password one character at a time to anyone who can time it.
        byte[] supplied = candidate == null
                ? new byte[0]
                : candidate.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(password, supplied)) {
            throttle.recordFailure(client);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                    "Incorrect password.");
        }

        throttle.recordSuccess(client);
        return tokens.issue();
    }
}
