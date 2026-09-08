# Backend Implementation Plan — `booking-be`

Java 21 · Spring Boot 3.3 · Maven · JdbcTemplate · Flyway · Postgres 16 · Testcontainers

The backend owns every invariant. The UI is advisory only; nothing the frontend
does can put the system into an incorrect state.

---

## 1. Invariants

These four statements must be true at every instant, including mid-race:

| # | Invariant | Enforced by |
|---|---|---|
| I1 | At most `capacity` (4) seats claimed per class | conditional `UPDATE` + `CHECK` constraint |
| I2 | At most one live booking per (student, class) | partial unique index |
| I3 | A booking is on the roster only if `status = 'CONFIRMED'` | roster query filters on status |
| I4 | `claimed_seats` equals the count of live bookings | every transition adjusts both in one transaction |

I4 is the one that catches bugs the others miss, and it is directly assertable
in tests. A seat is "live" while a booking is `PENDING_PAYMENT` (held) or
`CONFIRMED` (paid).

---

## 2. Data model

Flyway migrations under `src/main/resources/db/migration/`.

### `V1__schema.sql`

```sql
CREATE TYPE booking_status AS ENUM (
  'PENDING_PAYMENT', 'CONFIRMED', 'PAYMENT_FAILED', 'CANCELLED', 'EXPIRED'
);

CREATE TYPE payment_status AS ENUM ('SUCCEEDED', 'FAILED', 'REFUNDED');

CREATE TABLE parents (
  id         BIGSERIAL PRIMARY KEY,
  name       TEXT NOT NULL,
  email      TEXT NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE students (
  id         BIGSERIAL PRIMARY KEY,
  parent_id  BIGINT NOT NULL REFERENCES parents(id),
  name       TEXT NOT NULL,
  grade      TEXT NOT NULL
);

CREATE TABLE trial_classes (
  id            BIGSERIAL PRIMARY KEY,
  subject       TEXT NOT NULL,
  starts_at     TIMESTAMPTZ NOT NULL,
  capacity      INT NOT NULL DEFAULT 4,
  claimed_seats INT NOT NULL DEFAULT 0,
  CONSTRAINT seats_within_capacity
    CHECK (claimed_seats >= 0 AND claimed_seats <= capacity)
);

CREATE TABLE bookings (
  id              BIGSERIAL PRIMARY KEY,
  student_id      BIGINT NOT NULL REFERENCES students(id),
  trial_class_id  BIGINT NOT NULL REFERENCES trial_classes(id),
  status          booking_status NOT NULL,
  hold_expires_at TIMESTAMPTZ,          -- set while PENDING_PAYMENT, else NULL
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- I2: one live booking per child per class.
-- PAYMENT_FAILED / CANCELLED / EXPIRED are excluded so a parent can retry.
CREATE UNIQUE INDEX uq_live_booking
  ON bookings (student_id, trial_class_id)
  WHERE status IN ('PENDING_PAYMENT', 'CONFIRMED');

CREATE INDEX idx_bookings_expiry
  ON bookings (hold_expires_at)
  WHERE status = 'PENDING_PAYMENT';

CREATE TABLE payment_attempts (
  id              BIGSERIAL PRIMARY KEY,
  booking_id      BIGINT NOT NULL REFERENCES bookings(id),
  idempotency_key TEXT NOT NULL,
  amount_cents    INT NOT NULL,
  status          payment_status NOT NULL,
  provider_ref    TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (booking_id, idempotency_key)
);

-- Append-only audit trail. One row per state transition, written in the
-- same transaction as the transition itself.
CREATE TABLE booking_events (
  id          BIGSERIAL PRIMARY KEY,
  booking_id  BIGINT NOT NULL REFERENCES bookings(id),
  from_status booking_status,
  to_status   booking_status NOT NULL,
  reason      TEXT,
  actor       TEXT NOT NULL,           -- 'parent' | 'system' | 'admin'
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### `V2__seed.sql`

Covers the four cases the brief asks for. See §8.

---

## 3. Booking state machine

```
                    ┌──────────────────┐
   POST /bookings   │ PENDING_PAYMENT  │  seat claimed, hold_expires_at = now()+10m
   (claims seat) ──►│                  │
                    └────────┬─────────┘
                             │
        ┌────────────────────┼────────────────────┬──────────────────┐
        │ pay OK             │ pay declined       │ reaper           │ parent
        ▼                    ▼                    ▼                  ▼
   ┌───────────┐      ┌────────────────┐   ┌───────────┐      ┌───────────┐
   │ CONFIRMED │      │ PAYMENT_FAILED │   │  EXPIRED  │      │ CANCELLED │
   └───────────┘      └────────────────┘   └───────────┘      └───────────┘
     seat kept          seat released       seat released      seat released
