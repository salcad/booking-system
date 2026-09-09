# booking-fe

Demo UI for the Ottodot trial booking system. See the
[root README](../README.md) for the design, the run instructions, and the
verification click-path.

```bash
npm install
npm run dev     # http://localhost:8073, expects the backend on :8074
```

`next.config.ts` proxies `/api/*` to the backend so the browser only ever talks
to its own origin — no CORS configuration is needed on the Spring side.

The API refuses anything without a session, and the two callers present one
differently, so there are two clients: `lib/api` for client components, whose
same-origin requests carry the session cookie through that proxy on their own,
and `lib/api-server` for server components and actions, which attaches the
token by hand because a server-side fetch has no cookie jar. **Server code must
import `@/lib/api-server`** — the other one sends no credential and gets a
uniform 401. They are separate modules rather than one because reading the
token needs `next/headers`, which cannot be imported into the client bundle.

The frontend has one job: **make the backend's correctness visible.** Every
screen exists to demonstrate an invariant. Every check here is advisory; the
backend re-validates everything and the UI renders its refusals — the login
dialog included, which is a password box in front of a door the API is already
holding shut.
