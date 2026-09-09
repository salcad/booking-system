package com.ottodot.booking.web;

import com.ottodot.booking.auth.AuthService;
import com.ottodot.booking.auth.SessionTokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /**
     * The only route the auth filter lets through unauthenticated.
     *
     * <p>The token is returned in the body rather than as a Set-Cookie: the
     * browser never calls this directly. The frontend's server action does,
     * and it is the one that owns the cookie - so it decides the flags
     * (httpOnly, sameSite) for its own origin rather than inheriting the
     * API's guess at them.
     *
     * @return 401 INVALID_CREDENTIALS on a wrong password,
     *         429 TOO_MANY_ATTEMPTS once this client has spent its attempts
     */
    @PostMapping("/login")
    public Dtos.LoginResponse login(@Valid @RequestBody Dtos.LoginRequest req,
                                    HttpServletRequest http) {
        SessionTokens.Token token = auth.login(req.password(), clientOf(http));
        return new Dtos.LoginResponse(token.value(), token.expiresAt());
    }

    /**
     * Answers only when the caller's token is valid, since the filter turns
     * everything else away first. That makes it the frontend's cheap "is this
     * session still good?" probe.
     */
    @GetMapping("/session")
    public Dtos.SessionView session() {
        return new Dtos.SessionView(true);
    }

    /**
     * Throttling keys off the nearest hop unless a proxy names the client.
     * X-Forwarded-For is trivially forged, so this is only sound behind a
     * proxy that overwrites it - which is also the only arrangement where
     * getRemoteAddr alone would lump every caller together.
     */
    private static String clientOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
