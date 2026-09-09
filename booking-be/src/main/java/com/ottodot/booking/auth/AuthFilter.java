package com.ottodot.booking.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects any API request that does not carry a valid session token.
 *
 * <p>This is the gate. The frontend has its own login dialog, but that is only
 * there so the user sees a password box instead of a wall of errors - a client
 * cannot be trusted to gate itself, so the check that actually counts happens
 * here, on every request, before any handler runs.
 */
public class AuthFilter extends OncePerRequestFilter {

    /** Name of the cookie the frontend stores the token in. */
    public static final String COOKIE = "booking_session";
    private static final String BEARER = "Bearer ";

    private final SessionTokens tokens;
    private final ObjectMapper json;

    public AuthFilter(SessionTokens tokens, ObjectMapper json) {
        this.tokens = tokens;
        this.json = json;
    }

    /**
     * Logging in is the one thing an unauthenticated caller may do. The check
     * is on the exact path and method so that neither a sub-path nor a
     * different verb on the same route slips through.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && "/api/auth/login".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (tokens.isValid(presentedToken(request))) {
            chain.doFilter(request, response);
            return;
        }
        reject(response);
    }

    /**
     * The cookie is what the browser sends; the bearer header is for callers
     * that have no cookie jar - the frontend's server-side rendering, and the
     * tests.
     */
    private static String presentedToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE.equals(cookie.getName())) return cookie.getValue();
            }
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.startsWith(BEARER)
                ? header.substring(BEARER.length())
                : null;
    }

    /**
     * Written by hand rather than raised as an exception: a filter runs outside
     * the DispatcherServlet, so @RestControllerAdvice never sees what happens
     * here. The body still matches ApiExceptionHandler's shape, because the
     * frontend branches on "code" no matter which layer answered.
     */
    private void reject(HttpServletResponse response) throws IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Sign in to use this API.");
        pd.setTitle("UNAUTHENTICATED");
        pd.setProperty("code", "UNAUTHENTICATED");

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), pd);
    }
}
