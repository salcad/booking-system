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
     * Returns an EXPIRED booking to PENDING_PAYMENT, re-acquiring its slot.
     *
     * <p>This is what makes a late payment safe. The partial unique index
     * covers PENDING_PAYMENT, so if the same child already has another live
     * booking for this class, <em>this statement</em> fails — before the
     * gateway is ever called. Re-acquiring the slot up front converts a
     * "charged, then discovered the conflict" failure into a plain rejection.
     *
     * <p>The caller must let a DuplicateKeyException propagate as a rollback:
     * once a constraint fires, the transaction is aborted and no further
     * statement can run, so any seat claimed beforehand is undone by the
     * rollback rather than by compensating code.
     *
     * @return true if the booking was still EXPIRED and has been reopened
     */
    public boolean reopenHold(long bookingId, Instant holdExpiresAt) {
        return jdbc.update(
                "UPDATE bookings SET status = 'PENDING_PAYMENT', hold_expires_at = ?, "
                        + "updated_at = now() WHERE id = ? AND status = 'EXPIRED'",
                Timestamp.from(holdExpiresAt), bookingId) == 1;
    }

    /**
     * Expires a hold, but only if it is still PENDING_PAYMENT.
     *
     * <p>Deliberately conditional, in the same spirit as tryClaimSeat. The
     * reaper's transaction already serialises it against the payment path, but
     * an unconditional "UPDATE ... WHERE id = ?" would silently overwrite a
     * booking that payment confirmed between the sweep's read and its write —
     * taking a paid student off the roster and releasing their seat. Making the
     * transition itself conditional means only the caller that genuinely
     * performed it releases the seat, whatever the surrounding isolation.
     *
     * @return true if this call performed the transition
     */
    public boolean expireIfStillPending(long bookingId) {
        return jdbc.update(
                "UPDATE bookings SET status = 'EXPIRED', hold_expires_at = NULL, "
                        + "updated_at = now() WHERE id = ? AND status = 'PENDING_PAYMENT'",
                bookingId) == 1;
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

    /** One roster line: a booking joined to the child it is for. */
    public record RosterRow(long bookingId, long studentId, String studentName,
                            String grade, BookingStatus status) {
    }

    private static final RowMapper<RosterRow> ROSTER_MAPPER = (rs, n) -> new RosterRow(
            rs.getLong("booking_id"),
            rs.getLong("student_id"),
            rs.getString("name"),
            rs.getString("grade"),
            BookingStatus.valueOf(rs.getString("status")));

    /**
     * Every live booking for a class, with its student, in one statement.
     *
     * <p>Deliberately one query covering both statuses rather than one per
     * status. Two queries see two snapshots under READ COMMITTED, so a payment
     * committing between them makes the child appear in neither result: gone
     * from pending because it is now CONFIRMED, absent from confirmed because
     * that query already ran. A single statement has a single snapshot, so a
     * child is always in exactly one of the two lists.
     *
     * <p>The join also removes the per-booking student lookup the roster used
     * to do, which was a query per child on a page that lists every child.
     */
    public List<RosterRow> findRosterRows(long trialClassId) {
        return jdbc.query(
                "SELECT b.id AS booking_id, b.status, "
                        + "s.id AS student_id, s.name, s.grade "
                        + "FROM bookings b JOIN students s ON s.id = b.student_id "
                        + "WHERE b.trial_class_id = ? "
                        + "AND b.status IN ('PENDING_PAYMENT', 'CONFIRMED') "
                        + "ORDER BY b.created_at",
                ROSTER_MAPPER, trialClassId);
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
