package com.ottodot.booking.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for every integration test: a real Postgres 16 via Testcontainers.
 *
 * <p>An in-memory database would prove nothing here. The entire correctness
 * argument rests on Postgres semantics — a conditional UPDATE re-evaluating its
 * WHERE clause after a row lock is released, partial unique indexes, and
 * FOR UPDATE SKIP LOCKED. H2 does not reproduce any of that faithfully, so a
 * green suite against it would be actively misleading.
 *
 * <p>The container is started once in a static initialiser and shared by the
 * whole suite. Every subclass declares identical context configuration, so
 * Spring caches a single application context across all test classes; tests
 * isolate themselves by creating their own rows rather than by resetting the
 * database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AbstractIntegrationTest.IntegrationTestConfig.class)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("booking")
                    .withUsername("booking")
                    .withPassword("booking");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The concurrency tests fire up to 32 simultaneous bookings; each holds
        // a connection for its transaction. A pool smaller than the thread
        // count would serialise them and quietly weaken the test.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 40);
        // Tests drive the reaper explicitly. Left on its 30s production cadence
        // it would fire mid-test and make assertions about held seats racy.
        registry.add("booking.reaper-interval-ms", () -> 3_600_000);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    protected TestFixtures fixtures;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new TestFixtures(jdbc);
        clock.reset();
    }

    @TestConfiguration
    public static class IntegrationTestConfig {
        /**
         * Overrides the production system clock so hold expiry is drivable.
         * One bean, not two: it satisfies both the Clock injection points in
         * the services and the MutableClock the tests advance.
         */
        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
