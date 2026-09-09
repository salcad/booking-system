"use client";

import { useState, type ReactNode } from "react";

/*
 * Two rival designs for the same seat claim, stepped through side by side.
 * The point of the naive lane is that it looks correct at every individual
 * step - the defect only becomes visible in the final tallies, which is
 * exactly why lost updates survive code review.
 */

const CAPACITY = 4;

type Lane = "t1" | "t2";
type Phase = "waiting" | "running" | "blocked" | "committed" | "rejected";

type DbState = {
  seats: number;
  lock: Lane | null;
  bookings: number;
  t1: Phase;
  t2: Phase;
};

type Step = {
  lane: Lane;
  sql: string;
  narr: ReactNode;
  mark?: "ok" | "bad";
  state: DbState;
};

type Scenario = {
  intro: ReactNode;
  steps: Step[];
  verdict: { safe: boolean; label: string; text: string };
};

const START: DbState = { seats: 3, lock: null, bookings: 3, t1: "waiting", t2: "waiting" };

function db(
  seats: number,
  lock: Lane | null,
  bookings: number,
  t1: Phase,
  t2: Phase,
): DbState {
  return { seats, lock, bookings, t1, t2 };
}

const Hot = ({ children }: { children: ReactNode }) => (
  <em className="text-accent font-medium not-italic">{children}</em>
);

const ATOMIC: Scenario = {
  intro:
    "The class is 3 of 4 full. Two parents open checkout at the same instant, both after the same seat.",
  steps: [
    {
      lane: "t1",
      sql: "BEGIN",
      narr: "T1 opens a transaction.",
      state: db(3, null, 3, "running", "waiting"),
    },
    {
      lane: "t2",
      sql: "BEGIN",
      narr: "T2 opens one too. Nothing separates them yet.",
      state: db(3, null, 3, "running", "running"),
    },
    {
      lane: "t1",
      sql: "UPDATE trial_classes SET claimed_seats = claimed_seats + 1 WHERE id = 7 AND claimed_seats < capacity",
      mark: "ok",
      narr: (
        <>
          One row changed. Postgres <Hot>locks that class row</Hot>, and the new value is not
          yet visible to anyone else.
        </>
      ),
      state: db(4, "t1", 3, "running", "running"),
    },
    {
      lane: "t2",
      sql: "UPDATE trial_classes SET claimed_seats = claimed_seats + 1 WHERE id = 7 AND claimed_seats < capacity",
      narr: (
        <>
          T2 runs the identical statement and meets a locked row. It <Hot>waits</Hot> — it does
          not fail, and it does not slip past.
        </>
      ),
      state: db(4, "t1", 3, "running", "blocked"),
    },
    {
      lane: "t1",
      sql: "INSERT INTO bookings (...) VALUES (..., 'PENDING_PAYMENT')",
      mark: "ok",
      narr: "T1 inserts its booking, still holding both the seat and the row.",
      state: db(4, "t1", 4, "running", "blocked"),
    },
    {
      lane: "t1",
      sql: "COMMIT",
      mark: "ok",
      narr: "T1 commits. The lock lifts and claimed_seats = 4 becomes visible to everyone.",
      state: db(4, null, 4, "committed", "blocked"),
    },
    {
      lane: "t2",
      sql: "-- lock released, WHERE re-evaluated --> 0 rows",
      mark: "bad",
      narr: (
        <>
          Here is the whole trick. T2 <Hot>does not reuse the 3 it saw earlier</Hot>. It re-reads
          the row and re-evaluates the WHERE clause against the freshly committed value: 4 &lt; 4
          is false, so zero rows change.
        </>
      ),
      state: db(4, null, 4, "committed", "running"),
    },
    {
      lane: "t2",
      sql: "ROLLBACK  -- 409 CLASS_FULL",
      mark: "bad",
      narr: "T2 is turned away before the payment gateway is ever reached. Parent B sees “class full”, not a charge that needs refunding.",
      state: db(4, null, 4, "committed", "rejected"),
    },
  ],
  verdict: {
    safe: true,
    label: "safe",
    text: "4 seats taken, 4 live bookings, and not one card charged. The race was settled before anybody reached the payment step.",
  },
};

