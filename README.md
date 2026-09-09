# Ottodot Trial Booking

A trial-class booking system for a 4-seat class, built around one question:
**can two parents ever end up confirmed for the same last seat?**

The answer here is no, and the reason is a single conditional `UPDATE`.

- **Backend** — Java 21, Spring Boot 3.5, Postgres 16, Flyway, JdbcTemplate
- **Frontend** — Next.js 16 (App Router), React 19, TypeScript, Tailwind v4
- **Tests** — 118 integration tests on real Postgres via Testcontainers

---

## How to run

Requires Docker, JDK 21, and Node 20+.

```bash
# 1. Database (seeds itself through Flyway on first boot)
docker compose up -d db

# 2. Backend  -> http://localhost:8074
cd booking-be && mvn spring-boot:run

# 3. Frontend -> http://localhost:8073
cd booking-fe && npm install && npm run dev
```

Then open <http://localhost:8073>. The app asks for a password before anything
else - `644k1n9` by default - after which you pick a parent and book.

The password gate is a shared-secret door on the demo, not per-user accounts:
one password, checked by the API on every request. Override it, and pin the
session signing key so sessions survive a restart, with:

```bash
AUTH_PASSWORD=... AUTH_SECRET=... mvn spring-boot:run
```

### Run the tests

```bash
cd booking-be && mvn verify
```

118 tests, each against a Postgres 16 container started by Testcontainers.
Nothing else needs to be running — the suite manages its own database.

---

## What I built

A parent picks a child and a class, holds a seat, pays a mock gateway, and sees
the result. A teacher sees the roster. Everything else was cut.

| Screen | What it demonstrates |
|---|---|
| `/` | Parent picker (stands in for auth) |
| `/classes` | Seats remaining, auto-refreshing; full classes disabled |
| `/book/[classId]` | Claiming a seat — and both 409 refusals |
| `/checkout/[bookingId]` | Hold countdown, idempotency key, succeed/decline buttons |
| `/bookings/[bookingId]` | Final status plus the booking's full audit trail |
| `/admin/roster/[classId]` | Confirmed roster, with unpaid holds listed separately |
| `/demo/race` | Fires N simultaneous bookings at one class and shows every outcome |

---

## The core decision

**The seat is claimed when the booking is created, not when payment succeeds.**

A `PENDING_PAYMENT` booking holds a real seat for 10 minutes. Claiming it is one
statement:

```sql
UPDATE trial_classes
   SET claimed_seats = claimed_seats + 1
 WHERE id = :classId
   AND claimed_seats < capacity;
```

One row affected means the seat is yours; zero means the class is full.

**Why this is correct.** Under Postgres `READ COMMITTED`, a second transaction
reaching this row blocks on the row lock, and when the lock is released it
*re-reads the committed row and re-evaluates the `WHERE` clause*. It sees
`claimed_seats = 4` and matches nothing. There is no window between the check
and the increment, because they are the same statement — so no
`SELECT ... FOR UPDATE`, no `SERIALIZABLE`, and no retry loop is needed.

**Why claim early.** It resolves the last-seat race *before anyone is charged*.
The alternative — charge first, then try to claim — makes refunds the normal
outcome of a race rather than an exceptional one. Here, **every reservation is
taken before the gateway is called**, so no path can leave a parent charged for
a seat they did not get. The system has no refund operation at all.

**The tradeoff I accepted.** A held seat is a seat withheld from someone who
might have paid immediately. A parent who abandons checkout blocks a seat for up
to 10 minutes. I judged a briefly idle seat to be much cheaper than either an
overbooked class or a rejected checkout, and the hold reaper bounds the cost.
The 10-minute window is the tuning knob: too short and real checkouts start
getting refused at the last step, too long and classes look falsely full.

---

## The required scenario: the last-seat race

> A selects the last slot and moves to payment. B selects the same slot.
> B completes payment first. A then tries to pay.

With claim-at-booking, this cannot happen as written — **B never reaches
payment**:

1. Class is 3/4. A books → claim succeeds → 4/4, A holds the seat.
2. B books → the `UPDATE` matches zero rows → **409 `CLASS_FULL`**. B is turned
   away at booking time and is never charged.
