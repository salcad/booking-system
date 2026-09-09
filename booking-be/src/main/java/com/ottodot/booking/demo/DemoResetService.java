package com.ottodot.booking.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Puts the database back to the state a fresh migration leaves it in.
 *
 * <p>The seed migration is replayed rather than duplicated here, so there is
 * exactly one description of the demo fixtures. Class start times in it are
 * relative to now(), so a rebuild also refreshes them.
 */
@Service
public class DemoResetService {

    private static final Logger log = LoggerFactory.getLogger(DemoResetService.class);

    /** Every table the seed touches, plus the rows the race demo leaves behind. */
    private static final String TRUNCATE = """
            TRUNCATE booking_events, payment_attempts, bookings,
                     trial_classes, students, parents
            RESTART IDENTITY CASCADE""";

    private static final Resource SEED = new ClassPathResource("db/migration/V2__seed.sql");

    private final JdbcTemplate jdbc;

    public DemoResetService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One transaction: a failure part-way through leaves the old data intact
     * rather than an empty database.
     */
    @Transactional
    public ResetReport reset() {
        jdbc.execute(TRUNCATE);
        jdbc.execute((ConnectionCallback<Void>) conn -> {
            ScriptUtils.executeSqlScript(conn, SEED);
            return null;
        });

        var report = new ResetReport(
                jdbc.queryForObject("SELECT count(*) FROM parents", Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM students", Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM trial_classes", Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class));
        log.info("demo data rebuilt: {}", report);
        return report;
    }

    public record ResetReport(int parents, int students, int trialClasses, int bookings) {
    }
}
