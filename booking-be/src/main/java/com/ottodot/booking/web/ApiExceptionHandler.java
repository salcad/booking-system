package com.ottodot.booking.web;

import com.ottodot.booking.error.ApiException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
     * Spring's own signalling exceptions (unknown route, wrong method,
     * unsupported media type) already carry the right status. Without this they
     * would fall into the catch-all below and be reported as 500s.
     */
    @ExceptionHandler(ErrorResponseException.class)
    public ProblemDetail handleErrorResponse(ErrorResponseException e) {
        ProblemDetail pd = e.getBody();
        if (pd.getProperties() == null || !pd.getProperties().containsKey("code")) {
            pd.setProperty("code", "HTTP_" + e.getStatusCode().value());
        }
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
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