3. A pays → the booking is still `PENDING_PAYMENT`, so the seat is provably
   still held → `CONFIRMED`.

If A instead abandons checkout, the reaper releases the hold after 10 minutes
and B can book normally.

Proven by `LastSeatRaceHttpTest` (real concurrent HTTP requests) and
`SeatClaimConcurrencyTest` (up to 32 threads against 4 seats, repeated).

### The residual race

One window survives: A's hold expires *while A's payment is in flight*, and the
world moves on. Confirmation locks the booking row `FOR UPDATE`, which
serialises it against the reaper:

- **still `PENDING_PAYMENT`** → the reaper has not run and cannot run while we
  hold this lock, so the seat is guaranteed still claimed → charge → `CONFIRMED`.
- **already `EXPIRED`** → the reaper won, and the seat went back to the pool.
  Before charging anything, re-acquire **both** reservations:
  1. the seat, via the same conditional `UPDATE` — if it fails, answer
     `409 SEAT_UNAVAILABLE`, **uncharged**;
  2. the live-booking slot, by moving the row back to `PENDING_PAYMENT` — the
     partial unique index rejects this if the child has rebooked the class
     meanwhile, so that answers `409 DUPLICATE_BOOKING`, **uncharged**.

  Only once both succeed is the gateway called, and confirming afterwards
  cannot fail: the seat is claimed, the slot is held, and the row is locked.

**Reserving before charging is what removes the refund from this system.** The
earlier design charged first and compensated afterwards, which meant every
failure past that line was a failure holding someone's money. `ApiException`
before the charge is a far better outcome than a refund after it.
`ConfirmAfterExpiryTest` covers all three branches.

---

## Backend design

### Data model

Six tables. `parents` → `students` → `bookings` ← `trial_classes`, plus
`payment_attempts` and an append-only `booking_events` log.

The load-bearing columns:

- `trial_classes.claimed_seats` — counts **held and confirmed** bookings. A held
  seat is a real seat; that is what makes the race resolve before payment.
- `bookings.hold_expires_at` — set while `PENDING_PAYMENT`, `NULL` otherwise.
- `payment_attempts.idempotency_key` — unique per booking.

Three guarantees live in the schema rather than in application code:

| Invariant | Enforced by |
|---|---|
| I1 · never exceed capacity | `CHECK (claimed_seats BETWEEN 0 AND capacity)` |
| I2 · one live booking per child per class | partial unique index on `(student_id, trial_class_id) WHERE status IN ('PENDING_PAYMENT','CONFIRMED')` |
| I3 · roster is confirmed-only | roster query filters on status |
| I4 · `claimed_seats` == count of live bookings | every transition adjusts both in one transaction |

I4 is the one that catches bugs the others miss — it is asserted after **every**
scenario in the test suite, and it is what caught the reaper bug described in
`AI_USAGE.md`.

### Booking statuses

`PENDING_PAYMENT` → `CONFIRMED` | `PAYMENT_FAILED` | `EXPIRED` | `CANCELLED`

A seat is held while `PENDING_PAYMENT` or `CONFIRMED`, and released on entry to
any other state. `PAYMENT_FAILED`, `EXPIRED` and `CANCELLED` are deliberately
excluded from the unique index, so a parent whose card was declined can simply
book again.

### API

| Method | Path | Notable responses |
|---|---|---|
| `POST` | `/api/auth/login` | `200` + session token; `401 INVALID_CREDENTIALS`; `429 TOO_MANY_ATTEMPTS`. The only route reachable without a session |
| `GET` | `/api/auth/session` | `200` when the caller's token is still valid |
| `GET` | `/api/trial-classes` | seats remaining per class |
| `GET` | `/api/trial-classes/{id}/roster` | confirmed students, holds listed separately |
| `GET` | `/api/parents`, `/api/parents/{id}/students` | `?trialClassId=` adds each child's live booking for that class |
| `POST` | `/api/bookings` | `201`; `409 CLASS_FULL`; `409 DUPLICATE_BOOKING` |
| `POST` | `/api/bookings/{id}/payment` | `200`; `402 DECLINED`; `409 SEAT_UNAVAILABLE`; `409 DUPLICATE_BOOKING` |
| `GET` | `/api/bookings/{id}` | status + event history |
| `POST` | `/api/bookings/{id}/cancel` | releases a held seat; `409 NOT_CANCELLABLE` once paid |
| `POST` | `/api/demo/race` | demo only: N simultaneous attempts, all outcomes |

