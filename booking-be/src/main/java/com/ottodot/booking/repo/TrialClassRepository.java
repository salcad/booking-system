package com.ottodot.booking.repo;

import com.ottodot.booking.domain.TrialClass;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TrialClassRepository {

    private static final RowMapper<TrialClass> MAPPER = (rs, n) -> new TrialClass(
            rs.getLong("id"),
            rs.getString("subject"),
            rs.getTimestamp("starts_at").toInstant(),
            rs.getInt("capacity"),
            rs.getInt("claimed_seats"));

    private final JdbcTemplate jdbc;

    public TrialClassRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TrialClass> findAll() {
        return jdbc.query(
                "SELECT id, subject, starts_at, capacity, claimed_seats "
                        + "FROM trial_classes ORDER BY starts_at",
                MAPPER);
    }

    public Optional<TrialClass> findById(long id) {
        return jdbc.query(
                "SELECT id, subject, starts_at, capacity, claimed_seats "
                        + "FROM trial_classes WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    /**
     * Claims one seat, atomically.
     *
     * <p>This single statement is the heart of the system. Under Postgres READ
     * COMMITTED, a concurrent transaction reaching this row blocks on the row
     * lock and then <em>re-evaluates the WHERE clause against the newly
     * committed value</em>. It therefore sees the incremented count and matches
     * nothing. Two callers can never both succeed on the last seat.
     *
     * <p>Deliberately NOT a read-then-write: SELECT ... then UPDATE would leave
     * a window between the check and the increment. There is no such window
     * here, and so no need for SELECT FOR UPDATE, SERIALIZABLE, or a retry loop.
     *
     * @return true if a seat was claimed, false if the class is full
     */
    public boolean tryClaimSeat(long classId) {
        int rows = jdbc.update(
                "UPDATE trial_classes SET claimed_seats = claimed_seats + 1 "
                        + "WHERE id = ? AND claimed_seats < capacity",
                classId);
        return rows == 1;
    }

    /** Returns a seat to the pool. Guarded so the count can never go negative. */
    public void releaseSeat(long classId) {
        jdbc.update(
                "UPDATE trial_classes SET claimed_seats = claimed_seats - 1 "
                        + "WHERE id = ? AND claimed_seats > 0",
                classId);
    }
}
