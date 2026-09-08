# ADR-0010: BFF + API Gateway pattern (api-gateway + bff-web)

- **Status**: proposed
- **Date**: 2026-09-07
- **Deciders**: platform team

## Context

External traffic enters the platform through ingress-nginx into the
`api-gateway` deployment (Spring Cloud Gateway, port 8080, the only ingress
with a public host `api.<domain>`). The business services (catalog, cart,
order, …) are behind it. A second front-facing tier exists: `bff-web`, a
backend-for-frontend that composes and shapes responses for web clients. The
question is where cross-cutting concerns and client-specific composition
live.

## Decision

- **api-gateway = the edge gateway**: TLS termination at the ingress,
  JWT validation (ADR-0005), CORS, routing to services by discovery name
  (ADR-0004), and the NetworkPolicy trust boundary (only ingress-nginx may
  reach it; it may reach all ecommerce peers).
- **bff-web = the composition layer for browser/SPA clients**: aggregates
  calls to several services into one client-friendly payload, so the SPA
  does one round trip and never talks to business services directly.
- Both are full citizens of the uniform service layout (k8s/base + prod
  overlay + HPA/PDB/NetworkPolicy + ServiceMonitor); bff-web routes via
  discovery like any other peer.
- The browser → bff-web → services path is the **recommended** call flow for
  UI traffic; service-to-service communication stays direct (no bff hop).

## Consequences

- One extra hop for web traffic (bff aggregates it away at the API level —
  net win for chatty UIs).
- The gateway stays thin and generic; client-specific shaping moves to the
  bff, which can evolve per client later (mobile bff, partner bff) without
  touching the gateway.
- Two more deployments to secure/observe — both follow the exact same
  contract, so the tooling (tests, dashboards) applies unchanged.
- bff-web must replicate the gateway's auth story (or trust the gateway
  header) — the JWT propagation design between gateway and bff is still to
  be finalized (open point for the implementation phase).

## Alternatives considered

- **Gateway-only (no bff)**: simplest (9 services reachable directly), but
  every UI change forces gateway route/aggregation changes and the SPA does
  N round trips — rejected as the primary path.
- **GraphQL gateway**: schema-driven aggregation is elegant but adds a
  whole query layer (graphql-java, schema governance) to the TP scope —
  deferred; the bff pattern covers the same need imperatively.
- **Ingress-level aggregation (nginx subrequests)**: possible but pushes
  business logic into the ingress config — rejected: policies and probes
  are uniform per service, and subrequest composition is a config nightmare
  to maintain.