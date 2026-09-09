# AI Usage

## Tools

  **Claude Code** (Opus) for most of the work.


## What I used AI for

I worked design-first. Before writing any code I put together
[`booking-docs/backend-plan.md`](booking-docs/backend-plan.md) and
[`frontend-plan.md`](booking-docs/frontend-plan.md), using AI as a thinking
partner to argue through the concurrency model, the state machine, and where
each invariant should be enforced. Those documents became the spec. The
implementation follows them closely enough that the places it deviates are
worth listing on their own (see §12 of the backend plan).

In practice, AI wrote most of:

- the Flyway schema and seed data,
- the repositories, services, and controllers,
- all 118 tests,
- the entire frontend.

What I kept for myself was the architecture decision (claim the seat at booking
time, not at payment time) and the call on which invariants belong in the
database rather than in application code. Those two choices are what make the
system correct or not. The rest is execution.

## Where AI moved me fastest

The test suite, easily. Writing 118 Testcontainers tests by hand would have
eaten most of the timebox: a start-barrier concurrency harness, a mutable
`Clock` so hold expiry is testable without `Thread.sleep`, per-test fixtures,
and parameterised capacity/contention sweeps. AI produced that scaffolding in
minutes, which is what made it affordable to test the *interesting* cases
(reaper-versus-payment collisions, idempotent double submits, raw-SQL attacks
on the constraints) instead of just the happy path.

That inversion is the real benefit. Cheap tests meant I could spend my thinking
on which failure modes were worth pinning down.

## Where I corrected the AI

The booking page showed a child who was already enrolled, the red
`DUPLICATE_BOOKING` notice underneath, and an enabled **Hold my seat** button.
Nothing was broken: the seat was never at risk, the constraint held, the 409
was correct. But the screen was offering an action whose only possible outcome
was the error already printed above it.

Two separate causes, and only the second is interesting.

The shallow one was state shape. `BookingForm` kept every refusal in a single
`error` slot and gated the button solely on `submitting`, so the notice
survived a change of child in the dropdown it could sit there claiming the
currently selected child was already booked when it was describing the previous
one. A `CLASS_FULL` and a `DUPLICATE_BOOKING` are not the same kind of fact and
should not share a variable: the first is about the class and can stop being
true while the parent sits on the page, the second is about one child and
cannot. They are now held separately, and the duplicate is keyed by student id.

The real one was that the frontend had no way to know. `GET
/parents/{id}/students` returned `{id, name, grade}`, so the only way for the
page to discover that a child was already enrolled was to attempt the booking
and read the rejection. The UI was using the invariant's enforcement as its
query mechanism. That is why the button had to be enabled: on first render the
page genuinely did not know, and could not.

I had accepted that. My own note at the time said it "would mean widening the
students endpoint  a backend change, so I left it alone", which on rereading
is not a justification, just an observation about which directory the fix lives
in. The endpoint now takes an optional `?trialClassId=`, and each child reports
the live booking they hold for that class:

```json
{ "id": 2, "name": "Arjun Raman", "grade": "P3",
  "existingBooking": { "bookingId": 7, "status": "CONFIRMED" } }
```

The page asks for it alongside the class, so the disabled button and the
explanation are in the server-rendered HTML. The parent never sees a refusal
for something that was knowable before they clicked.

Two things I was careful about, because this is the kind of change that
quietly creates the bug it is meant to prevent:

- **It is a hint, not a decision.** The read is a separate statement from the
  insert, so a booking made in the gap is invisible to it. `uq_live_booking` is
  still the only thing that rejects a duplicate, and the form still handles the
  409  now by pinning it to that child. Had I let the pre-check *replace* the
  rejection path I would have swapped a harmless extra click for a real race.
- **The predicate must not drift.** The new query's definition of a live
  booking has to stay character-for-character the set in `uq_live_booking` and
  `findLive`. If it ever includes `PAYMENT_FAILED`, the UI locks a parent out
  of a class the API would happily let them book  a worse failure than the one
  I set out to fix, and a silent one. `StudentBookingStateTest` asserts every
  excluded status from the outside over HTTP: declined payment and expired hold
  both report the child as free, because the write side would accept them.

