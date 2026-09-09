package com.ottodot.booking.web;

import com.ottodot.booking.error.ApiException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 7807 responses. The "code" property is what the frontend branches on;
 * the human-readable detail is what it renders.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handle(ApiException e) {
        return problem(e.getStatus(), e.getCode(), e.getMessage());
    }

    /** A malformed body is the caller's fault, not ours: 400, not 500. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + defaultMessage(f))
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                detail.isEmpty() ? "Request validation failed." : detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request body could not be parsed.");
    }

    /**
     * An unparseable path variable or query parameter — /api/bookings/abc —
     * is a bad request. This one does not implement ErrorResponse either, so
     * without it the catch-all would call the caller's typo a server fault.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                e.getName() + " is not a valid value.");
    }

    /**
     * Spring's own signalling exceptions (unknown route, wrong method,
     * unsupported media type) already carry the right status, so they are
     * passed through rather than reported as 500s.
     *
     * <p>The branch lives inside the catch-all deliberately. Nearly all of
     * these merely implement {@link ErrorResponse} without extending
     * ErrorResponseException — NoResourceFoundException and
     * HttpRequestMethodNotSupportedException both extend ServletException — and
     * an @ExceptionHandler cannot name an interface, since it must name a
     * Throwable. Matching on the concrete class caught almost none of them.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        if (e instanceof ErrorResponse signalled) {
            ProblemDetail pd = signalled.getBody();
            if (pd.getProperties() == null || !pd.getProperties().containsKey("code")) {
                // Distinct from the domain codes on purpose: a 404 for an
                // unknown route is not the same thing as a booking that does
                // not exist, and the frontend must not conflate them.
                pd.setProperty("code", "HTTP_" + pd.getStatus());
            }
            return pd;
        }
        log.error("unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error");
    }

    private static String defaultMessage(FieldError f) {
        return f.getDefaultMessage() == null ? "is invalid" : f.getDefaultMessage();
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(code);
        pd.setProperty("code", code);
        return pd;
    }
}
