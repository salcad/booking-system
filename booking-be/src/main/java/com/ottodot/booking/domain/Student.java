package com.ottodot.booking.domain;

public record Student(
        long id,
        long parentId,
        String name,
        String grade,
        boolean alwaysFailsPayment) {
}