The transferable lesson is about where I was looking. Every earlier finding in
this document came from interrogating code I already suspected. This one was
invisible in the code each piece was correct in isolation, and the defect
only existed in the composition, on screen, in a state I had to be looking at
to notice. I found it because I opened the page and something felt wrong about
a button. That is not a code review technique, and I do not think a more
careful reading pass would have got there.

## What I would change about my AI workflow

I would drive the concurrency work test-first, and treat "the test fails when
the mechanism is removed" as part of the definition of done. The one place I
worked that way the code was right first time; the one place I did not, the
model handed me a confident comment in place of a guarantee and I believed it.

Where I did work test-first, in argument form, was the seat-claim `UPDATE`.
Before accepting it I made the model state the failing case and why it cannot
happen: under `READ COMMITTED`, an `UPDATE` that blocks on another transaction
re-evaluates its `WHERE` clause against the newly committed row, so two parents
racing for the last seat cannot both pass the capacity check. The claim was
falsifiable, I checked it, and the statement never needed a second pass.

The hold reaper  the scheduled job that releases seats held by parents who
never paid  got no such treatment. It arrived with an `@Transactional`
annotation and a comment asserting that its `FOR UPDATE SKIP LOCKED` serialised
it against payment confirmation. The comment was false: `sweep()` called the
annotated method on `this`, and Spring's transactions are proxy-based, so the
self-invocation bypassed them entirely. Every statement autocommitted and the
row lock was released the moment the `SELECT` returned. The reaper could then
read a booking as `PENDING_PAYMENT`, have `PaymentService` confirm and commit it
underneath, and overwrite it to `EXPIRED`  silently dropping a paid student off
the roster.

Written test-first, that job would have gone in this order, before `HoldReaper`
had a body:

1. **The invariant assertion, before any feature test.** I4  `claimed_seats`
   equals the count of live bookings — asserted at the end of every scenario and
   belonging to no feature in particular, so that any test which happens to
   provoke the race reports it whether or not it was looking for it.
2. **A red test for the collision.** `ReaperPaymentRaceTest`: a sweep and a
   payment confirmation aimed at the same booking, written while the reaper is
   still a stub, failing because nothing serialises them yet.
3. **The mechanism, only as far as green.** A `TransactionTemplate` rather than
   `@Transactional`, because programmatic demarcation is proxy-independent and
   so does not depend on how the method is reached; plus `expireIfStillPending`,
   which carries `AND status = 'PENDING_PAYMENT'` in the statement itself, so
   the guard holds even if the surrounding transaction is lost again in a later
   refactor.
4. **Then take the mechanism back out and watch it go red.** Reverting the
   `TransactionTemplate` alone drops the test from 12 of 12 confirmed bookings
   surviving to 0. This is the step that separates a test which passes from a
   test which would have caught the bug.

In reality that ran backwards  code, then bug, then test. Step 4 is the only
part I did in the right order, and it is the whole reason I trust the test.

So the rule I would carry forward is the loop, not just its conclusion: no
comment may claim a concurrency guarantee unless it names the mechanism
providing it and points at a test that fails when that mechanism is removed. If
I cannot write the failing test first, I do not yet understand the guarantee
well enough to accept the code a model hands me.

## How I verified the final implementation

1. **118 automated tests** against real Postgres 16 via Testcontainers,
   including up to 32 threads racing 4 seats, repeated, and the brief's exact
   scenario driven over concurrent HTTP. Invariant I4 is asserted after every
   scenario.
2. **Deliberately breaking the fix** to confirm the reaper regression test
   actually detects the bug.
3. **Manual end-to-end runs** against the seeded database, covering each case
   the brief names: available seats, the 3/4 race fixture, a duplicate attempt,
   and a declined payment. I checked these through the frontend proxy the way
   the browser would issue them, looking at both the JSON and the rendered
   pages, including that a held-but-unpaid child shows as `0 / 4 confirmed`
   under "Held, not paid" and only reaches the roster after payment.
