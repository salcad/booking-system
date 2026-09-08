package com.ottodot.booking.repo;

import com.ottodot.booking.domain.PaymentAttempt;
import com.ottodot.booking.domain.PaymentStatus;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentAttemptRepository {

    private static final String COLS =
            "id, booking_id, idempotency_key, amount_cents, status, provider_ref, created_at";

    private static final RowMapper<PaymentAttempt> MAPPER = (rs, n) -> new PaymentAttempt(
            rs.getLong("id"),
            rs.getLong("booking_id"),
            rs.getString("idempotency_key"),
            rs.getInt("amount_cents"),
            PaymentStatus.valueOf(rs.getString("status")),
            rs.getString("provider_ref"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public PaymentAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotency lookup: has this exact request already been processed? */
    public Optional<PaymentAttempt> findByKey(long bookingId, String idempotencyKey) {
        return jdbc.query(
                "SELECT " + COLS + " FROM payment_attempts "
                        + "WHERE booking_id = ? AND idempotency_key = ?",
                MAPPER, bookingId, idempotencyKey).stream().findFirst();
    }

    public long insert(long bookingId, String idempotencyKey, int amountCents,
                       PaymentStatus status, String providerRef) {
        return jdbc.queryForObject(
                "INSERT INTO payment_attempts "
                        + "(booking_id, idempotency_key, amount_cents, status, provider_ref) "
                        + "VALUES (?, ?, ?, ?::payment_status, ?) RETURNING id",
                Long.class, bookingId, idempotencyKey, amountCents, status.name(), providerRef);
    }

    public void markRefunded(long paymentAttemptId) {
        jdbc.update(
                "UPDATE payment_attempts SET status = 'REFUNDED' WHERE id = ?",
                paymentAttemptId);
    }

    public Optional<PaymentAttempt> findLatest(long bookingId) {
        return jdbc.query(
                "SELECT " + COLS + " FROM payment_attempts WHERE booking_id = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT 1",
                MAPPER, bookingId).stream().findFirst();
    }
}
