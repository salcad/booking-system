import type {
  Booking,
  LoginResponse,
  Parent,
  PaymentResponse,
  ProblemDetail,
  RaceReport,
  ResetReport,
  Roster,
  Student,
  TrialClass,
} from "./types";

/**
 * Server components need an absolute URL; the browser must use a relative one
 * so the request goes through the Next rewrite and stays same-origin.
 */
const SERVER_BASE = process.env.API_BASE ?? "http://localhost:8074";
const base = () => (typeof window === "undefined" ? SERVER_BASE : "");

/**
 * Supplies the session credential on every call.
 *
 * The API refuses anything unauthenticated, and the two callers present their
 * session differently. In the browser the request is relative, so it goes
 * through the Next rewrite and the httpOnly cookie rides along by itself -
 * hence the do-nothing default below, which is also all this side *could* do,
 * since script cannot read that cookie. On the server the fetch is a fresh
 * outbound call to another origin with no cookie jar behind it, so the token
 * is attached by hand - see lib/api-server.
 *
 * It is passed in rather than resolved here because reading it needs
 * next/headers, and this module is in the client bundle too, where importing
 * that is a build error however carefully the call is guarded.
 */
export type AuthHeaders = () => Promise<Record<string, string>>;

const noCredentials: AuthHeaders = async () => ({});

/** A failed request carrying the backend's machine-readable reason. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: ProblemDetail["code"];

  constructor(problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? "Request failed");
    this.name = "ApiError";
    this.status = problem.status;
    this.code = problem.code;
  }
}

function isPaymentResponse(body: unknown): body is PaymentResponse {
  return (
    typeof body === "object" &&
    body !== null &&
    "outcome" in body &&
    "bookingId" in body
  );
}

export function createApi(authHeaders: AuthHeaders = noCredentials) {
  async function request<T>(path: string, init?: RequestInit): Promise<T> {
    let res: Response;
    try {
      res = await fetch(`${base()}${path}`, {
        ...init,
        headers: {
          "Content-Type": "application/json",
          ...(await authHeaders()),
          ...init?.headers,
        },
        // Seat counts must never be served from a cache. A stale count is
        // precisely the bug this exercise is about, so nothing here is cached.
        cache: "no-store",
      });
    } catch {
      throw new ApiError({
        status: 503,
        detail: "Cannot reach the booking API. Is the backend running on :8074?",
      });
    }

    if (!res.ok) {
      let problem: ProblemDetail = { status: res.status, detail: res.statusText };
      try {
        problem = { ...problem, ...(await res.json()) };
      } catch {
        // A non-JSON error body (a proxy timeout, say) leaves the default above.
      }
      throw new ApiError(problem);
    }

    return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
  }

  const post = <T>(path: string, body: unknown) =>
    request<T>(path, { method: "POST", body: JSON.stringify(body) });

  return {
    /**
     * Exchanges the shared password for a session token. Called only by the
     * login server action, which is what puts the token in a cookie - so
     * unlike everything else here, this one runs before a session exists.
     */
    login: (password: string) =>
      post<LoginResponse>("/api/auth/login", { password }),

    /**
     * Confirms the current token is still one the API will accept. Rejects
     * with 401 when it is not, because the auth filter turns this route away
     * like any other - which is exactly what makes it a usable probe.
     */
    session: () => request<{ authenticated: boolean }>("/api/auth/session"),

    parents: () => request<Parent[]>("/api/parents"),
    /**
     * A parent's children. Pass trialClassId and each child reports the live
     * booking they already hold for that class, so the booking form can rule
     * out a duplicate before the parent clicks rather than after the 409.
     */
    students: (parentId: number, trialClassId?: number) =>
      request<Student[]>(
        `/api/parents/${parentId}/students`
          + (trialClassId === undefined ? "" : `?trialClassId=${trialClassId}`),
      ),

    trialClasses: () => request<TrialClass[]>("/api/trial-classes"),
    trialClass: (id: number) => request<TrialClass>(`/api/trial-classes/${id}`),
    roster: (id: number) => request<Roster>(`/api/trial-classes/${id}/roster`),

    booking: (id: number) => request<Booking>(`/api/bookings/${id}`),
    createBooking: (studentId: number, trialClassId: number) =>
      post<Booking>("/api/bookings", { studentId, trialClassId }),
    cancelBooking: (id: number) =>
      post<Booking>(`/api/bookings/${id}/cancel`, {}),

    /**
     * Payment is the one endpoint whose failures are not errors.
     *
     * A declined card answers 402 and a lost seat answers 409, but both carry
     * a normal PaymentResponse body describing a legitimate outcome that the
     * UI must render. Only a genuine refusal (an unpayable booking, say)
     * carries a ProblemDetail - and it also uses 409, so the status code alone
     * cannot tell them apart. The body shape can.
     */
    pay: async (
      bookingId: number,
      outcome: "SUCCESS" | "FAILURE",
      idempotencyKey: string,
    ): Promise<PaymentResponse> => {
      const res = await fetch(`${base()}/api/bookings/${bookingId}/payment`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(await authHeaders()),
        },
        body: JSON.stringify({ outcome, idempotencyKey }),
        cache: "no-store",
      }).catch(() => null);

      if (!res) {
        throw new ApiError({
          status: 503,
          detail: "Cannot reach the booking API.",
        });
      }

      const body: unknown = await res.json().catch(() => null);
      if (isPaymentResponse(body)) {
        return body;
      }
      throw new ApiError({
        status: res.status,
        ...(body as ProblemDetail | null),
      });
    },

    race: (trialClassId: number, contenders: number) =>
      post<RaceReport>("/api/demo/race", { trialClassId, contenders }),

    /** Wipes the database and replays the seed fixtures. Demo only. */
    resetDemo: () => post<ResetReport>("/api/demo/reset", {}),
  };
}

/**
 * The browser's client. Client components import this one; their requests are
 * same-origin, so the session cookie authenticates them without help.
 */
export const api = createApi();

export function formatMoney(cents: number): string {
  return `$${(cents / 100).toFixed(2)}`;
}

export function formatClassTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    weekday: "short",
    day: "numeric",
    month: "short",
    hour: "numeric",
    minute: "2-digit",
  });
}
