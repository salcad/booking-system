# booking-infra

Deployment for the trial-booking stack: Postgres, the Spring Boot API, and the
Next.js UI, as three containers on a single Ubuntu VPS.

Both application images are multi-stage. Maven, the JDK, and the full
`node_modules` tree stay in builder stages, so what ships is a JRE plus one jar
(~238 MB) and Next's standalone bundle (~231 MB) — neither runtime image can
compile anything, and both run as a non-root user.

## Layout

```
booking-infra/
├── docker-compose.yml        the stack
├── .env.example              copy to .env and fill in
└── docker/
    ├── backend.Dockerfile    build context is ../booking-be
    ├── frontend.Dockerfile   build context is ../booking-fe
    └── caddy/Caddyfile       used only by the tls profile
```

## Deploy to a fresh Ubuntu VPS

Install Docker from the official repository — Ubuntu's `docker.io` package is
usually too old for Compose v2 and BuildKit cache mounts:

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER" && newgrp docker
```

Then, from a clone of the repository:

```bash
cd booking-infra
cp .env.example .env
# POSTGRES_PASSWORD has no default and the stack refuses to start without it:
sed -i "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$(openssl rand -base64 32)|" .env

docker compose up -d --build
```

The build runs on the VPS. It needs roughly 2 GB of RAM and a few minutes; on a
1 GB droplet, add swap first or build elsewhere and push the images to a
registry.

Check it came up:

```bash
docker compose ps          # all three should read healthy
curl -i localhost:8073/api/trial-classes    # 401 UNAUTHENTICATED
```

`8073` is the only published port — `web`. The API is internal, so
`localhost:8074` refuses the connection by design, and nothing answers on `:80`
unless the tls profile below is running. The `401` is the success case: the
request reached the API through Next's `/api/*` rewrite and the password gate
turned it away. To see the data, log in first:

```bash
TOKEN=$(curl -s localhost:8073/api/auth/login \
  -H 'content-type: application/json' -d '{"password":"644k1n9"}' | jq -r .token)
curl -s localhost:8073/api/trial-classes -H "Authorization: Bearer $TOKEN"
```

### With HTTPS

Point the domain's A record at the VPS, then set `PUBLIC_DOMAIN`, `ACME_EMAIL`,
and `WEB_PORT=127.0.0.1:8073` in `.env` — the last one stops the UI publishing
on `:80` so Caddy can take it — and start with the profile:

```bash
docker compose --profile tls up -d --build
```

Caddy obtains and renews the Let's Encrypt certificate itself. Ports 80 and 443
must be open and free; `sudo ss -lptn 'sport = :80'` finds whatever is holding
them (a distro nginx, usually).

## What is exposed

Only the web container publishes a port. The API and Postgres are reachable
only on the private compose network, so a VPS with no firewall rules still is
not serving Postgres to the internet. The browser reaches the API through
Next's `/api/*` rewrite, which is also why the backend needs no CORS config.

**The API's `/api/demo/*` endpoints are reachable through that proxy**, and
`POST /api/demo/reset` wipes every booking, student, and class. They are what
the race-demo page drives, so they are deliberately left open — on a public
deployment that strangers should not be able to reset, uncomment the
`respond /api/demo/* 404` line in `docker/caddy/Caddyfile`.

## Configuration

Everything lives in `.env`; `.env.example` documents each key. Only
`POSTGRES_PASSWORD` is required. `BOOKING_HOLD_DURATION` and
`BOOKING_TRIAL_PRICE_CENTS` reach Spring through relaxed binding onto
`booking.*` in `application.yml`, so the seat-hold window can be shortened for a
demo without rebuilding:

```bash
# .env → BOOKING_HOLD_DURATION=PT2M
docker compose up -d api
```

`application.yml` logs the app package at DEBUG for local work; the compose file
overrides it to INFO, and every container caps its JSON log file at 3 × 10 MB so
a long-running VPS does not fill its disk.

## Operating

```bash
docker compose logs -f api            # follow one service
docker compose up -d --build          # redeploy after a git pull
docker compose down                   # stop, keeping the data volume
docker compose down -v                # stop and destroy the database
```

Flyway runs the migrations in `booking-be/src/main/resources/db/migration` on
every API start, so a redeploy needs no separate migration step. The data lives
in the `booking_db-data` volume and survives `down` and `up --build`.

Back up and restore:

```bash
docker compose exec -T db pg_dump -U booking booking | gzip > backup.sql.gz
gunzip -c backup.sql.gz | docker compose exec -T db psql -U booking booking
```

## Notes on the build

The backend image skips tests: the suite drives Postgres through Testcontainers,
which needs a Docker socket the builder does not have. CI already runs
`mvn verify` on every push, so this stage only packages what CI proved.

`booking-fe/next.config.ts` sets `output: "standalone"`, which is what lets the
runtime stage drop `node_modules`. `API_BASE` is passed as a *build* argument as
well as a runtime variable, because `next.config.ts` is evaluated during
`next build` and serialised into the standalone `server.js` — set only at
runtime, the `/api` rewrite would still point at `localhost:8074`.