const NAIVE: Scenario = {
  intro:
    "The same intent, but the check and the write are split: SELECT back into Java, decide there, then UPDATE. Watch the gap.",
  steps: [
    {
      lane: "t1",
      sql: "BEGIN",
      narr: "T1 opens a transaction.",
      state: db(3, null, 3, "running", "waiting"),
    },
    {
      lane: "t2",
      sql: "BEGIN",
      narr: "T2 opens a transaction.",
      state: db(3, null, 3, "running", "running"),
    },
    {
      lane: "t1",
      sql: "SELECT claimed_seats FROM trial_classes WHERE id = 7  --> 3",
      narr: (
        <>
          T1 reads 3. A plain SELECT <Hot>locks nothing at all</Hot>.
        </>
      ),
      state: db(3, null, 3, "running", "running"),
    },
    {
      lane: "t2",
      sql: "SELECT claimed_seats FROM trial_classes WHERE id = 7  --> 3",
      narr: "T2 reads 3 as well. Both transactions now hold the same number, and neither knows the other exists.",
      state: db(3, null, 3, "running", "running"),
    },
    {
      lane: "t1",
      sql: "// Java:  if (3 < 4) { ... }  --> passes",
      narr: "T1 decides there is room. Its decision is correct — at that moment.",
      state: db(3, null, 3, "running", "running"),
    },
    {
      lane: "t2",
      sql: "// Java:  if (3 < 4) { ... }  --> passes",
      narr: (
        <>
          T2 decides the same. <Hot>Both pass.</Hot> The gap between reading and writing is open,
          and nothing will close it.
        </>
      ),
      state: db(3, null, 3, "running", "running"),
    },
    {
      lane: "t1",
      sql: "UPDATE trial_classes SET claimed_seats = 4 WHERE id = 7;  INSERT booking;  COMMIT",
      mark: "ok",
      narr: "T1 writes 4 and commits. So far everything still looks right.",
      state: db(4, null, 4, "committed", "running"),
    },
    {
      lane: "t2",
      sql: "UPDATE trial_classes SET claimed_seats = 4 WHERE id = 7;  INSERT booking;  COMMIT",
      mark: "bad",
      narr: (
        <>
          T2 writes 4 too — a number it computed from a reading that went stale. T1&rsquo;s write
          is <Hot>overwritten without a trace</Hot>, and a fifth booking lands anyway.
        </>
      ),
      state: db(4, null, 5, "committed", "committed"),
    },
  ],
  verdict: {
    safe: false,
    label: "overbooked",
    text: "The counter stops at 4 while five bookings are live. The CHECK constraint stays quiet, because 4 <= 4 is perfectly legal. The class is one child over, and both parents will sail through checkout.",
  },
};

const PHASE_LABEL: Record<Phase, string> = {
  waiting: "waiting",
  running: "running",
  blocked: "blocked",
  committed: "committed",
  rejected: "rejected",
};

function phaseClass(phase: Phase) {
  switch (phase) {
    case "running":
      return "border-emerald-600 text-emerald-700 dark:border-emerald-500 dark:text-emerald-400";
    case "blocked":
      return "border-accent text-accent";
    case "committed":
      return "border-emerald-600 bg-emerald-50 text-emerald-800 dark:border-emerald-500 dark:bg-emerald-950 dark:text-emerald-300";
    case "rejected":
      return "border-red-400 bg-red-50 text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-400";
    default:
      return "muted";
  }
}

function Stat({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1 px-4 py-3">
      <span className="muted font-mono text-[10px] tracking-widest uppercase">{label}</span>
      {children}
    </div>
  );
}

function Lane({
  name,
  phase,
  steps,
  currentIndex,
}: {
  name: string;
  phase: Phase;
  steps: { sql: string; mark?: "ok" | "bad"; index: number }[];
  currentIndex: number;
}) {
  return (
    <div className="flex min-h-[13rem] flex-col gap-3 p-4">
      <div className="flex items-center justify-between gap-2">
        <span className="font-mono text-xs font-semibold">{name}</span>
        <span className={`badge border ${phaseClass(phase)}`}>{PHASE_LABEL[phase]}</span>
      </div>

      {steps.length === 0 ? (
        <p className="muted text-sm italic">Nothing run yet.</p>
      ) : (
        <ol className="flex flex-col gap-1.5">
          {steps.map((s) => {
            const now = s.index === currentIndex;
            const accent = now
              ? "border-l-accent text-accent"
              : s.mark === "bad"
                ? "border-l-red-400 text-red-700 dark:border-l-red-800 dark:text-red-400"
                : s.mark === "ok"
                  ? "border-l-emerald-600 muted dark:border-l-emerald-500"
                  : "muted";
            return (
              <li
                key={s.index}
                className={`rounded-r border-l-2 px-2.5 py-1.5 font-mono text-[11px] leading-relaxed break-words ${accent}`}
                style={{ background: "var(--background)" }}
              >
                {s.sql}
              </li>
            );
          })}
        </ol>
      )}
    </div>
  );
}

