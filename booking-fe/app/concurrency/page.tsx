import type { Metadata } from "next";
import { SeatRaceSimulator } from "@/components/SeatRaceSimulator";
import {
  LifecycleFigure,
  RaceSequenceFigure,
  ReaperFigure,
} from "@/components/ConcurrencyFigures";

export const metadata: Metadata = {
  title: "Concurrency notes · Ottodot Trial Booking",
  description:
    "How this booking system settles the race for the last seat, and why the guarantee lives in the database rather than in application code.",
};

function Gate({
  n,
  check,
  onFail,
  verdict,
  real,
}: {
  n: number;
  check: string;
  onFail: string;
  verdict: string;
  real?: boolean;
}) {
  return (
    <li className="card flex flex-col gap-2">
      <div className="flex items-baseline justify-between gap-3">
        <span className="muted font-mono text-[11px] tracking-widest uppercase">Gate {n}</span>
        <span
          className={`badge border ${
            real
              ? "border-accent text-accent"
              : "muted border-[var(--border)]"
          }`}
        >
          {real ? "the guarantee" : "courtesy check"}
        </span>
      </div>
      <p className="font-mono text-xs leading-relaxed">{check}</p>
      <p className="muted text-sm">
        <span className="font-medium">On failure:</span> {onFail}
      </p>
      <p className="text-sm leading-relaxed">{verdict}</p>
    </li>
  );
}