4. **Reading the server-rendered HTML** of the booking page for a parent whose
   child is already enrolled, to confirm the `disabled` attribute is in the
   markup rather than applied after a failed request — the whole point of the
   duplicate pre-check is that it costs no round-trip, so checking it in the
   browser after hydration would not have proved anything.
5. **The race demo endpoint**, 8 simultaneous parents against a 1-seat class:
   exactly one `CONFIRMED`, seven `CLASS_FULL`, none of the losers charged.
6. **Metrics inspected** at `/actuator/prometheus` after the manual runs, to
   confirm the counters tell the same story the database does.

The claims in [`README.md`](README.md) are the ones I could demonstrate. Where I
could not, such as the behaviour of a real webhook-driven payment provider, I
said so rather than implying coverage I do not have.

## Appendix: the annotation that did nothing

Kept for last because it is the one finding here that is not really about this
codebase. Any Spring project can hit it, and it fails without a single warning.

`@Transactional` is not a property of a method. It is a property of the *call*
that reaches the method. Spring wraps the bean in a proxy, and the proxy is what
opens and commits the transaction, so only a call arriving from outside the
object gets one. A call from inside the object goes straight to the real method
and never touches the proxy:

```java
@Scheduled(fixedDelay = 30_000)
public void sweep() {
    releaseExpiredHolds();   // this.…  → bypasses the proxy entirely
}

@Transactional               // → inert on the call above. No warning. No error.
public int releaseExpiredHolds() { ... }
```

The damage is not that the annotation is missing; it is what fills the gap.
Without a transaction every statement autocommits, and a lock in Postgres lives
only as long as the transaction holding it. So `SELECT ... FOR UPDATE SKIP
LOCKED` takes its locks and drops them the instant the `SELECT` returns — the
exact serialisation the design depends on, gone, while the code still reads as
though it were there. The reaper could then see a booking as `PENDING_PAYMENT`,
have `PaymentService` confirm and commit it underneath, and overwrite it to
`EXPIRED`.

Worse, this is a bug a test can hide. A test that autowires the bean holds the
*proxy*, so calling the annotated method from a test opens a real transaction —
green — while the scheduler's self-invocation gets none. The annotation would
have been alive on the tested path and dead on the production one, which is a
property of the wiring, not of anything the test could assert.

The fix is two layers, because either alone would be a guess about the future:

- **`TransactionTemplate` instead of the annotation.** The transaction is opened
  by the code itself rather than by a proxy, so how the method is reached stops
  being a variable — scheduler, self-call, or test, it is the same transaction.
- **`expireIfStillPending`, a guard inside the statement.** The expiry `UPDATE`
  carries `AND status = 'PENDING_PAYMENT'` and only the caller that actually
  performed the transition releases the seat. If the transaction were lost again
  in a later refactor, the outcome would still be correct.

Self-injection, `AopContext.currentProxy()`, and moving the method to a separate
bean all work too. I did not use them because each one leaves "does this call go
through the proxy?" as a live question that the next person to touch the file
has to re-answer. Programmatic demarcation deletes the question.

Which to reach for. `@Transactional` is still the right default, and most of
this codebase uses it: it is shorter, and a service method called from a
controller always arrives through the proxy, so the trap cannot fire. I reach
for `TransactionTemplate` in three situations when the entry point calls
inward on `this`, as a `@Scheduled` sweep does; when the transaction needs to be
narrower than a whole method, such as one transaction per batch inside a long
loop; and when the boundary is load-bearing enough that I want it stated in the
code rather than inferred from a call path. `HoldReaper` is all three at once,
which is why it is the only place in the backend that demarcates by hand.

The general form, and the reason this sits in a document about working with AI:
annotation-driven behaviour is contextual, so a model asserting that an
annotation makes something safe is asserting something it cannot see from the
method it is looking at. That is precisely the class of claim I should have
demanded a mechanism for.
