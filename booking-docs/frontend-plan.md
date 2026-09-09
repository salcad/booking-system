# Frontend Implementation Plan — `booking-fe`

Next.js 15 (App Router) · React 19 · TypeScript (strict) · Tailwind v4

---

## 1. Scope discipline

The brief says this explicitly:

> A polished frontend is not required... we care more about the data model,
> backend logic, invariants, tests, and your explanation.

So the frontend has exactly one job: **make the backend's correctness visible**.
Every screen exists to demonstrate an invariant, not to look good. Over-building
here is the easiest way to signal that the brief was misread.

**Not building:** auth/login, component library, design system, animations,
dark mode, responsive breakpoints beyond what Tailwind gives for free, form
libraries, client state managers, i18n. Parent identity is selected from a
dropdown seeded in the database.

---

## 2. Routes

| Route | Type | Purpose |
|---|---|---|
| `/` | server | Parent picker → sets `parentId` in a cookie, links to classes |
| `/classes` | server + client island | Class list with live seats remaining |
| `/book/[classId]` | server + client form | Pick a child, confirm, create the hold |
| `/checkout/[bookingId]` | client | Mock payment, hold countdown, Succeed/Decline |
| `/bookings/[bookingId]` | server | Final status screen |
| `/admin/roster/[classId]` | server | Confirmed roster + seat counts |
| `/demo/race` | client | Fires the concurrent-race demo, shows outcomes |

Seven small pages. Nothing else.

---

## 3. Structure

```
booking-fe/
├── app/
│   ├── layout.tsx                  root layout, Tailwind import
│   ├── page.tsx                    parent picker
│   ├── classes/page.tsx
│   ├── book/[classId]/page.tsx
│   ├── checkout/[bookingId]/page.tsx
│   ├── bookings/[bookingId]/page.tsx
│   ├── admin/roster/[classId]/page.tsx
│   └── demo/race/page.tsx
├── components/
│   ├── ClassCard.tsx               seats remaining + full/available state
│   ├── SeatBadge.tsx               "2 of 4 seats left" / "Full"
│   ├── StatusBadge.tsx             one colour per booking status
│   ├── HoldCountdown.tsx           client, ticks down to hold expiry
│   └── ErrorNotice.tsx             renders a ProblemDetail
├── lib/
│   ├── api.ts                      typed fetch client, ProblemDetail handling
│   └── types.ts                    mirrors backend DTOs
└── tailwind.config.ts
```

---

## 4. Data flow

- **Reads** happen in server components calling the Spring API directly with
  `fetch(..., { cache: 'no-store' })`. Seat counts must never be cached — a
  stale count is exactly the bug this whole exercise is about.
- **Writes** (`POST /bookings`, `POST /payment`) go through client components so
  errors can be rendered inline, followed by `router.refresh()` to re-pull
  server state.
- No client-side cache layer, no SWR/React Query. Not enough state to justify it.

`lib/types.ts` mirrors the backend DTOs by hand:

```ts
export type BookingStatus =
  | 'PENDING_PAYMENT' | 'CONFIRMED' | 'PAYMENT_FAILED' | 'CANCELLED' | 'EXPIRED';

export interface TrialClass {
  id: number; subject: string; startsAt: string;
  capacity: number; seatsRemaining: number;
}

export interface Booking {
  id: number; studentId: number; trialClassId: number;
  status: BookingStatus; holdExpiresAt: string | null;
}

export interface ProblemDetail {
  title: string; status: number; detail: string;
  code?: 'CLASS_FULL' | 'DUPLICATE_BOOKING' | 'SEAT_UNAVAILABLE' | 'PAYMENT_DECLINED';
}
```

Hand-mirroring is deliberate — generating a client from OpenAPI is more setup
than seven pages justify.

---

## 5. Screen behaviour

### `/classes`
Cards showing subject, start time, and a `SeatBadge`. Full classes render
disabled with "Full". Seat counts poll every 5s via a small client island so a
seat disappearing during the demo is visible without a manual refresh.

**The UI check is advisory only.** Hiding the button on a full class is UX; the
backend still returns 409 and the frontend still renders it. This point is worth
saying out loud in the video.

### `/book/[classId]`
Child dropdown (from `GET /api/parents/{id}/students`) plus a Confirm button.
On submit → `POST /api/bookings`:

- `201` → redirect to `/checkout/[bookingId]`
- `409 DUPLICATE_BOOKING` → "This child is already booked into this class."
- `409 CLASS_FULL` → "This class just filled up." + link back to `/classes`

Those two 409s are the visible proof of invariants I1 and I2 — they get their
own beat in the walkthrough.

### `/checkout/[bookingId]`
The most important screen. Shows:
- what is being paid for and the amount
- a **`HoldCountdown`** ticking toward `holdExpiresAt` — makes the seat-hold
  model legible at a glance
- two buttons, **Pay (succeeds)** and **Pay (declines)**, driving the mock
  gateway's `outcome` field so the failure path is reachable without code edits

An `idempotencyKey` is generated once with `crypto.randomUUID()` when the page
mounts and reused for every retry, so double-clicking cannot double-charge.
Buttons disable while in flight.

Outcomes → `/bookings/[bookingId]`, except `409 SEAT_UNAVAILABLE`, which shows
the refund message inline: *"Your payment was refunded — this seat was taken
while you were checking out."*

### `/bookings/[bookingId]`
`StatusBadge` plus a plain-language line per status, and the booking's event
history from `booking_events` — a visible audit trail is more convincing than a
single status word.