export default function ConcurrencyNotesPage() {
  return (
    <article className="space-y-12">
      <header className="space-y-3">
        <p className="text-accent font-mono text-[11px] tracking-widest uppercase">
          engineering notes
        </p>
        <h1 className="text-2xl font-semibold">Concurrency notes</h1>
        <p className="muted text-sm leading-relaxed">
          Four seats, and two parents pressing the button in the same millisecond. These notes
          trace how Postgres decides who wins — and why the winner is settled before any card is
          charged.
        </p>
        <dl className="muted flex flex-wrap gap-x-5 gap-y-1 border-t pt-3 font-mono text-xs"
            style={{ borderColor: "var(--border)" }}>
          <div className="flex gap-1.5"><dt>capacity</dt><dd className="font-medium">4</dd></div>
          <div className="flex gap-1.5"><dt>hold</dt><dd className="font-medium">10 min</dd></div>
          <div className="flex gap-1.5"><dt>isolation</dt><dd className="font-medium">READ COMMITTED</dd></div>
          <div className="flex gap-1.5"><dt>retry loops</dt><dd className="font-medium">none</dd></div>
        </dl>
      </header>

      {/* ---------------- simulator ---------------- */}
      <section className="space-y-4">
        <div className="space-y-1.5">
          <p className="text-accent font-mono text-[11px] tracking-widest uppercase">
            I1 · claimed_seats may never exceed capacity
          </p>
          <h2 className="text-lg font-semibold">Two transactions, one seat left</h2>
        </div>

        <p className="text-sm leading-relaxed">
          Below are two designs that both &ldquo;check first, then increment&rdquo;. The only
          difference is whether the check and the write live inside one statement, or are
          separated by a round trip through Java. Step through each and watch the{" "}
          <span className="font-mono text-xs">live bookings</span> tally at the end.
        </p>

        <SeatRaceSimulator />

        <p className="text-sm leading-relaxed">
          The statement that makes the first design safe is{" "}
          <code className="font-mono text-xs">TrialClassRepository.tryClaimSeat</code>:
        </p>

        <pre
          className="overflow-x-auto rounded-md border border-l-2 p-3 font-mono text-xs leading-relaxed"
          style={{ background: "var(--card)", borderColor: "var(--border)", borderLeftColor: "var(--accent)" }}
        >{`UPDATE trial_classes
   SET claimed_seats = claimed_seats + 1
 WHERE id = ? AND claimed_seats < capacity`}</pre>

        <p className="text-sm leading-relaxed">
          Its return value — the number of rows changed — <em>is</em> the answer.{" "}
          <span className="font-mono text-xs">1</span> means the seat is yours,{" "}
          <span className="font-mono text-xs">0</span> means the class is full. No{" "}
          <code className="font-mono text-xs">SELECT FOR UPDATE</code>, no{" "}
          <code className="font-mono text-xs">SERIALIZABLE</code>, no retry loop — because there
          is no window between the check and the write left to patch.
        </p>
      </section>

      {/* ---------------- race sequence ---------------- */}
      <section className="space-y-4">
        <div className="space-y-1.5">
          <p className="muted font-mono text-[11px] tracking-widest uppercase">sequence</p>
          <h2 className="text-lg font-semibold">The same race, drawn out</h2>
        </div>
        <RaceSequenceFigure />
      </section>

      {/* ---------------- lifecycle ---------------- */}
      <section className="space-y-4">
        <div className="space-y-1.5">
          <p className="muted font-mono text-[11px] tracking-widest uppercase">booking status</p>
          <h2 className="text-lg font-semibold">Where a seat can go</h2>
        </div>
        <LifecycleFigure />
      </section>

      {/* ---------------- reaper ---------------- */}
      <section className="space-y-4">
        <div className="space-y-1.5">
          <p className="text-accent font-mono text-[11px] tracking-widest uppercase">
            I4 · the counter must equal the live bookings
          </p>
          <h2 className="text-lg font-semibold">When the reaper and a payment meet</h2>
        </div>
        <p className="text-sm leading-relaxed">
          <code className="font-mono text-xs">HoldReaper</code> runs every 30 seconds, releasing
          seats from holds that lapsed. Sooner or later it sweeps at the exact moment a parent is
          paying. This — and only this — is where the system reaches for an explicit row lock.
        </p>
        <ReaperFigure />
      </section>

      {/* ---------------- gates ---------------- */}
      <section className="space-y-4">
        <div className="space-y-1.5">
          <p className="text-accent font-mono text-[11px] tracking-widest uppercase">
            I2 · one live booking per child per class
          </p>
          <h2 className="text-lg font-semibold">Three gates on one request</h2>
        </div>
        <p className="text-sm leading-relaxed">
          Gates one and three defend the <em>same</em> invariant. That repetition is deliberate,
          and the difference between them is the whole lesson.
        </p>
        <ol className="space-y-3">
          <Gate
            n={1}
            check="bookings.findLive(studentId, classId).isPresent()"
            onFail="409 DUPLICATE_BOOKING"
            verdict="Friendly, and not a guarantee. Two simultaneous requests can both pass it, because reading and deciding are separated in time."
          />
          <Gate
            n={2}
            check="UPDATE trial_classes … WHERE claimed_seats < capacity"
            onFail="409 CLASS_FULL"
            verdict="Atomic. The seat is claimed at booking time, not at payment time, which is why the loser never reaches the gateway at all."
            real
          />
          <Gate
            n={3}
            check="uq_live_booking — partial unique index on (student_id, trial_class_id)"
            onFail="DuplicateKeyException → whole transaction rolls back"
            verdict="This is the actual guarantee. The rollback also undoes the seat claim from gate two, so there is no compensating code to write and none to forget."
            real
          />
        </ol>
      </section>

      {/* ---------------- summary ---------------- */}
      <section className="space-y-4">
        <div className="space-y-1.5">
          <p className="muted font-mono text-[11px] tracking-widest uppercase">summary</p>
          <h2 className="text-lg font-semibold">Which mechanism for which problem</h2>
        </div>

        <div className="overflow-x-auto rounded-lg border" style={{ borderColor: "var(--border)" }}>
          <table className="w-full min-w-[36rem] text-sm">
            <thead>
              <tr style={{ background: "var(--background)" }}>
                <th className="muted border-b px-4 py-2.5 text-left font-mono text-[10px] font-medium tracking-widest uppercase"
                    style={{ borderColor: "var(--border)" }}>
                  Mechanism
                </th>
                <th className="muted border-b px-4 py-2.5 text-left font-mono text-[10px] font-medium tracking-widest uppercase"
                    style={{ borderColor: "var(--border)" }}>
                  Protects
                </th>
                <th className="muted border-b px-4 py-2.5 text-left font-mono text-[10px] font-medium tracking-widest uppercase"
                    style={{ borderColor: "var(--border)" }}>
                  Remove it and
                </th>
              </tr>
            </thead>
            <tbody>
              {[
                ["conditional UPDATE", "I1", "seats never exceed capacity", "the last seat is oversold"],
                ["CHECK constraint", "I1", "second line of defence", "a bad refactor ships unnoticed"],
                ["partial unique index", "I2", "one live booking per child", "a child is enrolled twice"],
                ["SELECT … FOR UPDATE", "I4", "payment against the reaper", "a paid seat gets released"],
                ["FOR UPDATE SKIP LOCKED", "I4", "the reaper steps over busy rows", "a hold expires mid-payment"],
                ["UNIQUE idempotency_key", "", "one charge per request", "a refresh means a second bill"],
              ].map(([mech, inv, protects, removed]) => (
                <tr key={mech} className="border-b last:border-b-0" style={{ borderColor: "var(--border)" }}>
                  <td className="px-4 py-2.5 align-top font-mono text-xs">{mech}</td>
                  <td className="px-4 py-2.5 align-top">
                    {inv ? <span className="text-accent font-mono text-[11px] font-semibold">{inv} </span> : null}
                    {protects}
                  </td>
                  <td className="muted px-4 py-2.5 align-top">{removed}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <p className="text-sm leading-relaxed">
          Not one row above lives in application code. That is precisely the point: this system is
          correct because the database refuses the wrong states, not because the Java happened to
          run in the right order.
        </p>
      </section>

      <footer className="muted border-t pt-4 text-xs leading-relaxed"
              style={{ borderColor: "var(--border)" }}>
        Behaviour described here lives in <code className="font-mono">BookingService</code>,{" "}
        <code className="font-mono">PaymentService</code>,{" "}
        <code className="font-mono">HoldReaper</code> and{" "}
        <code className="font-mono">V1__schema.sql</code>. The simulator uses a class of capacity
        4 that is already 3 full.
      </footer>
    </article>
  );
}
