import Link from "next/link";
import { SeatBadge } from "./SeatBadge";
import { formatClassTime } from "@/lib/api";
import type { TrialClass } from "@/lib/types";

export function ClassCard({ trialClass }: { trialClass: TrialClass }) {
  const { id, subject, startsAt, capacity, seatsRemaining, full } = trialClass;

  return (
    <div className="card flex items-center justify-between gap-4">
      <div className="min-w-0">
        <h2 className="font-medium">{subject}</h2>
        <p className="muted mt-0.5 text-sm">{formatClassTime(startsAt)}</p>
        <div className="mt-2">
          <SeatBadge seatsRemaining={seatsRemaining} capacity={capacity} />
        </div>
      </div>

      <div className="flex shrink-0 flex-col items-end gap-2">
        {full ? (
          /*
           * Advisory only. Hiding the button is a courtesy; the backend still
           * answers 409 CLASS_FULL if a request arrives anyway.
           */
          <span className="btn-secondary cursor-not-allowed opacity-50">Full</span>
        ) : (
          <Link href={`/book/${id}`} className="btn-primary">
            Book
          </Link>
        )}
        <Link
          href={`/admin/roster/${id}`}
          className="muted text-xs hover:underline"
        >
          View roster
        </Link>
      </div>
    </div>
  );
}
