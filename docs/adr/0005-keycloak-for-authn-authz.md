# ADR-0005: Keycloak for authentication and authorization (auth service)

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: platform team

## Context

Users (browser → api-gateway) need authentication and token-based
authorization across the platform. The `auth` service is a first-class
deployment in the uniform layout (`services/auth/k8s/base/deployment.yaml`),
and the reference repo carries a Keycloak-style auth component. The gateway
must validate bearer tokens per route without every service re-implementing
JWT verification.

## Decision

- **Keycloak** runs as the `auth` service (image `acr.azurecr.io/auth`,
  container port 8080, admin bootstrap env
  `KEYCLOAK_ADMIN`/`KEYCLOAK_ADMIN_PASSWORD` sourced from the
  `keycloak-admin-credentials` Secret).
- Admin credentials come from a placeholder Secret today; the production
  path is External Secrets → Azure Key Vault (ADR-0007) so the console
  password is never in Git.
- The **api-gateway** terminates token validation (JWT at the edge): routes
  are protected at the gateway, services behind the ecommerce peer policy
  trust the gateway (matching the per-service NetworkPolicy ingress rule
  "only the api-gateway may call the catalog API").

## Consequences

- Single identity provider: realms, clients and roles configure once and
  every service inherits the story.
- The auth Pod must stay healthy or every login path fails — it gets the
  same HPA/PDB/probes treatment as any other service, and
  `troubleshooting.md` covers its CrashLoopBackOff causes
  (misconfigured admin secret, missing DB/backing store).
- Keycloak in `start-dev` mode is demo-convenient but not production
  hardened; the prod overlay should switch to production mode + managed
  Postgres (ADR-0006) before any real traffic.

## Alternatives considered

- **Custom Spring Security + JWT service**: full control, but re-implements
  realms/clients/roles that Keycloak ships; more code to demo, less
  impressive.
- **Azure AD B2C / Entra ID**: the natural managed choice for a real Azure
  product (entra-specific flows, cost tiers) but it would tie the local
  minikube/kind demo to Azure connectivity — rejected for TP portability.
- **No gateway auth (per-service)**: every service would need the same JWT
  filter; rejected — that is precisely the gateway's job (ADR-0010).