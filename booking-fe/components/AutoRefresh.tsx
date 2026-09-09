"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";

/**
 * Re-runs the server component on an interval so a seat disappearing during
 * the demo is visible without a manual refresh.
 *
 * This is presentation only. The list it refreshes is advisory: the backend
 * re-checks capacity on every booking regardless of what the browser last saw.
 */
export function AutoRefresh({ intervalMs = 5000 }: { intervalMs?: number }) {
  const router = useRouter();

  useEffect(() => {
    const id = setInterval(() => router.refresh(), intervalMs);
    return () => clearInterval(id);
  }, [router, intervalMs]);

  return null;
}
