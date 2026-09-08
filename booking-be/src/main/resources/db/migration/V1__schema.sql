-- Ottodot trial booking — schema.
--
-- The correctness of this system rests on three database-level guarantees,
-- not on application logic:
--   I1  claimed_seats can never exceed capacity   -> CHECK constraint
--   I2  one live booking per (student, class)     -> partial unique index
--   I4  claimed_seats == count of live bookings   -> maintained transactionally
--
-- Note on enums: bind parameters against these columns must be cast
-- explicitly in SQL (?::booking_status). Literals inside SQL do not need it.

CREATE TYPE booking_status AS ENUM (
    'PENDING_PAYMENT',
    'CONFIRMED',
    'PAYMENT_FAILED',
    'CANCELLED',
    'EXPIRED'
);

CREATE TYPE payment_status AS ENUM (
    'SUCCEEDED',
    'FAILED',
    'REFUNDED'
);

CREATE TABLE parents (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT NOT NULL,
    email      TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE students (
    id        BIGSERIAL PRIMARY KEY,
    parent_id BIGINT NOT NULL REFERENCES parents (id),
    name      TEXT NOT NULL,
    grade     TEXT NOT NULL,
    -- Demo affordance: the mock payment gateway always declines for this
    -- student, so the payment-failure path is reachable from the UI without
    -- editing code. Would not exist in a real system.
    always_fails_payment BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_students_parent ON students (parent_id);

CREATE TABLE trial_classes (
    id            BIGSERIAL PRIMARY KEY,
    subject       TEXT NOT NULL,
    starts_at     TIMESTAMPTZ NOT NULL,
    capacity      INT NOT NULL DEFAULT 4,
    -- Counts BOTH held (PENDING_PAYMENT) and CONFIRMED bookings. A held seat
    -- is a real seat: that is what makes the last-seat race resolve before
    -- anyone is charged.
    claimed_seats INT NOT NULL DEFAULT 0,

    -- I1: defence in depth. Even if the service layer is later refactored
    -- incorrectly, the database refuses to overbook.
    CONSTRAINT seats_within_capacity
        CHECK (claimed_seats >= 0 AND claimed_seats <= capacity)
);

CREATE TABLE bookings (
    id              BIGSERIAL PRIMARY KEY,
    student_id      BIGINT NOT NULL REFERENCES students (id),
    trial_class_id  BIGINT NOT NULL REFERENCES trial_classes (id),
    status          booking_status NOT NULL,
    -- Set while PENDING_PAYMENT, NULL in every terminal state.
    hold_expires_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- I2: at most one live booking per child per class.
-- PAYMENT_FAILED / CANCELLED / EXPIRED are deliberately excluded so that a
-- parent whose payment failed can simply try again.
CREATE UNIQUE INDEX uq_live_booking
    ON bookings (student_id, trial_class_id)
    WHERE status IN ('PENDING_PAYMENT', 'CONFIRMED');

-- Drives the reaper's sweep.
CREATE INDEX idx_bookings_expiry
    ON bookings (hold_expires_at)
    WHERE status = 'PENDING_PAYMENT';

CREATE INDEX idx_bookings_class ON bookings (trial_class_id);

CREATE TABLE payment_attempts (
    id              BIGSERIAL PRIMARY KEY,
    booking_id      BIGINT NOT NULL REFERENCES bookings (id),
    -- Client-supplied. Replaying a request with the same key returns the
    -- original outcome instead of charging twice.
    idempotency_key TEXT NOT NULL,
    amount_cents    INT NOT NULL,
    status          payment_status NOT NULL,
    provider_ref    TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_payment_idempotency UNIQUE (booking_id, idempotency_key)
);

-- Append-only audit trail. One row per state transition, written inside the
-- same transaction as the transition, so the log cannot drift from reality.
CREATE TABLE booking_events (
    id          BIGSERIAL PRIMARY KEY,
    booking_id  BIGINT NOT NULL REFERENCES bookings (id),
    from_status booking_status,
    to_status   booking_status NOT NULL,
    reason      TEXT,
    actor       TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_events_booking ON booking_events (booking_id, created_at);
