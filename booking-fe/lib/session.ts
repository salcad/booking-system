import { cookies } from "next/headers";

const COOKIE = "parentId";
const SESSION = "booking_session";

/**
 * Stands in for authentication. A real system would derive the parent from a
 * session; picking one from a dropdown keeps the demo focused on booking
 * correctness rather than on login plumbing, which the brief does not ask for.
 *
 * Distinct from the password gate below: that decides whether you may use
 * the API at all, this decides which parent you are pretending to be.
 */
export async function currentParentId(): Promise<number | null> {
  const raw = (await cookies()).get(COOKIE)?.value;
  if (!raw) return null;
  const id = Number(raw);
  return Number.isFinite(id) && id > 0 ? id : null;
}

/**
 * The API session token, held httpOnly so page scripts cannot read it.
 *
 * Presence is not validity - the backend verifies the signature and expiry on
 * every request, and is the only thing that can. This just answers "is it
 * worth trying?", which is all the login dialog needs to decide.
 */
export async function sessionToken(): Promise<string | null> {
  return (await cookies()).get(SESSION)?.value ?? null;
}

export const PARENT_COOKIE = COOKIE;
export const SESSION_COOKIE = SESSION;
