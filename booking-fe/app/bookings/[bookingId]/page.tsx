import Link from "next/link";
import { ApiError, api, formatMoney } from "@/lib/api-server";
import { ErrorNotice } from "@/components/ErrorNotice";
import { StatusBadge } from "@/components/StatusBadge";
import type { BookingStatus } from "@/lib/types";

/** What each status means for the parent, in their terms rather than ours. */
const EXPLANATION: Record<BookingStatus, string> = {
  CONFIRMED: "Your child is on the roster. Nothing else to do.",
  PENDING_PAYMENT:
    "The seat is held but not yet paid for. It is released if payment does not complete in time.",
  PAYMENT_FAILED:
    "The payment was declined, so the seat was released. You can book again.",
  CANCELLED: "This booking was cancelled and the seat returned to the pool.",
  EXPIRED:
    "The hold ran out before payment completed, so the seat went back to the pool.",
};

export default async function BookingStatusPage({
  params,
}: PageProps<"/bookings/[bookingId]">) {
  const { bookingId } = await params;

  let booking;
  try {
    booking = await api.booking(Number(bookingId));
  } catch (e) {
    const err = e as ApiError;
    return <ErrorNotice code={err.code} message={err.message} />;
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Booking #{booking.id}</h1>
        <div className="mt-2 flex items-center gap-3">
          <StatusBadge status={booking.status} />
          <span className="muted text-sm">{formatMoney(booking.amountCents)}</span>
        </div>
        <p className="mt-3 text-sm">{EXPLANATION[booking.status]}</p>
      </div>

      {booking.status === "PENDING_PAYMENT" && (
        <Link href={`/checkout/${booking.id}`} className="btn-primary">
          Continue to payment
        </Link>
      )}

      <section className="space-y-3">
        <div>
          <h2 className="font-medium">History</h2>
          <p className="muted text-sm">
            Written from <code className="font-mono text-xs">booking_events</code>, one row
            per transition, in the same transaction as the change itself.
          </p>
        </div>
        <ol className="space-y-2">
          {booking.history.map((event, i) => (
            <li key={i} className="card flex items-start justify-between gap-4 text-sm">
              <div>
                <span className="font-mono text-xs">
                  {event.fromStatus ?? "new"} &rarr; {event.toStatus}
                </span>
                {event.reason && <p className="muted mt-1">{event.reason}</p>}
              </div>
              <div className="muted shrink-0 text-right text-xs">
                <div>{event.actor}</div>
                <div>{new Date(event.at).toLocaleTimeString()}</div>
              </div>
            </li>
          ))}
        </ol>
      </section>

      <Link href="/classes" className="muted text-sm hover:underline">
        &larr; Back to classes
      </Link>
    </div>
  );
}
