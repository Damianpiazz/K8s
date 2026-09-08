# security/ — app-level security-as-code (Phase 8)

The **application security layer** for the e-commerce platform. It builds on
the Phase 3 platform bootstrap (`cluster/base/`) — namespaces, Kyverno
policies, external-secrets, hard-coded ServiceAccounts and per-service
NetworkPolicies already exist there and are **read-only inputs** to this layer.

| Concern | Phase 3 platform (exists) | Phase 8 app layer (this directory) |
|---|---|---|
| Admission policy (labels/images/resources) | Kyverno ClusterPolicies (`cluster/base/kyverno/policies/`) | `secret-policies/disallow-plain-secrets.yaml` (Audit) |
| Pod security | — (nothing) | `pod-security/pod-security-labels.yaml` (PSA restricted, mode matrix) |
| Network | per-service NetworkPolicies (`services/*/k8s/base/networkpolicy.yaml`) | `network-policies/default-deny-all.yaml` safety net |
| RBAC | ServiceAccounts per service (`automountServiceAccountToken: false`) | `rbac/`: ecommerce-reader + ecommerce-admin Roles/Bindings, `sa-tokens.md` |
| Secrets | external-secrets ClusterSecretStore + example (`cluster/base/external-secrets/`) | `secret-policies/`: guard against hand-written secrets |

## Enforced vs advisory

| Layer | Enforced? | Notes |
|---|---|---|
| PSA `restricted` on `ecommerce` + `data` | **YES** (enforce mode) | **Known blocker**: service Deployments lack `seccompProfile` — see below. |
| PSA `restricted` on `observability` / `security` / `platform` | **NO** (audit + warn) | kube-prometheus-stack + tooling workloads are not restricted-compliant yet. Promote after verifying each chart. |
| default-deny-all in `ecommerce` | **YES** (NetworkPolicy) | Union semantics: per-service policies keep working. |
| ecommerce-reader / ecommerce-admin | **YES** (RBAC) | Groups `platform-developers` / `platform-team` — adjust to your AAD groups. |
| disallow-plain-secrets | **Audit** (advisory) | Flip to Enforce AFTER tuning (see `secret-policies/readme.md`) — Enforce-as-shipped would block external-secrets Secrets (key `db-password`). |

## PSA restricted ↔ Kyverno interplay

Admission chain for a Pod in `ecommerce` (applied in order):

1. **PSA (built-in, webhook-free)**: rejects Pods violating the namespace's
   `restricted` profile. This is the HARD GATE.
2. **Kyverno (dynamic admission webhook)**: enforces `require-labels`
   (app+env), `disallow-latest-tag`, `require-resources` (Audit) on the same
   Pods.

Design consequence: Kyverno's `require-labels` already EXCLUDES the platform
namespaces (`observability`, `platform`, ...) because chart workloads use
`app.kubernetes.io/name` instead of the plain `app` label. PSA doesn't care
about labels — but since PSA is restricted **only on ecommerce/data**, the two
mechanisms roughly align: tenants get both gates, tooling namespaces get only
the Kyverno label/image gates. This is intentional.

## Known blockers before `enforce=restricted` passes (VERIFIED)

Every service Deployment (`services/{catalog,order}-svc/k8s/base/deployment.yaml`,
verified on two) sets:

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 1000
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: false
  capabilities: { drop: [ALL] }
```

That is restricted-compatible **except for one missing field**: `seccompProfile`.
Since Kubernetes 1.27 the `restricted` profile requires
`seccompProfile: {type: RuntimeDefault}` (or the legacy alpha annotation), so
**the current deployments are REJECTED by enforce=restricted**. Required fix
(2 lines per service, in `services/*/k8s/base/deployment.yaml` — out of scope
for this read-only layer):

```yaml
securityContext:
  seccompProfile: { type: RuntimeDefault }   # ← add; keep the rest as-is
```

Apply `security/pod-security` **after** adding this to the service
deployments. If you cannot touch the deployments, keep `ecommerce` at
audit+warn until you can.

Secondary notes:
- `readOnlyRootFilesystem: false` is ALLOWED by restricted (only `true` is not
  required); services intentionally write `/tmp` for embedded Tomcat.
- The `data` namespace: bitnami-style charts (postgres/redis/kafka, Phase 4)
  default to non-root — verify the chart's seccomp settings before trusting
  `enforce` there too.

## Wiring into GitOps (for the orchestrator — DO NOT edit cluster/ yourself)

Add to **every** `cluster/overlays/{dev,staging,prod}/kustomization.yaml`
inside the existing `resources:` list:

```yaml
  # ── Security app layer (Phase 8) ──
  - ../../../security
```

Order note: `security/` contains only CRs (Namespace labels, NetworkPolicy,
RBAC, ClusterPolicy) — it has no hard dependency on the service manifests being
present first, but for the best demo effect deploy it AFTER the services are
up (the default-deny then has gaps to close, and PSA warnings are visible).

## Verified against

- `cluster/base/namespaces/namespaces.yaml` (the 5 platform namespaces + ecommerce)
- `services/*/k8s/base/{deployment,serviceaccount,networkpolicy}.yaml` (PSA facts, SA automount, DNS egress)
- `cluster/base/kyverno/policies/*` (namespace exclusions, Audit precedent)
- `cluster/base/external-secrets/*` (ESO key names → Audit-mode rationale)
- `docs/plan-ecommerce-k8s.md` (§4.6 security vision, §8 PSA restricted requirement)