```

Plus one edge transition: payment succeeds but the reaper already expired the
hold and the seat was taken meanwhile → refund → `CANCELLED (SEAT_UNAVAILABLE)`.

`PAYMENT_FAILED` is terminal for that booking row, but because it is excluded
from `uq_live_booking` the parent can create a fresh booking and retry.

---

## 4. Concurrency design

### 4.1 Claiming a seat

One statement. No read-then-write, no `SELECT ... FOR UPDATE`, no retry loop:

```sql
UPDATE trial_classes
   SET claimed_seats = claimed_seats + 1
 WHERE id = :classId
   AND claimed_seats < capacity;
```

Rows affected `= 1` → seat is yours. `= 0` → class is full.

**Why this is correct.** Under Postgres READ COMMITTED, a second transaction
that reaches this row while the first holds the lock blocks, and on release
**re-reads the committed row and re-evaluates the `WHERE` clause**. It therefore
sees `claimed_seats = 4` and matches nothing. Two concurrent claimers cannot
both succeed. No application-level locking is needed.

Releasing is the mirror statement, `claimed_seats - 1 WHERE claimed_seats > 0`.

### 4.2 The last-seat race

Because the seat is claimed at booking time rather than at payment time, the
race described in the brief is resolved *before* anyone is charged:

1. Class has 3/4 claimed. User A books → claim succeeds, 4/4, A holds a seat.
2. User B books the same class → `UPDATE` matches 0 rows → **409 Conflict,
   B never reaches payment and is never charged.**
3. A pays → booking row still `PENDING_PAYMENT`, so the seat is provably still
   held → `CONFIRMED`.

If instead A abandons checkout, the reaper releases the hold after 10 minutes,
the seat returns to the pool, and B can book it.

### 4.3 The residual race — hold expiry during payment

The only remaining window: A's hold expires while A's payment is in flight, and
someone else takes the seat. Handled at confirmation time by locking the booking
row, which serialises confirmation against the reaper:

```
BEGIN;
  SELECT * FROM bookings WHERE id = :id FOR UPDATE;

  status = PENDING_PAYMENT
      -- the reaper has not run; the seat is guaranteed still claimed
      -> UPDATE bookings SET status='CONFIRMED', hold_expires_at=NULL
      -> insert booking_event
      -- claimed_seats deliberately unchanged: held -> confirmed, still 1 seat

  status = EXPIRED
      -- the reaper won the row; the seat went back to the pool
      -> attempt a fresh claim (§4.1)
         1 row -> CONFIRMED
         0 rows -> refund the payment
                -> CANCELLED, reason = SEAT_UNAVAILABLE