export function SeatRaceSimulator() {
  const [naive, setNaive] = useState(false);
  const [cursor, setCursor] = useState(0);

  const scenario = naive ? NAIVE : ATOMIC;
  const total = scenario.steps.length;
  const state = cursor === 0 ? START : scenario.steps[cursor - 1].state;
  const finished = cursor === total;
  const broken = state.bookings > state.seats;

  function pick(useNaive: boolean) {
    setNaive(useNaive);
    setCursor(0);
  }

  const laneSteps = (lane: Lane) =>
    scenario.steps
      .slice(0, cursor)
      .map((s, i) => ({ ...s, index: i + 1 }))
      .filter((s) => s.lane === lane);

  return (
    <div className="card space-y-0 overflow-hidden p-0">
      <div
        className="flex flex-wrap items-center justify-between gap-3 border-b px-4 py-3"
        style={{ borderColor: "var(--border)", background: "var(--background)" }}
      >
        <div className="flex" role="group" aria-label="Choose a design">
          <button
            type="button"
            aria-pressed={!naive}
            onClick={() => pick(false)}
            className={`rounded-l-md border px-3 py-1.5 text-xs font-medium ${
              !naive ? "bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900" : "muted"
            }`}
            style={{ borderColor: "var(--border)" }}
          >
            Atomic conditional UPDATE
          </button>
          <button
            type="button"
            aria-pressed={naive}
            onClick={() => pick(true)}
            className={`rounded-r-md border border-l-0 px-3 py-1.5 text-xs font-medium ${
              naive ? "bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900" : "muted"
            }`}
            style={{ borderColor: "var(--border)" }}
          >
            Read-then-write
          </button>
        </div>

        <div className="flex gap-2">
          <button type="button" className="btn-secondary !px-3 !py-1.5 !text-xs" onClick={() => setCursor(0)}>
            Restart
          </button>
          <button
            type="button"
            className="btn-primary !px-3 !py-1.5 !text-xs"
            disabled={finished}
            onClick={() => setCursor((c) => Math.min(c + 1, total))}
          >
            {finished ? "Done" : "Next step"}
          </button>
        </div>
      </div>

      <div
        className="grid grid-cols-2 border-b sm:grid-cols-4 [&>*+*]:border-l"
        style={{ borderColor: "var(--border)" }}
      >
        <Stat label="claimed_seats">
          <span
            className={`font-mono text-2xl font-semibold tabular-nums ${state.lock ? "text-accent" : ""}`}
          >
            {state.seats}
            <span className="muted text-base font-normal"> / {CAPACITY}</span>
          </span>
        </Stat>
        <Stat label="row lock">
          <span
            className={`badge self-start font-mono ${
              state.lock ? "text-accent border-accent border" : "muted border"
            }`}
            style={state.lock ? undefined : { borderColor: "var(--border)" }}
          >
            {state.lock ? `held by ${state.lock.toUpperCase()}` : "free"}
          </span>
        </Stat>
        <Stat label="live bookings">
          <span
            className={`font-mono text-2xl font-semibold tabular-nums ${
              broken ? "text-red-600 dark:text-red-400" : ""
            }`}
          >
            {state.bookings}
          </span>
        </Stat>
        <Stat label="invariant I4">
          <span
            className={`text-base font-semibold ${broken ? "text-red-600 dark:text-red-400" : ""}`}
          >
            {broken ? "BROKEN" : "holds"}
          </span>
        </Stat>
      </div>

      <div
        className="grid grid-cols-1 border-b sm:grid-cols-2 sm:[&>*+*]:border-l"
        style={{ borderColor: "var(--border)" }}
      >
        <Lane name="T1 · Parent A" phase={state.t1} steps={laneSteps("t1")} currentIndex={cursor} />
        <div className="border-t sm:border-t-0" style={{ borderColor: "var(--border)" }}>
          <Lane name="T2 · Parent B" phase={state.t2} steps={laneSteps("t2")} currentIndex={cursor} />
        </div>
      </div>

      <div className="flex min-h-[4.5rem] items-start gap-3 px-4 py-3">
        <span
          className="muted shrink-0 rounded border px-2 py-0.5 font-mono text-[11px] font-semibold tabular-nums"
          style={{ borderColor: "var(--border)" }}
        >
          {cursor} / {total}
        </span>
        <p className="text-sm leading-relaxed" aria-live="polite">
          {cursor === 0 ? scenario.intro : scenario.steps[cursor - 1].narr}
        </p>
      </div>

      {finished && (
        <div
          className={`flex flex-wrap items-baseline gap-2 border-t px-4 py-3 text-sm font-medium ${
            scenario.verdict.safe
              ? "bg-emerald-50 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300"
              : "bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-300"
          }`}
          style={{ borderColor: "var(--border)" }}
        >
          <span className="shrink-0 font-mono text-[11px] tracking-widest uppercase">
            {scenario.verdict.label}
          </span>
          <span>{scenario.verdict.text}</span>
        </div>
      )}
    </div>
  );
}
