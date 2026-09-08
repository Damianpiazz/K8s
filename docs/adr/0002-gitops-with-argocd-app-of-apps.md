# ADR-0002: GitOps with Argo CD — app-of-apps pattern

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: platform team

## Context

The repo must be the single source of truth for the platform and the 17
services. CI/CD was already built in Phase 1 with a `GITOPS` variable that
selects between an Argo CD sync (`GITOPS == 'argocd'`) and a direct
`kubectl apply` fallback — the GitOps controller choice had to match that
pipeline and be demonstrable in a lecture demo.

## Decision

- **Argo CD** as the GitOps controller, in the **app-of-apps** pattern:
  `cluster/base/argocd/app-of-apps.yaml` (root Application `ecommerce-apps`)
  reconciles the 7 child Applications under
  `cluster/base/argocd/applications/` (ingress-nginx, cert-manager,
  external-dns, external-secrets, kyverno, keda, kube-prometheus-stack).
- Every Application runs `automated: { prune: true, selfHeal: true }` —
  drift is corrected, deletions in Git propagate.
- Bootstrap order is explicit and documented in `cluster/base/README.md`
  (install Argo CD → apply the Applications → apply the remaining
  cluster-scoped kustomization).
- The CD pipeline (`cd.yml`) syncs `ecommerce-<env>` after pushing images,
  keeping Argo CD as the delivery agent.

## Consequences

- Git is the single source of truth: rollback = revert a commit (see
  `docs/runbooks/scaling-and-recovery.md`).
- The UI gives the professor a visible reconcile loop for the demo
  (`bootstrap-argocd.ps1` prints the initial admin password + port-forward).
- App-of-apps adds a bootstrap ordering concern (charts must install their
  CRDs before cluster-scoped resources reference them) — documented in
  `cluster/base/README.md` with the symptom `no matches for kind: ClusterPolicy`.
- Argo CD does not manage itself (installed once by bootstrap) — accepted.

## Alternatives considered

- **Flux CD**: equally GitOps-correct, but the Phase 1 pipeline was already
  built around Argo CD (`argoproj/argo-cd-action`), and the reference repo
  uses Argo CD — consistency wins.
- **kubectl apply only (no controller)**: simpler but loses drift
  correction and the UI demo; kept only as `GITOPS != 'argocd'` fallback in
  `cd.yml` and as `scripts/deploy/apply-overlay.ps1`.
- **helmfile**: documented in `cluster/base/helmfile.yaml` as a non-GitOps
  comparison path only — deliberately not the primary flow (mixing both for
  the same component conflicts, e.g. external-dns).