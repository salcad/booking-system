"use client";

import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { ErrorNotice } from "./ErrorNotice";
import type { ApiErrorCode, RaceReport, TrialClass } from "@/lib/types";

export function RacePanel({ classes }: { classes: TrialClass[] }) {
  const [trialClassId, setTrialClassId] = useState(classes[0]?.id ?? 0);
  const [contenders, setContenders] = useState(8);
  const [running, setRunning] = useState(false);
  const [report, setReport] = useState<RaceReport | null>(null);
  const [error, setError] = useState<{ code?: ApiErrorCode; message: string } | null>(
    null,
  );

  async function fire() {
    setRunning(true);
    setError(null);
    setReport(null);
    try {
      setReport(await api.race(trialClassId, contenders));
    } catch (e) {
      const err = e as ApiError;
      setError({ code: err.code, message: err.message });
    } finally {
      setRunning(false);
    }
  }

  return (
    <div className="space-y-6">
      <div className="card space-y-4">
        <div>
          <label htmlFor="raceClass" className="label">
            Class
          </label>
          <select
            id="raceClass"
            className="select"
            value={trialClassId}
            onChange={(e) => setTrialClassId(Number(e.target.value))}
            disabled={running}
          >
            {classes.map((c) => (
              <option key={c.id} value={c.id}>
                {c.subject} - {c.seatsRemaining} of {c.capacity} seats left
              </option>
            ))}
          </select>
        </div>

        <div>
          <label htmlFor="contenders" className="label">
            Simultaneous parents
          </label>
          <input
            id="contenders"
            type="number"
            min={2}
            max={32}
            className="select"
            value={contenders}
            onChange={(e) => setContenders(Number(e.target.value))}
            disabled={running}
          />
        </div>

        {error && <ErrorNotice code={error.code} message={error.message} />}

        <button className="btn-primary" onClick={fire} disabled={running}>
          {running ? "Racing..." : "Fire concurrent bookings"}
        </button>
      </div>

      {report && <RaceResult report={report} />}
    </div>
  );
}

function RaceResult({ report }: { report: RaceReport }) {
  return (
    <div className="space-y-4">
      <div
        className={
          report.invariantHolds
            ? "rounded-md border border-green-300 bg-green-50 p-3 text-sm text-green-900 dark:border-green-900 dark:bg-green-950/50 dark:text-green-300"
            : "rounded-md border border-red-300 bg-red-50 p-3 text-sm text-red-900 dark:border-red-900 dark:bg-red-950/50 dark:text-red-300"
        }
      >
        <p className="font-medium">
          {report.invariantHolds ? "Invariant held" : "INVARIANT VIOLATED"}
        </p>
        <p className="mt-1">{report.verdict}</p>
      </div>

      <div className="grid grid-cols-3 gap-3 text-sm">
        <div className="card">
          <div className="muted text-xs">Seats before</div>
          <div className="text-lg font-semibold">{report.seatsBefore}</div>
        </div>
        <div className="card">
          <div className="muted text-xs">Confirmed</div>
          <div className="text-lg font-semibold">{report.confirmed}</div>
        </div>
        <div className="card">
          <div className="muted text-xs">Seats after</div>
          <div className="text-lg font-semibold">{report.seatsAfter}</div>
        </div>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead className="muted text-xs">
            <tr>
              <th className="py-2 pr-4">#</th>
              <th className="py-2 pr-4">Got as far as</th>
              <th className="py-2 pr-4">Outcome</th>
              <th className="py-2">Charged?</th>
            </tr>
          </thead>
          <tbody>
            {report.attempts.map((a) => (
              <tr key={a.index} className="border-t" style={{ borderColor: "var(--border)" }}>
                <td className="py-2 pr-4 font-mono text-xs">{a.index}</td>
                <td className="py-2 pr-4 font-mono text-xs">{a.phase}</td>
                <td className="py-2 pr-4">
                  <span
                    className={`badge ${
                      a.outcome === "CONFIRMED"
                        ? "bg-green-100 text-green-800 dark:bg-green-950 dark:text-green-300"
                        : "bg-zinc-200 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300"
                    }`}
                  >
                    {a.outcome}
                  </span>
                </td>
                {/* The point of the whole design: losers never reach payment. */}
                <td className="py-2 muted text-xs">
                  {a.phase === "REJECTED_AT_BOOKING" ? "no" : "yes"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
