import Link from "next/link";
import { ApiError, api, formatClassTime } from "@/lib/api-server";
import { ErrorNotice } from "@/components/ErrorNotice";
import { AutoRefresh } from "@/components/AutoRefresh";
import type { RosterEntry } from "@/lib/types";

function EntryList({ entries }: { entries: RosterEntry[] }) {
  return (
    <ul className="space-y-2">
      {entries.map((e) => (
        <li key={e.bookingId} className="card flex items-center justify-between text-sm">
          <span>{e.studentName}</span>
          <span className="muted text-xs">
            {e.grade} &middot; booking #{e.bookingId}
          </span>
        </li>
      ))}
    </ul>
  );
}

export default async function RosterPage({
  params,
}: PageProps<"/admin/roster/[classId]">) {
  const { classId } = await params;

  let roster;
  try {
    roster = await api.roster(Number(classId));
  } catch (e) {
    const err = e as ApiError;
    return <ErrorNotice code={err.code} message={err.message} />;
  }

  const { trialClass, confirmed, pendingHolds } = roster;

  return (
    <div className="space-y-6">
      <AutoRefresh />

      <div>
        <Link href="/classes" className="muted text-sm hover:underline">
          &larr; All classes
        </Link>
        <h1 className="mt-2 text-2xl font-semibold">{trialClass.subject}</h1>
        <p className="muted mt-1 text-sm">{formatClassTime(trialClass.startsAt)}</p>
        <p className="mt-2 text-sm">
          <strong>
            {confirmed.length} / {trialClass.capacity} confirmed
          </strong>{" "}
          <span className="muted">
            ({trialClass.claimedSeats} of {trialClass.capacity} seats claimed,
            counting unpaid holds)
          </span>
        </p>
      </div>

      <section className="space-y-3">
        <h2 className="font-medium">Roster</h2>
        {confirmed.length === 0 ? (
          <p className="muted text-sm">No confirmed students yet.</p>
        ) : (
          <EntryList entries={confirmed} />
        )}
      </section>

      <section className="space-y-3">
        <div>
          <h2 className="font-medium">Held, not paid</h2>
          <p className="muted text-sm">
            These children are <strong>not on the roster</strong>. They hold a seat
            while checkout is in progress and drop off if payment does not complete.
          </p>
        </div>
        {pendingHolds.length === 0 ? (
          <p className="muted text-sm">No holds in progress.</p>
        ) : (
          <EntryList entries={pendingHolds} />
        )}
      </section>
    </div>
  );
}
