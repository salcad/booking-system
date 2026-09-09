import Link from "next/link";
import { ApiError, api } from "@/lib/api-server";
import { CheckoutPanel } from "@/components/CheckoutPanel";
import { ErrorNotice } from "@/components/ErrorNotice";
import { StatusBadge } from "@/components/StatusBadge";

export default async function CheckoutPage({
  params,
}: PageProps<"/checkout/[bookingId]">) {
  const { bookingId } = await params;

  let booking;
  try {
    booking = await api.booking(Number(bookingId));
  } catch (e) {
    const err = e as ApiError;
    return <ErrorNotice code={err.code} message={err.message} />;
  }

  // Reaching checkout for a booking that is no longer awaiting payment means
  // the flow was resumed from history or a stale tab.
  if (booking.status !== "PENDING_PAYMENT") {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold">Nothing left to pay</h1>
        <div className="card flex items-center gap-3">
          <StatusBadge status={booking.status} />
          <span className="muted text-sm">This booking is no longer awaiting payment.</span>
        </div>
        <Link href={`/bookings/${booking.id}`} className="btn-secondary">
          See booking status
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Complete your payment</h1>
        <p className="muted mt-1 text-sm">
          Your seat is already held. Paying converts the hold into a confirmed
          place on the roster.
        </p>
      </div>
      <CheckoutPanel booking={booking} />
    </div>
  );
}
