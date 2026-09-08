# Planning Docs

Working plans for the Ottodot full-stack take-home (trial booking reliability).
These are implementation notes, not the submission README — that one lives at
the repo root and is written last.

| Doc | Contents |
|---|---|
| [backend-plan.md](backend-plan.md) | Invariants, schema, state machine, concurrency design, API, tests, observability |
| [frontend-plan.md](frontend-plan.md) | Routes, structure, data flow, screen behaviour, build order |

## The problem in one paragraph

Trial classes cap at 4 students. Parents book and pay. The system must never
overbook, never double-book the same child, never put an unpaid child on the
roster, and must resolve the last-seat race so that at most one parent ends up
confirmed. Everything else is secondary — the brief says so directly.

## The core decision

**Claim the seat when the booking is created, not when payment succeeds.**

A booking at `PENDING_PAYMENT` holds a real seat with a 10-minute expiry. A
background reaper releases abandoned holds. The seat claim is a single
conditional `UPDATE`:

```sql
UPDATE trial_classes SET claimed_seats = claimed_seats + 1
 WHERE id = :id AND claimed_seats < capacity;
```

Zero rows affected means the class is full. Postgres re-evaluates that `WHERE`
after the row lock is released, so two concurrent claimers can never both win.

This resolves the brief's race **before anyone is charged** — the second parent
is rejected at booking time and never reaches payment. The pay-then-claim
alternative was rejected because it makes refunds the normal path rather than
the exceptional one. Refunds still exist as a fallback for the one genuine edge
case (hold expires mid-payment and the seat is taken), which is the only place
in the system money is returned.

## Tech stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 3.3, Maven, JdbcTemplate, Flyway |
| Database | Postgres 16 (Docker) |
| Frontend | Next.js 15 App Router, React 19, TypeScript, Tailwind v4 |
| Tests | JUnit 5 + Testcontainers, GitHub Actions CI |

JdbcTemplate over JPA on purpose: the seat claim is one `UPDATE` whose exact
semantics are the thing being graded, and an ORM's caching would obscure it.

## Submission checklist

- [ ] Public GitHub repo (no zip files)
- [ ] `README.md` — how to run, what was built, time spent, assumptions,
      architecture decisions, what was cut, what to monitor, what's next
- [ ] `AI_USAGE.md` — tools used, where AI helped, where it was **corrected or
      rejected**, workflow changes, how the result was verified
- [ ] Seed data + one-command setup
- [ ] Tests or clear verification steps
- [ ] Video walkthrough, 5–8 min: run it, explain the race handling, name the
      tradeoffs
