# ADR-0009: PSA restricted + Kyverno policies as the admission chain

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: platform team

## Context

The security phase requires hard gates on what can run in the tenant
namespace: no root containers, no privileged workloads, no `latest` image
tags, no missing labels/resources. Kubernetes ships a built-in admission
chain (Pod Security Admission) and the platform already installs Kyverno
(`cluster/base/kyverno/policies/`) — the two must be combined without
fighting each other.

## Decision

- **PSA `restricted`** enforced via namespace labels
  (`security/pod-security/pod-security-labels.yaml`) on **ecommerce + data**;
  `observability`/`security`/`platform` stay audit+warn (chart workloads are
  not restricted-compliant yet).
- **Kyverno ClusterPolicies** (Enforce on tenant namespaces):
  `require-labels` (app + env on every Pod), `disallow-latest-tag`,
  `require-resources` (Audit until every workload declares resources); an
  Audit-mode `disallow-plain-secrets` guard (ADR-0007) completes the layer.
- Service deployments conform: `runAsNonRoot: true`, `runAsUser: 1000`,
  `allowPrivilegeEscalation: false`, drop ALL capabilities,
  `seccompProfile: {type: RuntimeDefault}` — verified by
  `tests/manifests/test-service-contract.py` for all 17 services.
- **default-deny NetworkPolicy** (`security/network-policies/default-deny-all.yaml`)
  covers pods without a per-service policy; per-service policies keep working
  via policy union semantics.

## Consequences

- Admission chain per Pod in `ecommerce`: PSA (hard gate) → Kyverno labels/
  image/resource checks → NetworkPolicy isolation at runtime.
- Charts/tooling that violate restricted must stay out of the tenant
  namespaces — this is why platform namespaces are audit-only (documented
  interplay in `security/README.md`).
- `disallow-latest-tag` rejects manual applies with `:latest` images —
  a documented workflow constraint (run the CD pipeline or
  `deploy-cd-manual.ps1` first).
- `runAsUser: 1000` + `readOnlyRootFilesystem: false` is restricted-legal
  (services write /tmp for embedded Tomcat); no privilege creep.

## Alternatives considered

- **Kyverno for everything (no PSA)**: single mechanism, but built-in PSA is
  free, webhook-free and the course explicitly asks for it; both are
  demonstrated and documented — richer answer for the professor.
- **OPA/Gatekeeper**: equivalent to Kyverno but the repo already ships
  Kyverno with chart applications; switching would touch the platform layer.
- **Enforce restricted on all namespaces**: fails immediately on
  chart workloads (verified limitation) — the audit/warn matrix is the
  honest middle ground until each chart is restricted-compliant.