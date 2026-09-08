package com.ottodot.booking.repo;

import com.ottodot.booking.domain.BookingEvent;
import com.ottodot.booking.domain.BookingStatus;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Append-only transition log. Every write happens inside the same transaction
 * as the transition it records, so the log cannot drift from booking state.
 */
@Repository
public class BookingEventRepository {

    private static final RowMapper<BookingEvent> MAPPER = (rs, n) -> new BookingEvent(
            rs.getLong("id"),
            rs.getLong("booking_id"),
            rs.getString("from_status") == null
                    ? null : BookingStatus.valueOf(rs.getString("from_status")),
            BookingStatus.valueOf(rs.getString("to_status")),
            rs.getString("reason"),
            rs.getString("actor"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public BookingEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(long bookingId, BookingStatus from, BookingStatus to,
                       String reason, String actor) {
        jdbc.update(
                "INSERT INTO booking_events "
                        + "(booking_id, from_status, to_status, reason, actor) "
                        + "VALUES (?, ?::booking_status, ?::booking_status, ?, ?)",
                bookingId, from == null ? null : from.name(), to.name(), reason, actor);
    }

    public List<BookingEvent> findByBooking(long bookingId) {
        return jdbc.query(
                "SELECT id, booking_id, from_status, to_status, reason, actor, created_at "
                        + "FROM booking_events WHERE booking_id = ? ORDER BY created_at, id",
                MAPPER, bookingId);
    }
}
