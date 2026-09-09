"use client";

import { useEffect, useState } from "react";

/**
 * Makes the seat-hold model legible: the parent can see that the seat is
 * theirs, and for how long. When it reaches zero the reaper may release the
 * seat at any moment, so the copy stops promising it.
 */
export function HoldCountdown({ expiresAt }: { expiresAt: string }) {
  const target = new Date(expiresAt).getTime();
  const [remainingMs, setRemainingMs] = useState(() => target - Date.now());

  useEffect(() => {
    const id = setInterval(() => setRemainingMs(target - Date.now()), 1000);
    return () => clearInterval(id);
  }, [target]);

  if (remainingMs <= 0) {
    return (
      <span className="badge bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300">
        Hold expired - the seat may have been released
      </span>
    );
  }

  const totalSeconds = Math.floor(remainingMs / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;

  return (
    <span className="badge bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300">
      Seat held for {minutes}:{String(seconds).padStart(2, "0")}
    </span>
  );
}
