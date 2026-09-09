import { createApi, type AuthHeaders } from "./api";
import { sessionToken } from "./session";

export { ApiError, formatClassTime, formatMoney } from "./api";

/**
 * The server's client: the same API surface, with the session token attached
 * to every request.
 *
 * It is a separate module rather than a branch inside lib/api because reading
 * the token needs next/headers, which exists only in the server graph - a
 * single shared module importing it fails the build for the client components
 * that also use this client.
 *
 * Server components and server actions must import from here. Importing the
 * browser's `api` on the server would send no credential and get a uniform
 * 401 back, which is a loud enough failure to catch immediately.
 */
const bearerToken: AuthHeaders = async () => {
  const token = await sessionToken();
  const headers: Record<string, string> = {};
  // No cookie yet is not an error here: the login call is made through this
  // same client, and the API lets that one route through unauthenticated.
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
};

export const api = createApi(bearerToken);
