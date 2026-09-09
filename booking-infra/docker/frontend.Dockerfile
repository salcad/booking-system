# syntax=docker/dockerfile:1.7
#
# Next.js UI. Build context is booking-fe/.
#
# Three stages: node_modules resolve once, the build consumes them, and the
# runtime keeps only Next's standalone output — the server plus the handful of
# packages it actually traced, not the full dependency tree.

# ---- stage 1: dependencies --------------------------------------------------
FROM node:22-alpine AS deps
WORKDIR /app

COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci

# ---- stage 2: build ---------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app

COPY --from=deps /app/node_modules ./node_modules
COPY . .

ENV NEXT_TELEMETRY_DISABLED=1

# next.config.ts is evaluated during `next build` and serialised into the
# standalone server.js, so the /api rewrite destination is fixed at build time —
# setting API_BASE only at runtime leaves the rewrite pointing at localhost.
# It is supplied here as well as in the runtime stage, and defaults to the
# compose service name so a plain `docker build` still produces a working image.
ARG API_BASE=http://api:8074
ENV API_BASE=$API_BASE

RUN npm run build

# ---- stage 3: runtime -------------------------------------------------------
FROM node:22-alpine AS runtime
WORKDIR /app

ARG API_BASE=http://api:8074
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=8073 \
    HOSTNAME=0.0.0.0 \
    API_BASE=$API_BASE

RUN addgroup -S -g 1001 nodejs && adduser -S -u 1001 -G nodejs nextjs

# The standalone bundle carries its own minimal node_modules and server.js.
# Static assets and public/ sit outside it and have to come across separately.
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=build --chown=nextjs:nodejs /app/public ./public

USER nextjs
EXPOSE 8073

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
    CMD wget -q -O- http://127.0.0.1:8073/ >/dev/null 2>&1 || exit 1

CMD ["node", "server.js"]