### `/admin/roster/[classId]`
Confirmed students only, with `n / 4 confirmed` in the header. Pending holds are
listed separately and clearly marked *not on the roster* — that separation is
invariant I3 made visual.

### `/demo/race`
Class picker, an "Fire N concurrent bookings" button, and a results table of
every outcome side by side: one `CONFIRMED`, the rest `CLASS_FULL`, with final
`claimed_seats` shown. One click, invariant demonstrated. Built specifically for
the 5–8 minute recording.

---

## 6. Styling

Tailwind utilities inline. A handful of tokens in `globals.css` for status
colours (confirmed green, pending amber, failed/cancelled red, expired grey),
system font stack, one card style, one button style. No component library, no
custom design work.

---

## 7. Config

```
# .env.local
API_BASE=http://localhost:8074          # server components
NEXT_PUBLIC_API_BASE=http://localhost:8074   # client components
```

Both come from `docker-compose.yml` so `docker compose up` runs the whole demo.

---

## 8. Build order

1. Scaffold, Tailwind, `lib/types.ts` + `lib/api.ts`
2. `/` parent picker and cookie
3. `/classes` + `ClassCard` + `SeatBadge`
4. `/book/[classId]` with both 409 paths rendered
5. `/checkout/[bookingId]` + `HoldCountdown` + idempotency key
6. `/bookings/[bookingId]` with event history
7. `/admin/roster/[classId]`
8. `/demo/race`
9. Polling island on `/classes`

Steps 1–6 are the demo spine. If time runs short, 7–9 are the first to go — and
per the brief, the frontend as a whole is cut before any backend test is.

---

## 9. Verification

No frontend test suite. The graded correctness lives in the backend's
Testcontainers tests, and Playwright setup would consume time better spent
there. The README says this explicitly rather than leaving it as an apparent
gap. Manual verification is a scripted click-path in the README covering:
happy path, duplicate, class full, payment declined, hold expiry, and the race
demo.

---

## 10. Implementation notes — where the build deviated from this plan

### 10.1 Next.js 16, not 15

`create-next-app@latest` now scaffolds Next 16 (React 19.2, Tailwind v4). The
App Router API is unchanged for everything this plan uses; the differences that
mattered:

- `params` is a `Promise`, and route props use the generated
  `PageProps<'/book/[classId]'>` types. These are emitted by `next typegen`, so
  a new dynamic route fails to typecheck until they are regenerated.
- Turbopack is the default for both `dev` and `build`.
- Tailwind v4's `@apply` only accepts real utilities, not other component
  classes. The shared button base had to be registered with `@utility btn`
  before `.btn-primary { @apply btn ... }` would compile.

### 10.2 A rewrite proxy instead of CORS

Client components post to `/api/*` on their own origin, and `next.config.ts`
rewrites that to the backend. The alternative was relaxing CORS on the Spring
side purely to accommodate the demo UI, which is a worse trade than one rewrite
rule. Server components still call the backend directly by absolute URL, since
a server-side relative fetch has no origin to resolve against.

### 10.3 Payment failures are not transport errors

`POST /payment` answers 402 for a declined card and 409 for a lost seat, but
both carry a normal `PaymentResponse` body describing a legitimate outcome. A
genuine refusal (an unpayable booking) *also* uses 409 with an RFC 7807 body, so
the status code alone cannot separate them. `api.pay` therefore discriminates on
body shape rather than status, and is the one call that does not go through the
generic error path.

### 10.4 JSX must not be built inside try/catch

The first draft of `/book/[classId]` wrapped its whole render in a try/catch.
React does not render the element where it is constructed, so a render error
would escape the catch entirely — `react-hooks/error-boundaries` flags this.
Only the data fetch belongs in the try; the JSX returns after it.

### 10.5 The duplicate is now known before the click

§5 specified the booking form as a dropdown plus a Confirm button, with
`409 DUPLICATE_BOOKING` rendered as a message. That is what was built, and the
result was a screen offering **Hold my seat** to a child who was already
enrolled, above a red notice saying so. Nothing was broken — the 409 was
correct, the seat was never at risk — but the only outcome the button could
produce was the error already printed above it.

The plan is what caused it. It described the duplicate as a *response to
handle*, so the form learned about it by making a request that could only fail.
`GET /parents/{id}/students` now takes `?trialClassId=` and reports each child's
live booking (see backend plan §12.8), so the page renders the button already
disabled, with a plain explanation rather than an error.

Two consequences for the form's state:

- `CLASS_FULL` and `DUPLICATE_BOOKING` no longer share one `error` slot. They
  are different kinds of fact: a full class can stop being true while the parent
  sits on the page, so it is transient and cleared on the next attempt; a
  duplicate is about one child and is held keyed by student id, so switching
  children in the dropdown cannot carry it across to a sibling who can book.
- The advisory-only rule from §5 still holds, and matters more here than on
  `/classes`. The pre-check is a separate read from the insert, so it cannot see
  a booking made in the gap. The 409 handler stays, and now pins the refusal to
  that child.

### 10.6 Cut from this plan

Nothing material. `ErrorNotice`, `HoldCountdown`, `SeatBadge`, `StatusBadge` and
the polling island were all built as described. `AutoRefresh` replaced the
"client island" sketch — it simply calls `router.refresh()` on an interval,
which re-runs the server component and needs no client-side fetching at all.
