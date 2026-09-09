"use server";

import { cookies } from "next/headers";
import { revalidatePath } from "next/cache";
import { ApiError, api } from "@/lib/api-server";
import type { ResetReport } from "@/lib/types";
import { redirect } from "next/navigation";
import { PARENT_COOKIE, SESSION_COOKIE } from "@/lib/session";

export async function selectParent(formData: FormData) {
  const parentId = String(formData.get("parentId") ?? "");
  (await cookies()).set(PARENT_COOKIE, parentId, {
    path: "/",
    httpOnly: true,
    sameSite: "lax",
  });
  redirect("/classes");
}

export type RebuildResult =
  | { ok: true; report: ResetReport }
  | { ok: false; message: string };

/**
 * Throws away every booking and reinstates the seed fixtures, so the
 * walkthrough can be run again from a known state.
 *
 * Errors are returned rather than thrown: the caller is a dialog that has to
 * stay open and say what went wrong.
 */
export async function rebuildDemoData(): Promise<RebuildResult> {
  try {
    const report = await api.resetDemo();
    // Seat counts are rendered on every page, so the whole tree is stale.
    revalidatePath("/", "layout");
    return { ok: true, report };
  } catch (e) {
    return { ok: false, message: (e as ApiError).message };
  }
}

export type LoginState = { error: string | null };

/**
 * Exchanges the shared password for an API session.
 *
 * The token never reaches the browser as a readable value: it goes straight
 * into an httpOnly cookie, so page scripts (and anything injected into them)
 * cannot lift it. From then on the browser attaches it automatically to the
 * relative /api calls the Next rewrite proxies, and lib/api-server attaches it by
 * hand on the server-rendered ones.
 *
 * Errors are returned rather than thrown so the dialog can stay open and say
 * what went wrong, the same way rebuildDemoData does.
 */
export async function logIn(
  _prev: LoginState,
  formData: FormData,
): Promise<LoginState> {
  const password = String(formData.get("password") ?? "");
  if (!password) return { error: "Enter the password." };

  let token: string;
  let expiresAt: string;
  try {
    ({ token, expiresAt } = await api.login(password));
  } catch (e) {
    return { error: (e as ApiError).message };
  }

  (await cookies()).set(SESSION_COOKIE, token, {
    path: "/",
    httpOnly: true,
    sameSite: "lax",
    // Matching the token's own expiry keeps the cookie and the thing it
    // carries from disagreeing about when the session is over.
    expires: new Date(expiresAt),
    // The demo runs over plain HTTP locally; anywhere else this must be set.
    secure: process.env.NODE_ENV === "production",
  });

  // Every page was rendered by an unauthenticated server, so all of it is
  // stale - including the layout that decided to show the login dialog.
  revalidatePath("/", "layout");
  return { error: null };
}

/** Drops the session. The token stays valid until it expires; see SessionTokens. */
export async function logOut() {
  (await cookies()).delete(SESSION_COOKIE);
  revalidatePath("/", "layout");
  redirect("/");
}
