"use client";

import { useEffect, useRef, useState, useTransition } from "react";
import { rebuildDemoData, type RebuildResult } from "@/app/actions";

/**
 * Rebuilding is destructive and one click away from the landing page, so it
 * goes through a modal confirmation. A native <dialog> gives the focus trap,
 * Escape-to-close and inert background for free.
 */
export function RebuildDemoButton() {
  const dialog = useRef<HTMLDialogElement>(null);
  const [pending, startTransition] = useTransition();
  const [result, setResult] = useState<RebuildResult | null>(null);

  // showModal() cannot be called during render, and the open attribute alone
  // renders a non-modal dialog, so the element is driven from an effect.
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const el = dialog.current;
    if (!el) return;
    if (open && !el.open) el.showModal();
    if (!open && el.open) el.close();
  }, [open]);

  function confirm() {
    startTransition(async () => {
      const r = await rebuildDemoData();
      setResult(r);
      if (r.ok) setOpen(false);
    });
  }

  return (
    <div className="space-y-3">
      <button
        type="button"
        className="btn-danger"
        onClick={() => {
          setResult(null);
          setOpen(true);
        }}
      >
        Rebuild demo data
      </button>

      {result?.ok && (
        <p className="text-sm text-green-700 dark:text-green-400">
          Demo data rebuilt: {result.report.trialClasses} classes,{" "}
          {result.report.students} students, {result.report.bookings} bookings.
        </p>
      )}

      <dialog
        ref={dialog}
        className="dialog"
        aria-labelledby="rebuildTitle"
        // Escape and backdrop dismissal bypass the buttons, so state is synced
        // from the element's own close event rather than assumed.
        onClose={() => setOpen(false)}
        onCancel={(e) => {
          if (pending) e.preventDefault();
        }}
      >
        <div className="space-y-4">
          <h2 id="rebuildTitle" className="text-lg font-semibold">
            Rebuild demo data?
          </h2>
          <p className="muted text-sm">
            This deletes every booking, payment and student currently in the
            database - including anything you created during this session - and
            reinstates the original seed fixtures with fresh class times. It
            cannot be undone.
          </p>

          {result && !result.ok && (
            <p className="text-sm text-red-700 dark:text-red-400">
              {result.message}
            </p>
          )}

          <div className="flex justify-end gap-2">
            <button
              type="button"
              className="btn-secondary"
              onClick={() => setOpen(false)}
              disabled={pending}
            >
              Cancel
            </button>
            <button
              type="button"
              className="btn-danger"
              onClick={confirm}
              disabled={pending}
            >
              {pending ? "Rebuilding..." : "Yes, rebuild"}
            </button>
          </div>
        </div>
      </dialog>
    </div>
  );
}
