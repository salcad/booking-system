package com.ottodot.booking.service;

import com.ottodot.booking.domain.BookingStatus;
import com.ottodot.booking.domain.TrialClass;
import com.ottodot.booking.error.ApiException;
import com.ottodot.booking.repo.BookingRepository;
import com.ottodot.booking.repo.BookingRepository.RosterRow;
import com.ottodot.booking.repo.TrialClassRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RosterService {

    public record RosterEntry(long bookingId, long studentId, String studentName, String grade) {
    }

    public record Roster(TrialClass trialClass, List<RosterEntry> confirmed,
                         List<RosterEntry> pendingHolds) {
    }

    private final TrialClassRepository classes;
    private final BookingRepository bookings;

    public RosterService(TrialClassRepository classes, BookingRepository bookings) {
        this.classes = classes;
        this.bookings = bookings;
    }

    /**
     * The roster is CONFIRMED only. Held-but-unpaid bookings are returned
     * separately and must never be merged into it, which is the whole point of
     * invariant I3.
     *
     * <p>REPEATABLE READ, not the default READ COMMITTED. A read-only
     * transaction is not automatically a consistent one: under READ COMMITTED
     * each statement takes a fresh snapshot, so the class row and the booking
     * rows could be read either side of a payment and disagree with each other.
     * The response would then show a seat count that no list accounts for.
     * REPEATABLE READ pins one snapshot for the whole method, so the counts and
     * the lists always describe the same moment.
     *
     * <p>Read-only REPEATABLE READ cannot fail with a serialisation error in
     * Postgres, so this needs no retry: those arise from write conflicts, and
     * this transaction writes nothing.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Roster forClass(long classId) {
        TrialClass klass = classes.findById(classId)
                .orElseThrow(() -> ApiException.notFound("trial class", classId));

        List<RosterEntry> confirmed = new ArrayList<>();
        List<RosterEntry> pendingHolds = new ArrayList<>();
        for (RosterRow row : bookings.findRosterRows(classId)) {
            RosterEntry entry = new RosterEntry(
                    row.bookingId(), row.studentId(), row.studentName(), row.grade());
            if (row.status() == BookingStatus.CONFIRMED) {
                confirmed.add(entry);
            } else {
                pendingHolds.add(entry);
            }
        }
        return new Roster(klass, List.copyOf(confirmed), List.copyOf(pendingHolds));
    }
}
