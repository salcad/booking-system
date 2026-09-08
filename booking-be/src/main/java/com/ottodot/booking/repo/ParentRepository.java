package com.ottodot.booking.repo;

import com.ottodot.booking.domain.Parent;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ParentRepository {

    private static final RowMapper<Parent> MAPPER = (rs, n) ->
            new Parent(rs.getLong("id"), rs.getString("name"), rs.getString("email"));

    private static final String DEMO_EMAIL = "race-demo@example.com";

    private final JdbcTemplate jdbc;

    public ParentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Parent> findAll() {
        return jdbc.query("SELECT id, name, email FROM parents ORDER BY id", MAPPER);
    }

    /**
     * The owner of the throwaway students created by the race demo, kept apart
     * from the seeded families so the demo never pollutes their child lists.
     */
    public long ensureDemoParent() {
        return jdbc.queryForObject(
                "INSERT INTO parents (name, email) VALUES ('Race Demo', ?) "
                        + "ON CONFLICT (email) DO UPDATE SET name = EXCLUDED.name RETURNING id",
                Long.class, DEMO_EMAIL);
    }
}
