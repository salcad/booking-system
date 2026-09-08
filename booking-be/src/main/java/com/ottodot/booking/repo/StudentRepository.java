package com.ottodot.booking.repo;

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

    /** Demo support only: creates a throwaway student for the race endpoint. */
    public long insertDemoStudent(long parentId, String name) {
        return jdbc.queryForObject(
                "INSERT INTO students (parent_id, name, grade, always_fails_payment) "
                        + "VALUES (?, ?, 'demo', FALSE) RETURNING id",
                Long.class, parentId, name);
    }
}
