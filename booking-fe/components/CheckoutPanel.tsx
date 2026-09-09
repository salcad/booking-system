"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { ApiError, api, formatMoney } from "@/lib/api";
import { ErrorNotice } from "./ErrorNotice";
import { HoldCountdown } from "./HoldCountdown";
import type { ApiErrorCode, Booking } from "@/lib/types";

export function CheckoutPanel({ booking }: { booking: Booking }) {
  const router = useRouter();

  /**
   * Generated once when the panel mounts and reused for every attempt on this
   * booking, so a double-click - or a retry after a flaky response - cannot
   * produce a second charge. The backend enforces this with a unique index;
   * this is the client half of the same contract.
   */
  const [idempotencyKey] = useState(() => crypto.randomUUID());

  const [pending, setPending] = useState(false);
  const [error, setError] = useState<{ code?: ApiErrorCode; message: string } | null>(
    null,
  );

  async function pay(outcome: "SUCCESS" | "FAILURE") {
    setPending(true);
    setError(null);
    try {
      await api.pay(booking.id, outcome, idempotencyKey);
      router.push(`/bookings/${booking.id}`);
    } catch (e) {
      const err = e as ApiError;
      setError({ code: err.code, message: err.message });
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="card space-y-4">
      <div className="flex items-center justify-between">
        <span className="text-sm">Trial class</span>
        <span className="font-medium">{formatMoney(booking.amountCents)}</span>
      </div>

      {booking.holdExpiresAt && <HoldCountdown expiresAt={booking.holdExpiresAt} />}

      {error && <ErrorNotice code={error.code} message={error.message} />}

      <div className="flex flex-wrap gap-2">
        <button
          className="btn-primary"
          onClick={() => pay("SUCCESS")}
          disabled={pending}
        >
          {pending ? "Processing..." : `Pay ${formatMoney(booking.amountCents)}`}
        </button>
        <button
          className="btn-danger"
          onClick={() => pay("FAILURE")}
          disabled={pending}
        >
          Pay with a declining card
        </button>
      </div>

      <p className="muted text-xs">
        The second button drives the mock gateway to decline, so the failure
        path is reachable without editing code. A declined payment releases the
        seat and leaves the child off the roster.
      </p>
    </div>
  );
}
