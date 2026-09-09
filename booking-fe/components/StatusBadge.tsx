import type { BookingStatus } from "@/lib/types";

/**
 * One colour per status. PENDING_PAYMENT is amber rather than green on
 * purpose: a held seat is not a booked seat, and the UI should never suggest
 * otherwise.
 */
const STYLES: Record<BookingStatus, string> = {
  CONFIRMED: "bg-green-100 text-green-800 dark:bg-green-950 dark:text-green-300",
  PENDING_PAYMENT: "bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300",
  PAYMENT_FAILED: "bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300",
  CANCELLED: "bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300",
  EXPIRED: "bg-zinc-200 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300",
};

const LABELS: Record<BookingStatus, string> = {
  CONFIRMED: "Confirmed",
  PENDING_PAYMENT: "Awaiting payment",
  PAYMENT_FAILED: "Payment failed",
  CANCELLED: "Cancelled",
  EXPIRED: "Hold expired",
};

export function StatusBadge({ status }: { status: BookingStatus }) {
  return <span className={`badge ${STYLES[status]}`}>{LABELS[status]}</span>;
}
