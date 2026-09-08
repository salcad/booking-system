package com.ottodot.booking.error;

import org.springframework.http.HttpStatus;

/** Carries a machine-readable code so the frontend can branch on the reason. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException notFound(String what, long id) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " " + id + " not found");
    }

    public static ApiException duplicateBooking() {
        return new ApiException(HttpStatus.CONFLICT, "DUPLICATE_BOOKING",
                "This child already has a live booking for this class.");
    }

    public static ApiException classFull() {
        return new ApiException(HttpStatus.CONFLICT, "CLASS_FULL",
                "This class is full.");
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
