# ── Stage 1: Build ──────────────────────────────────────────────────────────
# Next 16 requires Node >= 20.9; use 22-alpine for long-term support.
FROM node:22-alpine AS build

WORKDIR /workspace

# Enable corepack for pnpm (repo standard).
RUN corepack enable

# Install dependencies first (layer cache).
COPY package.json pnpm-lock.yaml ./
RUN corepack prepare pnpm@latest --activate && pnpm install --frozen-lockfile

# Copy application source.
COPY . .

# Build with mock mode disabled (real API mode) and standalone output.
# NEXT_PUBLIC_API_BASE baked as EMPTY: the browser calls same-origin /api/* and
# the Ingress path-routes to bff-web / api-gateway (no localhost anywhere).
ARG NEXT_PUBLIC_MOCK=false
ENV NEXT_PUBLIC_MOCK=${NEXT_PUBLIC_MOCK}
ARG NEXT_PUBLIC_API_BASE=
ENV NEXT_PUBLIC_API_BASE=${NEXT_PUBLIC_API_BASE}
RUN pnpm build

# ── Stage 2: Runtime ───────────────────────────────────────────────────────
FROM node:22-alpine AS runtime

RUN addgroup -S appgroup && adduser -S appuser -u 1000

WORKDIR /app

# Copy Next.js standalone output (self-contained server + minimal deps).
COPY --from=build /workspace/.next/standalone ./
# Copy static assets into the standalone directory.
COPY --from=build /workspace/.next/static ./.next/static
# Copy public assets.
COPY --from=build /workspace/public ./public

USER appuser

ENV NODE_ENV=production PORT=3000 HOSTNAME=0.0.0.0
EXPOSE 3000

CMD ["node", "server.js"]
