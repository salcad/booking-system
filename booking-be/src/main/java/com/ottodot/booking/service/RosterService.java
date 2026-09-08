package com.ottodot.booking.service;

import com.ottodot.booking.domain.Booking;
import com.ottodot.booking.domain.BookingStatus;
import com.ottodot.booking.domain.TrialClass;
import com.ottodot.booking.error.ApiException;
import com.ottodot.booking.repo.BookingRepository;
import com.ottodot.booking.repo.StudentRepository;
import com.ottodot.booking.repo.TrialClassRepository;
import java.util.List;
import org.springframework.stereotype.Service;
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
    private final StudentRepository students;

    public RosterService(TrialClassRepository classes, BookingRepository bookings,
                         StudentRepository students) {
        this.classes = classes;
        this.bookings = bookings;
        this.students = students;
    }

    /**
     * The roster is CONFIRMED only. Held-but-unpaid bookings are returned
     * separately and must never be merged into it — that separation is the
     * whole point of invariant I3.
     */
    @Transactional(readOnly = true)
    public Roster forClass(long classId) {
        TrialClass klass = classes.findById(classId)
                .orElseThrow(() -> ApiException.notFound("trial class", classId));
        return new Roster(klass,
                toEntries(bookings.findByClassAndStatus(classId, BookingStatus.CONFIRMED)),
                toEntries(bookings.findByClassAndStatus(classId, BookingStatus.PENDING_PAYMENT)));
    }

    private List<RosterEntry> toEntries(List<Booking> source) {
        return source.stream().map(b -> {
            var s = students.findById(b.studentId()).orElseThrow();
            return new RosterEntry(b.id(), s.id(), s.name(), s.grade());
        }).toList();
    }
}
