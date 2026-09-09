package com.ottodot.booking.repo;

import com.ottodot.booking.domain.BookingStatus;
import com.ottodot.booking.domain.Student;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class StudentRepository {

    private static final String COLS = "id, parent_id, name, grade, always_fails_payment";

    private static final RowMapper<Student> MAPPER = (rs, n) -> new Student(
            rs.getLong("id"),
            rs.getLong("parent_id"),
            rs.getString("name"),
            rs.getString("grade"),
            rs.getBoolean("always_fails_payment"));

    private final JdbcTemplate jdbc;

    public StudentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Student> findById(long id) {
        return jdbc.query("SELECT " + COLS + " FROM students WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public List<Student> findByParent(long parentId) {
        return jdbc.query("SELECT " + COLS + " FROM students WHERE parent_id = ? ORDER BY name",
                MAPPER, parentId);
    }

    /**
     * One child, plus the live booking they already hold for a given class.
     *
     * <p>{@code liveBookingId} and {@code liveBookingStatus} are both null when
     * the child has no live booking for that class.
     */
    public record StudentBooking(long id, String name, String grade,
                                 Long liveBookingId, BookingStatus liveBookingStatus) {
    }

    private static final RowMapper<StudentBooking> WITH_BOOKING_MAPPER = (rs, n) -> {
        long bookingId = rs.getLong("booking_id");
        boolean booked = !rs.wasNull();
        return new StudentBooking(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("grade"),
                booked ? bookingId : null,
                booked ? BookingStatus.valueOf(rs.getString("status")) : null);
    };

    /**
     * A parent's children, each carrying the live booking they already hold for
     * one class.
     *
     * <p>This exists so the booking form can refuse a duplicate without asking
     * the API first. Without it the UI has no per-child state to render from,
     * so the only way to discover I2 is to violate it and read the 409 back —
     * which means every already-booked child costs a pointless round-trip and
     * shows the parent an error for something that was knowable on page load.
     *
     * <p>"Live" here must stay exactly the set that {@code
     * BookingRepository.findLive} and the {@code uq_live_booking} index use.
     * If this predicate drifts from theirs the UI starts disagreeing with the
     * API — offering a button that is then refused, or greying out a child who
     * could in fact book. That index is also why the LEFT JOIN is safe: it
     * guarantees at most one live booking per (student, class), so no child can
     * fan out into two rows.
     */
    public List<StudentBooking> findByParentWithBooking(long parentId, long trialClassId) {
        return jdbc.query(
                "SELECT s.id, s.name, s.grade, b.id AS booking_id, b.status "
                        + "FROM students s "
                        + "LEFT JOIN bookings b ON b.student_id = s.id "
                        + "AND b.trial_class_id = ? "
                        + "AND b.status IN ('PENDING_PAYMENT', 'CONFIRMED') "
                        + "WHERE s.parent_id = ? "
                        + "ORDER BY s.name",
                WITH_BOOKING_MAPPER, trialClassId, parentId);
    }

    /** Demo support only: creates a throwaway student for the race endpoint. */
    public long insertDemoStudent(long parentId, String name) {
        return jdbc.queryForObject(
                "INSERT INTO students (parent_id, name, grade, always_fails_payment) "
                        + "VALUES (?, ?, 'demo', FALSE) RETURNING id",
                Long.class, parentId, name);
    }
}
