package com.ottodot.booking.web;

import com.ottodot.booking.domain.Parent;
import com.ottodot.booking.error.ApiException;
import com.ottodot.booking.repo.ParentRepository;
import com.ottodot.booking.repo.StudentRepository;
import com.ottodot.booking.repo.TrialClassRepository;
import com.ottodot.booking.service.RosterService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TrialClassController {

    private final TrialClassRepository classes;
    private final StudentRepository students;
    private final ParentRepository parents;
    private final RosterService rosters;

    public TrialClassController(TrialClassRepository classes, StudentRepository students,
                                ParentRepository parents, RosterService rosters) {
        this.classes = classes;
        this.students = students;
        this.parents = parents;
        this.rosters = rosters;
    }

    @GetMapping("/trial-classes")
    public List<Dtos.TrialClassView> list() {
        return classes.findAll().stream().map(Dtos.TrialClassView::of).toList();
    }

    @GetMapping("/trial-classes/{id}")
    public Dtos.TrialClassView one(@PathVariable long id) {
        return classes.findById(id).map(Dtos.TrialClassView::of)
                .orElseThrow(() -> ApiException.notFound("trial class", id));
    }

    /** Confirmed students only. Holds are reported separately, never merged in. */
    @GetMapping("/trial-classes/{id}/roster")
    public RosterService.Roster roster(@PathVariable long id) {
        return rosters.forClass(id);
    }

    /** Stands in for authentication: the UI picks a parent instead of logging in. */
    @GetMapping("/parents")
    public List<Parent> parents() {
        return parents.findAll();
    }

    /**
     * A parent's children. Pass {@code ?trialClassId=} and each child also
     * reports the live booking they already hold for that class.
     *
     * <p>The parameter is optional so the existing callers are unaffected, but
     * the booking form always sends it: knowing up front which children are
     * already booked is what lets it disable them instead of letting the parent
     * click through to a 409 that was predictable before they clicked.
     *
     * <p>This is a hint, not a decision. It is a separate read from the insert
     * that enforces I2, so a booking made between this call and the submit is
     * not reflected here. The partial unique index remains the only thing that
     * actually rejects a duplicate; this endpoint only spares the parent the
     * common case of finding that out the hard way.
     */
    @GetMapping("/parents/{parentId}/students")
    public List<Dtos.StudentView> studentsOf(@PathVariable long parentId,
                                             @RequestParam(required = false) Long trialClassId) {
        if (trialClassId == null) {
            return students.findByParent(parentId).stream()
                    .map(s -> new Dtos.StudentView(s.id(), s.name(), s.grade(), null)).toList();
        }
        return students.findByParentWithBooking(parentId, trialClassId).stream()
                .map(Dtos.StudentView::of).toList();
    }
}
