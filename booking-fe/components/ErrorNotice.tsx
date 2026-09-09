import type { ApiErrorCode } from "@/lib/types";

/**
 * Renders a backend refusal. The two 409s are the visible proof of the
 * capacity and duplicate invariants, so they are phrased for a parent rather
 * than echoing the API's wording.
 */
const FRIENDLY: Partial<Record<ApiErrorCode, string>> = {
  CLASS_FULL: "This class just filled up. Someone else took the last seat.",
  DUPLICATE_BOOKING: "This child is already booked into this class.",
  SEAT_UNAVAILABLE:
    "This seat was taken while you were checking out. You have not been charged.",
};

export function ErrorNotice({
  code,
  message,
}: {
  code?: ApiErrorCode;
  message: string;
}) {
  return (
    <div
      role="alert"
      className="rounded-md border border-red-300 bg-red-50 p-3 text-sm
                 text-red-800 dark:border-red-900 dark:bg-red-950/50 dark:text-red-300"
    >
      <p>{(code && FRIENDLY[code]) ?? message}</p>
      {code && (
        <p className="mt-1 font-mono text-xs opacity-70">
          {code} - rejected by the backend
        </p>
      )}
    </div>
  );
}
