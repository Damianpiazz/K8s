# cluster/overlays — Per-environment Kustomize overlays

This directory is the **environment layer** of the GitOps model (Phase 4). It
extends `cluster/base` (the platform bootstrap, Phase 3) with per-environment
configuration for `dev`, `staging`, and `prod`.

```
cluster/
├─ base/       # ONE platform definition — namespaces, Argo CD app-of-apps,
│              # ClusterIssuers, IngressClass, ClusterSecretStore, Kyverno,
│              # KEDA example, monitoring stack
└─ overlays/   # THREE environment variations — this directory
    ├─ dev/       # testing playground
    ├─ staging/   # pre-prod validation
    └─ prod/      # production
```

## The model

Kustomize uses the **base + overlay** pattern:

- `cluster/base` holds everything that is **identical across environments** —
  the namespaces, the Argo CD Applications, the CRDs-backed platform resources.
- Each overlay references `../../base` as `resources`, then **adds** what is
  env-specific and **patches** what differs.

### What changes per environment

| Concern | Where | dev | staging | prod |
|---|---|---|---|---|
| Certificate issuer | `issuer-default.yaml` (new resource per overlay) | Let's Encrypt **staging** | Let's Encrypt **staging** | Let's Encrypt **production** |
| Key Vault URL | `patches/secret-store-patch.yaml` (patches `ClusterSecretStore/azure-keyvault` from base) | `kv-dev-ecommerce` | `kv-staging-ecommerce` | `kv-prod-ecommerce` |
| Environment config | `env-config.yaml` (new ConfigMap per overlay) | `ENVIRONMENT=dev`, `LOG_LEVEL=DEBUG` | `ENVIRONMENT=staging`, `LOG_LEVEL=INFO` | `ENVIRONMENT=prod`, `LOG_LEVEL=INFO` |
| DNS zone (external-dns) | not patched here — set on the Azure DNS zone + `--domain-filter` in base | — | — | — |

### Why base's issuers are NOT patched

`cluster/base` already defines `selfsigned`, `letsencrypt-staging`, and
`letsencrypt-prod` ClusterIssuers (see `cluster/base/cert-manager/cluster-issuer.yaml`).
Instead of mutating those, each overlay **adds its own** `letsencrypt-default`
ClusterIssuer pointing at the right ACME server for the environment.

Services select the issuer declaratively with the annotation:

```yaml
metadata:
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-default
```

This gives you one stable name (`letsencrypt-default`) across environments while
the actual CA behind it switches per overlay. Explicit and honest — no patch
surgery on base.

### Why env-config is a new resource, not a patch

`ecommerce-env-config` does not exist in base — kustomize patches only replace
fields in **existing** resources. Adding it as a plain resource per overlay is
the idiomatic way. Services (Phase 5+) consume it via `envFrom`:

```yaml
envFrom:
  - configMapRef:
      name: ecommerce-env-config
```

### Placeholders in env-config.yaml

The personal data connection values follow the Azure FQDN patterns produced by
`infra/terraform` (Phase 2) and are **placeholders** — replace each `# REPLACE
with terraform output` value before a real deployment:

| Key | dev example | Replace with |
|---|---|---|
| `DB_HOST` | `pg-dev-ecommerce.postgres.database.azure.com` | `terraform output db_fqdn` |
| `REDIS_HOST` | `redis-dev-ecommerce.redis.cache.windows.net` | `terraform output redis_hostname` |
| `KAFKA_BOOTSTRAP` | `ns-dev-ecommerce.servicebus.windows.net:9093` | `terraform output event_hubs_kafka_endpoint` |

## How Argo CD uses overlays

The Argo CD **app-of-apps** in `cluster/base` syncs the platform. For
environment deployment, point each environment's Argo CD Application at the
overlay path instead of base:

```yaml
# Example — prod Application (cluster/overlays/prod is the source)
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: ecommerce-platform-prod
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/<org>/<repo>.git
    targetRevision: main
    path: cluster/overlays/prod          # ← overlay path, not base
  destination:
    server: https://kubernetes.default.svc
    namespace: argocd
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

One cluster can host several environments (dev + staging + prod namespaces
already exist in base) by pointing different Applications at different overlay
paths — or each environment gets its own cluster and its own Application with
the matching overlay path. Both are valid; the plan assumes **one cluster per
environment** (see `docs/plan-ecommerce-k8s.md`), so each Argo CD instance
deploys exactly one overlay.

### Prefer `kustomize build` over `kubectl apply -k`

Argo CD renders `kustomize build <path>` server-side. Local verification:

```bash
kustomize build cluster/overlays/dev      # must render cleanly
kustomize build cluster/overlays/staging
kustomize build cluster/overlays/prod
kubectl apply -k cluster/overlays/dev     # optional, direct apply for testing
```

### Why direct `kubectl apply -k` may be rejected (Kyverno)

`cluster/base/kyverno` ships **two Enforce policies** that DO apply to the
`ecommerce` namespace:

- `disallow-latest-tag` — images must NOT use the literal `latest` tag.
- `require-labels` — every Pod must carry `app:` and `env:` labels.

**This is intentional, not a bug.** The service base manifests use
`acr.azurecr.io/<svc>:latest` as a dev-friendly default, but the **real
deployment flow never applies `latest`**: the CD pipeline
(`.github/workflows/cd.yml`) rewrites each overlay's `newTag` to the commit SHA
and Argo CD/`kubectl` apply that rendered output. `latest` is only referenced
before CD runs.

Consequences:

- ✅ `require-labels` — all services set `app: <svc>` and `env:` labels in the
  Pod template (verify with `kubectl apply -k` passthrough). The `env:` VALUE
  in base is always `production` — see the cosmetic gap note below.
- ⛔ If you run `kubectl apply -k cluster/overlays/dev` **before** CD has
  rewritten the tags, Pod creation will be **rejected** by
  `disallow-latest-tag`. That is Kyverno doing its job.
- 🔧 To apply manually, either run the CD flow first, or pin an explicit tag in
  the overlay before applying, e.g.:

```bash
# after a real image push
kustomize edit set image acr.azurecr.io/catalog-svc=acr.azurecr.io/catalog-svc:1.0.0
```

## What the overlays deliberately do NOT do

- **Patch Helm `values.yaml` files** (ingress-nginx, kube-prometheus-stack) —
  those are chart inputs consumed by Argo CD multi-source or helmfile, not
  Kubernetes resources; kustomize cannot patch them sensibly. Env-specific chart
  values belong in the Argo CD Application manifests or a per-env values repo.
- **Set service replica counts** — services arrive in a later phase. The KEDA
  ScaledObject example from base already covers autoscaling (`min=1, max=10`);
  prod baselines (e.g. `replicas: 3`) belong in each service's own manifests.
- **Patch `.github/` or terraform** — keep infra config with its owning phase.
- **Patch the `env:` pod template label** — base Deployments hardcode
  `env: production` in the Pod template and the overlays do not patch this
  label. The Kyverno `require-labels` policy only requires the label to be
  present (value doesn't matter), so Pods are not rejected. For the correct
  per-environment value (e.g. `env: dev`) in Pod labels and Prometheus metrics,
  a per-service overlay patch is a later student task.

## Placeholder audit

| File | Placeholder | Replace with |
|---|---|---|
| `dev/issuer-default.yaml` | `admin@ecommerce.dev` | your Let's Encrypt email |
| `staging/issuer-default.yaml` | `admin@ecommerce.staging` | your Let's Encrypt email |
| `prod/issuer-default.yaml` | `admin@ecommerce.com` | your Let's Encrypt email |
| `*/patches/secret-store-patch.yaml` | `kv-{env}-ecommerce` vault name | `terraform output keyvault_name` |
| `*/env-config.yaml` | `pg-*`, `redis-*`, `ns-*` FQDNs | terraform outputs (table above) |