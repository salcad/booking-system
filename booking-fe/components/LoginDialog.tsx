"use client";

import { useActionState, useEffect, useRef, useState } from "react";
import { logIn, type LoginState } from "@/app/actions";

const INITIAL: LoginState = { error: null };

/**
 * The password box. Rendered only by LoginGate, and only when there is no
 * session, so it has no dismissed state of its own: it disappears when the
 * action succeeds and the server re-renders the layout with the app in place.
 */
export default function LoginDialog() {
  const dialog = useRef<HTMLDialogElement>(null);
  const [state, formAction, pending] = useActionState(logIn, INITIAL);
  const [revealed, setRevealed] = useState(false);

  // showModal() cannot be called during render, and the open attribute alone
  // renders a non-modal dialog, so the element is driven from an effect - the
  // same way RebuildDemoButton does it.
  useEffect(() => {
    const el = dialog.current;
    if (el && !el.open) el.showModal();
  }, []);

  return (
    <dialog
      ref={dialog}
      className="dialog"
      aria-labelledby="loginTitle"
      // There is nothing behind the dialog to fall back to, so Escape and
      // backdrop dismissal are refused rather than closing it.
      onCancel={(e) => e.preventDefault()}
    >
      <form action={formAction} className="space-y-4">
        <h2 id="loginTitle" className="text-lg font-semibold">
          Sign in
        </h2>
        <p className="muted text-sm">
          This demo is password protected. Enter the password to continue.
        </p>

        <div>
          <label className="label" htmlFor="password">
            Password
          </label>
          <div className="relative">
            <input
              id="password"
              name="password"
              type={revealed ? "text" : "password"}
              autoFocus
              autoComplete="current-password"
              // Room for the toggle, so a long password does not run under it.
              className="select pr-10"
              aria-invalid={state.error ? true : undefined}
              aria-describedby={state.error ? "passwordError" : undefined}
            />
            <button
              type="button"
              // Inside a form, a button with no type submits it - which here
              // would try to sign in every time someone peeked at what they
              // had typed.
              onClick={() => setRevealed((shown) => !shown)}
              // The icon carries no text, so the accessible name has to come
              // from aria-label: it says what the button does, while
              // aria-pressed says which way it is currently set. Both, because
              // the name alone leaves a screen reader guessing whether this is
              // a state or an action, and the state alone never says what it
              // toggles.
              aria-pressed={revealed}
              aria-label={revealed ? "Hide password" : "Show password"}
              aria-controls="password"
              className="muted absolute inset-y-0 right-0 flex items-center
                         px-3 hover:text-[color:var(--foreground)]"
            >
              <EyeIcon crossed={revealed} />
            </button>
          </div>
          {state.error && (
            <p
              id="passwordError"
              // Announced rather than just rendered: the dialog keeps focus in
              // the field, so a screen reader would otherwise miss the reason.
              aria-live="polite"
              className="mt-1.5 text-sm text-red-700 dark:text-red-400"
            >
              {state.error}
            </p>
          )}
        </div>

        <div className="flex justify-end">
          <button type="submit" className="btn-primary" disabled={pending}>
            {pending ? "Signing in..." : "Sign in"}
          </button>
        </div>
      </form>
    </dialog>
  );
}

/**
 * Inline rather than from an icon set: two paths do not justify a dependency,
 * and currentColor lets it inherit the muted/hover colours already defined for
 * the button in both themes.
 *
 * Crossed out means the password is currently visible - the icon shows what
 * clicking will do, matching the aria-label rather than contradicting it.
 */
function EyeIcon({ crossed }: { crossed: boolean }) {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.4"
      strokeLinecap="round"
      strokeLinejoin="round"
      // Decorative: the button's aria-label is the accessible name, and a
      // second one here would have a screen reader announce it twice.
      aria-hidden="true"
      focusable="false"
    >
      <path d="M1.2 8S3.7 3.4 8 3.4 14.8 8 14.8 8 12.3 12.6 8 12.6 1.2 8 1.2 8Z" />
      <circle cx="8" cy="8" r="2.1" />
      {crossed && <path d="M2.4 2.4l11.2 11.2" />}
    </svg>
  );
}
