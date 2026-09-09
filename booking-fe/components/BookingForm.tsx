"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError, api, formatMoney } from "@/lib/api";
import { ErrorNotice } from "./ErrorNotice";
import type { ApiErrorCode, Student } from "@/lib/types";

/**
 * Creating the booking is what claims the seat, so this form is the moment the
 * race is decided. Both refusals it can receive - CLASS_FULL and
 * DUPLICATE_BOOKING - are rendered rather than swallowed: they are the visible
 * evidence of the capacity and duplicate invariants.
 *
 * The two are not held the same way, because they are not the same kind of
 * fact. CLASS_FULL is about the class and can stop being true while the parent
 * sits here (a hold expires, a seat comes back), so it is transient state
 * cleared on the next attempt. A duplicate is about one child, cannot resolve
 * itself from this screen, and - unlike a full class - is knowable before the
 * parent clicks: the page asks for it with the student list. So it is held per
 * child, seeded from the server, and it disables rather than reports.
 */
export function BookingForm({
  trialClassId,
  students,
  priceCents,
}: {
  trialClassId: number;
  students: Student[];
  priceCents: number;
}) {
  const router = useRouter();

  // Children the server already knows are booked into this class. Held as a
  // set rather than copied into state so a router.refresh() after a booking
  // updates it; state seeded once from props would go stale on the same screen.
  const alreadyBooked = useMemo(
    () => new Set(students.filter((s) => s.existingBooking).map((s) => s.id)),
    [students],
  );

  const [studentId, setStudentId] = useState<number>(
    // Opening on a child who cannot book would hand the parent a disabled form.
    () => (students.find((s) => !s.existingBooking) ?? students[0])?.id ?? 0,
  );
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<{ code?: ApiErrorCode; message: string } | null>(
    null,
  );
  // Children the *backend* refused as duplicates during this visit. The server
  // list is a read taken before the form was rendered, so it cannot see a
  // booking made since - in another tab, say. This closes that window.
  const [refused, setRefused] = useState<number[]>([]);

  const isDuplicate = alreadyBooked.has(studentId) || refused.includes(studentId);
  const selected = students.find((s) => s.id === studentId);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (isDuplicate) return;
    setSubmitting(true);
    setError(null);
    try {
      const booking = await api.createBooking(studentId, trialClassId);
      router.push(`/checkout/${booking.id}`);
    } catch (err) {
      const api = err as ApiError;
      if (api.code === "DUPLICATE_BOOKING") {
        // Pinned to this child rather than to the form, so switching children
        // does not carry the refusal across to one who can still book.
        setRefused((prev) => [...prev, studentId]);
      } else {
        setError({ code: api.code, message: api.message });
      }
      // The seat count on the previous screen is now known to be stale.
      router.refresh();
    } finally {
      setSubmitting(false);
    }
  }

  if (students.length === 0) {
    return (
      <div className="card text-sm">
        This parent has no children in the seed data.
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} className="card space-y-4">
      <div>
        <label htmlFor="studentId" className="label">
          Which child?
        </label>
        <select
          id="studentId"
          className="select"
          value={studentId}
          onChange={(e) => {
            setStudentId(Number(e.target.value));
            // The previous attempt's refusal was about the previous child.
            setError(null);
          }}
          disabled={submitting}
        >
          {students.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name} ({s.grade})
              {alreadyBooked.has(s.id) || refused.includes(s.id)
                ? " - already booked"
                : ""}
            </option>
          ))}
        </select>
      </div>

      {/*
        A duplicate is a state of the form, not a failed request, so it is not
        dressed as an error. The red "rejected by the backend" notice stays for
        things that actually were.
      */}
      {isDuplicate ? (
        <p
          role="status"
          className="rounded-md border border-neutral-300 bg-neutral-50 p-3 text-sm
                     dark:border-neutral-700 dark:bg-neutral-900"
        >
          {selected?.name ?? "This child"} already has a booking for this class.
          Pick another child, or cancel the existing booking first.
        </p>
      ) : (
        error && <ErrorNotice code={error.code} message={error.message} />
      )}

      <div className="flex items-center justify-between">
        <span className="muted text-sm">{formatMoney(priceCents)} per trial</span>
        <button
          type="submit"
          className="btn-primary"
          disabled={submitting || isDuplicate}
        >
          {submitting ? "Holding a seat..." : "Hold my seat"}
        </button>
      </div>

      <p className="muted text-xs">
        Confirming holds the seat for 10 minutes while you pay. The seat is
        taken out of the pool immediately - that is what stops two parents
        paying for the same one.
      </p>
    </form>
  );
}