COMMIT;
```

Both the reaper and the confirm path take `FOR UPDATE` on the same booking row,
so exactly one of them acts on it. This is the only place a refund can occur.

### 4.4 Defence in depth

`CHECK (claimed_seats <= capacity)` means that even if the service layer is
later refactored incorrectly, the database refuses to overbook — the transaction
aborts rather than producing a fifth confirmed student. A test asserts this by
attempting an overbooking `UPDATE` in raw SQL.

### 4.5 Where each check lives

| Check | UI | Backend | Database | Background job |
|---|---|---|---|---|
| Seats remaining | display only | recomputed per request | source of truth | — |
| Duplicate booking | hides button | 409 pre-check | partial unique index | — |
| Capacity | — | conditional UPDATE | CHECK constraint | — |
| Payment idempotency | — | idempotency key | unique (booking, key) | — |
| Abandoned checkout | — | — | — | hold reaper |

The UI layer is duplicated logic for user experience only. Removing every UI
check would leave correctness untouched.

---

## 5. API surface

Errors use RFC 7807 `application/problem+json`.

| Method | Path | Purpose | Notable responses |
|---|---|---|---|
| `GET` | `/api/trial-classes` | List classes with `seatsRemaining` | 200 |
| `GET` | `/api/trial-classes/{id}` | Single class detail | 200, 404 |
| `GET` | `/api/trial-classes/{id}/roster` | Confirmed students only | 200 |
| `GET` | `/api/parents/{id}/students` | Children for the booking form | 200 |
| `POST` | `/api/bookings` | Claim a seat, create hold | 201, 409 `CLASS_FULL`, 409 `DUPLICATE_BOOKING` |
| `POST` | `/api/bookings/{id}/payment` | Mock charge + confirm | 200, 402 `PAYMENT_DECLINED`, 409 `SEAT_UNAVAILABLE` |
| `GET` | `/api/bookings/{id}` | Status for the result screen | 200, 404 |
| `POST` | `/api/bookings/{id}/cancel` | Release a held seat | 200 |
| `POST` | `/api/demo/race` | Fire N concurrent bookings at one class | 200 (demo only) |

### `POST /api/bookings`
```jsonc
// request
{ "studentId": 1, "trialClassId": 7 }
// 201
{ "bookingId": 42, "status": "PENDING_PAYMENT", "holdExpiresAt": "...", "amountCents": 4900 }
```

### `POST /api/bookings/{id}/payment`
```jsonc
// request  — outcome drives the mock gateway so failures are demonstrable
{ "outcome": "SUCCESS", "idempotencyKey": "a3f1-..." }
// 200
{ "bookingId": 42, "status": "CONFIRMED", "paymentStatus": "SUCCEEDED" }
```

Replaying the same `idempotencyKey` returns the original result without a second
charge, enforced by `UNIQUE (booking_id, idempotency_key)`.

### `POST /api/demo/race`
Exists purely for the video walkthrough. Fires N concurrent booking+payment
attempts at the last seat and returns every outcome side by side, so the
invariant can be demonstrated in one click rather than narrated.

---

## 6. Package structure

```
com.ottodot.booking
├── BookingApplication.java
├── config/          DataSource, Clock bean, Jackson, OpenAPI, scheduling
├── web/             controllers, DTOs, ProblemDetail exception handler
├── service/         BookingService, PaymentService, RosterService
├── repo/            JdbcTemplate DAOs — all SQL lives here
├── payment/         PaymentGateway interface + MockPaymentGateway
├── scheduler/       HoldReaper (@Scheduled, every 30s)
└── demo/            RaceDemoController
```

A `Clock` bean is injected everywhere time is read, so hold expiry is testable
without `Thread.sleep`.

**Why JdbcTemplate and not JPA:** the seat claim is one conditional `UPDATE`
whose exact semantics are the thing being graded. Hibernate's first-level cache
and dirty-checking would sit between the code and that statement and make the
correctness argument harder to make, both in the README and on the video.

---

## 7. Testing

JUnit 5 + Testcontainers against real Postgres 16. An in-memory H2 would prove
nothing here — the whole design rests on Postgres row-locking semantics.

| Test | Asserts |
|---|---|
| `SeatClaimConcurrencyTest` | 20 threads on 4 seats via `CountDownLatch` → exactly 4 confirmed, 16 rejected, `claimed_seats = 4`. `@RepeatedTest(20)` |
| `SeatClaimConcurrencyTest` (parameterized) | capacity 1–4 × threads 2–20, invariant I1 holds in every combination |
| `LastSeatRaceHttpTest` | Two real concurrent HTTP payment calls against a running server — proves it end-to-end, not just inside one JVM transaction |
| `DuplicateBookingTest` | Second live booking for same (student, class) → 409; allowed again after PAYMENT_FAILED |
| `PaymentFailureTest` | Declined payment → absent from roster, seat released, retry succeeds |
| `HoldExpiryTest` | Injected `Clock` advances past hold → reaper sets EXPIRED and releases seat |
| `ConfirmAfterExpiryTest` | Payment lands after reaper ran and seat is gone → refund + CANCELLED |
| `IdempotencyTest` | Same key twice → one `payment_attempts` row, one charge |
| `InvariantTest` | I4: `claimed_seats` = count of live bookings, checked after every scenario |
| `SchemaConstraintTest` | Raw SQL overbooking `UPDATE` is rejected by the CHECK constraint |

GitHub Actions runs the full suite on push. A green CI badge in the README is
disproportionately convincing evidence that the concurrency claims are real.

---

## 8. Seed data

`V2__seed.sql`, covering exactly the cases the brief names:

| Class | State | Demonstrates |
|---|---|---|
| Science Trial — Mon | 0/4 confirmed | a class with available seats |
| Math Trial — Tue | **3/4 confirmed** | the last-seat race fixture |
| Science Trial — Wed | 1/4, plus an existing live booking for student #3 | duplicate booking attempt |
| Math Trial — Thu | 2/4 | payment failure path |

Plus 3 parents, 5 students, and one student flagged so the mock gateway always
declines — so the failure path is reachable from the UI without editing code.

---

## 9. Observability

Answers the README's "what would you monitor after release" with running code
rather than a wish list:

- **Micrometer counters** — `seat_claim_conflicts_total`, `payment_failures_total`,
  `holds_expired_total`, `refunds_issued_total`
- **Gauge** — seats remaining per class
- **Structured log** per state transition, correlated by `bookingId`
- **`/actuator/health`** wired into `docker-compose.yml`

`refunds_issued_total` is the one to alert on: any sustained non-zero rate means
the hold window is too short relative to real checkout time.

---

## 10. Build order

1. `pom.xml`, `docker-compose.yml` (postgres:16 + app), `application.yml`
2. `V1__schema.sql`, `V2__seed.sql`, verify Flyway runs clean
3. Repos + DTOs, `GET /api/trial-classes` end to end
4. `POST /api/bookings` with the conditional claim + duplicate handling
5. `MockPaymentGateway`, `POST /payment`, confirm path with `FOR UPDATE`
6. `HoldReaper` + `Clock` injection
7. `booking_events` writes on every transition
8. Test suite, then CI
9. Metrics, roster endpoint, race-demo endpoint

---

## 11. Deliberately out of scope

Named here so the README's cut list is concrete: real auth/sessions, a real
payment provider and its webhooks, waitlists for full classes, multi-seat or
sibling bookings, class timezone handling, notification email, and a
transactional outbox for confirmation events. Each is listed in the README with
one line on why it was cut and what it would take to add.
