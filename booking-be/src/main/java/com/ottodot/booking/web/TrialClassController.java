package com.ottodot.booking.web;

import com.ottodot.booking.error.ApiException;
import com.ottodot.booking.repo.StudentRepository;
import com.ottodot.booking.repo.TrialClassRepository;
import com.ottodot.booking.service.RosterService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TrialClassController {

    private final TrialClassRepository classes;
    private final StudentRepository students;
    private final RosterService rosters;

    public TrialClassController(TrialClassRepository classes, StudentRepository students,
                                RosterService rosters) {
        this.classes = classes;
        this.students = students;
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

    @GetMapping("/parents/{parentId}/students")
    public List<Dtos.StudentView> studentsOf(@PathVariable long parentId) {
        return students.findByParent(parentId).stream()
                .map(s -> new Dtos.StudentView(s.id(), s.name(), s.grade())).toList();
    }
}
