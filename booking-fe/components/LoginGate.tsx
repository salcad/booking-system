import { ApiError, api } from "@/lib/api-server";
import { sessionToken } from "@/lib/session";
import LoginDialog from "./LoginDialog";

/**
 * Shows the login dialog instead of the app until there is a working session.
 *
 * This is presentation, not protection. The API rejects every unauthenticated
 * request on its own (see AuthFilter), so nothing here is load-bearing - its
 * only job is that a signed-out visitor gets a password box rather than seven
 * pages of "Sign in to use this API."
 */
export default async function LoginGate({
  children,
}: {
  children: React.ReactNode;
}) {
  if (await hasSession()) return <>{children}</>;
  return <LoginDialog />;
}

/**
 * Holding a cookie is not the same as holding a valid session - a token
 * outlives neither its expiry nor a restart that regenerates the signing key -
 * so the cookie only decides whether it is worth asking the API.
 */
async function hasSession(): Promise<boolean> {
  if (!(await sessionToken())) return false;
  try {
    return (await api.session()).authenticated;
  } catch (e) {
    // A rejected token means sign in again; an unreachable API is a different
    // problem, and one the pages already report far better than a password
    // box would. Let those through to say so.
    return !(e instanceof ApiError && e.status === 401);
  }
}
