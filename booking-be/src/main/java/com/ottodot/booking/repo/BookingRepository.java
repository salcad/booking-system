package com.ottodot.booking.repo;

import com.ottodot.booking.domain.Booking;
import com.ottodot.booking.domain.BookingStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class BookingRepository {

    private static final String COLS =
            "id, student_id, trial_class_id, status, hold_expires_at, created_at, updated_at";

    private static final RowMapper<Booking> MAPPER = (rs, n) -> new Booking(
            rs.getLong("id"),
            rs.getLong("student_id"),
            rs.getLong("trial_class_id"),
            BookingStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("hold_expires_at") == null
                    ? null : rs.getTimestamp("hold_expires_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public BookingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Booking> findById(long id) {
        return jdbc.query("SELECT " + COLS + " FROM bookings WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * Loads a booking and holds its row lock for the rest of the transaction.
     *
     * <p>This is what serialises the payment path against the hold reaper:
     * both take this lock, so exactly one of them acts on a given booking.
     */
    public Optional<Booking> lockById(long id) {
        return jdbc.query("SELECT " + COLS + " FROM bookings WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /** Existing PENDING_PAYMENT or CONFIRMED booking for this child in this class. */
    public Optional<Booking> findLive(long studentId, long trialClassId) {
        return jdbc.query(
                "SELECT " + COLS + " FROM bookings "
                        + "WHERE student_id = ? AND trial_class_id = ? "
                        + "AND status IN ('PENDING_PAYMENT', 'CONFIRMED')",
                MAPPER, studentId, trialClassId).stream().findFirst();
    }

    public long insertPending(long studentId, long trialClassId, Instant holdExpiresAt) {
        return jdbc.queryForObject(
                "INSERT INTO bookings (student_id, trial_class_id, status, hold_expires_at) "
                        + "VALUES (?, ?, 'PENDING_PAYMENT', ?) RETURNING id",
                Long.class, studentId, trialClassId, Timestamp.from(holdExpiresAt));
    }

    public void updateStatus(long bookingId, BookingStatus status, Instant holdExpiresAt) {
        jdbc.update(
                "UPDATE bookings SET status = ?::booking_status, hold_expires_at = ?, "
                        + "updated_at = now() WHERE id = ?",
                status.name(),
                holdExpiresAt == null ? null : Timestamp.from(holdExpiresAt),
                bookingId);
    }

    /**
     * Expired holds, locked for this transaction.
     *
     * <p>SKIP LOCKED is deliberate: a booking whose row is currently locked is
     * mid-payment, and must not be expired out from under the paying parent.
     * The reaper simply leaves it for the payment path to resolve.
     */
    public List<Booking> lockExpiredHolds(Instant now, int limit) {
        return jdbc.query(
                "SELECT " + COLS + " FROM bookings "
                        + "WHERE status = 'PENDING_PAYMENT' AND hold_expires_at < ? "
                        + "ORDER BY hold_expires_at LIMIT ? FOR UPDATE SKIP LOCKED",
                MAPPER, Timestamp.from(now), limit);
    }

    public List<Booking> findByClassAndStatus(long trialClassId, BookingStatus status) {
        return jdbc.query(
                "SELECT " + COLS + " FROM bookings "
                        + "WHERE trial_class_id = ? AND status = ?::booking_status "
                        + "ORDER BY created_at",
                MAPPER, trialClassId, status.name());
    }

    /** Live booking count, used to assert invariant I4 in tests. */
    public int countLive(long trialClassId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM bookings WHERE trial_class_id = ? "
                        + "AND status IN ('PENDING_PAYMENT', 'CONFIRMED')",
                Integer.class, trialClassId);
        return n == null ? 0 : n;
    }
}
