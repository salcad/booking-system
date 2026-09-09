/**
 * Hand-mirrored from the backend DTOs.
 *
 * Generating a client from OpenAPI would be more setup than seven pages
 * justify. The cost of hand-mirroring is that these can drift; the mitigation
 * is that every one of them is exercised by the click-path in the README.
 */

export type BookingStatus =
  | "PENDING_PAYMENT"
  | "CONFIRMED"
  | "PAYMENT_FAILED"
  | "CANCELLED"
  | "EXPIRED";

export type PaymentOutcome =
  | "CONFIRMED"
  | "ALREADY_CONFIRMED"
  | "DECLINED"
  | "SEAT_UNAVAILABLE";

/** Machine-readable reasons the backend can refuse a request. */
export type ApiErrorCode =
  | "CLASS_FULL"
  | "DUPLICATE_BOOKING"
  | "SEAT_UNAVAILABLE"
  | "NOT_PAYABLE"
  | "NOT_CANCELLABLE"
  | "NOT_FOUND"
  | "VALIDATION_ERROR"
  | "MALFORMED_REQUEST"
  | "INTERNAL_ERROR"
  /** No valid session token was presented. Every route can answer this. */
  | "UNAUTHENTICATED"
  | "INVALID_CREDENTIALS"
  | "TOO_MANY_ATTEMPTS";

/** What POST /api/auth/login hands back once the password checks out. */
export interface LoginResponse {
  token: string;
  expiresAt: string;
}

export interface Parent {
  id: number;
  name: string;
  email: string;
}

/** The live booking that blocks a second one for this child (invariant I2). */
export interface ExistingBooking {
  bookingId: number;
  status: Extract<BookingStatus, "PENDING_PAYMENT" | "CONFIRMED">;
}

export interface Student {
  id: number;
  name: string;
  grade: string;
  /**
   * Null when the child is free to book, and also when the request named no
   * class. Only populated when `students()` is called with a trialClassId.
   */
  existingBooking: ExistingBooking | null;
}

export interface TrialClass {
  id: number;
  subject: string;
  startsAt: string;
  capacity: number;
  seatsRemaining: number;
  full: boolean;
}

export interface BookingEvent {
  fromStatus: BookingStatus | null;
  toStatus: BookingStatus;
  reason: string | null;
  actor: string;
  at: string;
}

export interface Booking {
  id: number;
  studentId: number;
  trialClassId: number;
  status: BookingStatus;
  holdExpiresAt: string | null;
  amountCents: number;
  history: BookingEvent[];
}

export interface PaymentResponse {
  bookingId: number;
  status: BookingStatus;
  paymentStatus: "SUCCEEDED" | "FAILED" | "REFUNDED";
  outcome: PaymentOutcome;
  message: string;
}

export interface RosterEntry {
  bookingId: number;
  studentId: number;
  studentName: string;
  grade: string;
}

/**
 * The roster embeds the domain record rather than the list DTO, so it exposes
 * claimedSeats instead of seatsRemaining.
 */
export interface Roster {
  trialClass: {
    id: number;
    subject: string;
    startsAt: string;
    capacity: number;
    claimedSeats: number;
  };
  confirmed: RosterEntry[];
  pendingHolds: RosterEntry[];
}

export interface RaceAttempt {
  index: number;
  studentId: number;
  bookingId: number | null;
  phase: "PAID" | "REJECTED_AT_BOOKING" | "PAYMENT_ERROR";
  outcome: string;
  message: string;
}

export interface RaceReport {
  trialClassId: number;
  capacity: number;
  seatsBefore: number;
  seatsAfter: number;
  contenders: number;
  confirmed: number;
  attempts: RaceAttempt[];
  invariantHolds: boolean;
  verdict: string;
}

/** What the demo rebuild put back. */
export interface ResetReport {
  parents: number;
  students: number;
  trialClasses: number;
  bookings: number;
}

/** RFC 7807 body, plus the backend's own `code`. */
export interface ProblemDetail {
  title?: string;
  status: number;
  detail?: string;
  code?: ApiErrorCode;
}