Errors are RFC 7807 `application/problem+json` with a machine-readable `code`
the frontend branches on.

Every route except `POST /api/auth/login` is behind `AuthFilter` and answers
`401 UNAUTHENTICATED` without a valid session token; `/actuator/**` stays open
so probes and Prometheus do not need the password. The token is an HMAC over
its own expiry, so verifying it costs no round trip - the tradeoff is that a
token cannot be revoked before it expires, and rotating `AUTH_SECRET` (or
restarting, when it is unset) is what invalidates every session at once.

The frontend's login dialog is presentation, not protection: the browser is
never what decides. It exists so a signed-out visitor sees a password box
instead of seven pages of `UNAUTHENTICATED`.

### Preventing duplicate bookings

Two layers. A friendly pre-check returns `409 DUPLICATE_BOOKING` for the common
case, but it is **not** the guarantee — under concurrency, several requests can
pass it before any of them writes. The partial unique index is the guarantee:
the loser's `INSERT` fails and its whole transaction, *including its seat
claim*, rolls back. `DuplicateBookingTest` fires 8 simultaneous requests for one
child and asserts exactly one booking and exactly one claimed seat.

### Handling payment failure

A declined payment is a successful request with an unsuccessful outcome, so the
service **returns** it rather than throwing — throwing would roll back the
`PAYMENT_FAILED` transition it needs to persist. The seat is released
immediately, the attempt is recorded as `FAILED`, and the child never appears on
the roster, because the roster is confirmed-only.

Payments are idempotent per `(booking_id, idempotency_key)`. The checkout screen
generates one key on mount and reuses it for every retry, so a double-click
cannot double-charge.

### Where each check lives

| Check | UI | Backend | Database | Background job |
|---|---|---|---|---|
| Seats remaining | display only | re-read per request | source of truth | — |
| Duplicate booking | hides the button | 409 pre-check | **partial unique index** | — |
| Capacity | disables full classes | **conditional `UPDATE`** | `CHECK` constraint | — |
| Payment idempotency | reuses one key | looks up prior attempt | **unique constraint** | — |
| Abandoned checkout | countdown | — | — | **hold reaper** |

The bold cell in each row is the one that actually guarantees the property.
Every UI check is advisory: deleting all of them would leave correctness
untouched, and the backend still answers 409.

---

## Testing

118 tests against real Postgres 16 (Testcontainers). An in-memory database would
prove nothing here — the entire argument rests on Postgres row-locking
semantics, partial indexes, and `FOR UPDATE SKIP LOCKED`.

| Test | Asserts |
|---|---|
| `SeatClaimConcurrencyTest` | up to 32 threads on 1–4 seats → exactly capacity confirmed, repeated |
| `LastSeatRaceHttpTest` | the brief's scenario over real concurrent HTTP |
| `DuplicateBookingTest` | 8 simultaneous requests for one child → one booking, one seat |
| `PaymentFailureTest` | declined → off the roster, seat released, retry succeeds |
| `HoldExpiryTest` | expiry releases the seat; confirmed bookings are never touched |
| `ConfirmAfterExpiryTest` | late payment reclaims the seat, or is refused uncharged (seat gone / child rebooked) |
| `ReaperPaymentRaceTest` | reaper and payment colliding on the same rows stay consistent |
| `IdempotencyTest` | six simultaneous clicks → one charge |
| `SchemaConstraintTest` | raw SQL overbooking is rejected by the database |

Every test asserts invariant I4 before finishing.

**Manual click-path** (once all three services are running):

1. `/classes` → *Math – Fractions* shows **1 of 4 seats left**.
2. Book it as Priya → hold → pay → roster shows 4/4. The class is now **Full**.
3. Book *Science – Simple Circuits* as Maya → **409 duplicate** (she is already in it).
4. Book *Math – Area and Perimeter* as Noah → **Pay with a declining card** →
   `PAYMENT_FAILED`, seat returned, roster unchanged.
5. `/demo/race` → pick a class with one seat, fire 8 contenders → exactly one
   `CONFIRMED`, seven `CLASS_FULL`, none of them charged.

### Frontend tests

None. Playwright setup would have consumed time better spent on the backend
tests, and the graded correctness lives there. This is a deliberate cut, not an
oversight.

---

## Seed data

`V2__seed.sql` covers exactly the cases the brief names:

| Class | State | Demonstrates |
|---|---|---|
| Science – States of Matter | 0/4 | a class with available seats |
| Math – Fractions | **3/4 confirmed** | the last-seat race fixture |
| Science – Simple Circuits | 1/4, Maya already booked | a duplicate attempt |
| Math – Area and Perimeter | 2/4 | the payment-failure path |

Plus 3 parents and 5 students. **Noah Okafor** is flagged
`always_fails_payment`, so the decline path is reachable from the UI without
editing code.

---

## Assumptions

- No authentication. A parent is chosen from a dropdown; a real system would
  derive them from a session.
- One seat per booking. No sibling or group bookings.
- Every trial costs the same fixed price (4900 cents).
- Class times are stored as `TIMESTAMPTZ` and rendered in the browser's locale;
  no per-class timezone handling.
- The mock gateway is synchronous. A real provider is webhook-driven, which is
  the single biggest thing this mock hides — see below.
- A 10-minute hold is long enough for a real checkout. This is a guess that
  production data would correct.

---

## What I deliberately cut

| Cut | Why | What it would take |
|---|---|---|
| Per-user accounts | The gate is one shared password, so it says *someone signed in*, not *who* - "which parent" is still a dropdown | A `parents` credential table, and deriving `parentId` from the session instead of a cookie |
| Real payment provider | The mock exercises every branch | Webhook endpoint + outbox; confirmation becomes eventual, not synchronous |
| Waitlists | Beyond "trial booking only" | A queue table + notification on seat release |
| Sibling/group bookings | Would change the claim from +1 to +N | `tryClaimSeats(n)` and a booking→students join table |
| Frontend tests | Backend tests are what is graded | Playwright against the running stack |
| Email/notifications | No correctness content | Transactional outbox + worker |
| Dockerised app container | `mvn spring-boot:run` is one command | Dockerfile + compose service |

---

## What I would monitor after release

The metrics are already wired (Micrometer → `/actuator/prometheus`):

- **`booking_holds_expired_total` relative to `booking_payment_failures_total`**
  — the pair to watch. A rising expiry rate means the hold window is too short
  for real checkout times, and parents are being refused at the last step. This
  is the signal that tells you a *correct* system is behaving *unkindly*, and it
  is the one I would alert on.
- `booking_seat_claim_conflicts_total` — high values mean demand exceeds
  capacity; a product signal, not a bug.
- `booking_payment_failures_total` — a spike means the provider is unwell.
- **Invariant I4 as a scheduled assertion in production**: any class where
  `claimed_seats != count(live bookings)` is a bug that has already happened.
  I would run this as a periodic query and page on a non-empty result.

Plus structured logs per transition, correlated by `bookingId`, and
`/actuator/health` for the database connection.

---

## What I would do next

1. **Make payment asynchronous.** The mock hides that real gateways confirm by
   webhook. The booking would move to a `PAYMENT_PENDING` state and confirm on
   callback, with the hold outliving the redirect.
2. **A transactional outbox** for confirmation emails, so a notification cannot
   be sent for a booking that rolled back.
3. **Tune the hold window from data** rather than by guess — the p99 of real
   checkout duration.
4. **Waitlists**, which turn a `CLASS_FULL` rejection from a dead end into a
   queue, and give the released-seat path somewhere to go.
5. **Load-test the claim statement.** Row-level contention on one hot class is
   the natural bottleneck; I would want to know where it starts to hurt.

---

## Time spent

Roughly 4 hours, weighted heavily toward the data model, the concurrency design,
and the tests — in that order. The frontend was kept deliberately thin.

See [`AI_USAGE.md`](AI_USAGE.md) for how AI tools were used, and
[`booking-docs/`](booking-docs/) for the working design notes written before the
code.
