# cluster/base — Platform GitOps bootstrap

This directory contains the **platform layer** of the e-commerce cluster (Phase 3 of
[docs/plan-ecommerce-k8s.md](../../docs/plan-ecommerce-k8s.md)). It brings up the
shared infrastructure every service needs (Phase 4 creates `services/` on top of it).

## Architecture

GitOps controller: **Argo CD** (app-of-apps pattern), chosen because the Phase 1
CI/CD (`GITOPS == 'argocd'`) already supports it — `cluster/base` stays consistent
with `.github/workflows/cd.yml`.

```
cluster/base/
├─ namespaces/            # ecommerce, platform, observability, security, data, argocd
├─ argocd/
│   ├─ namespace.yaml           # documented for clarity only (namespace lives in namespaces.yaml)
│   ├─ install/                 # ONE-TIME Argo CD bootstrap (remote kustomize base)
│   ├─ app-of-apps.yaml         # root Application "ecommerce-apps"
│   └─ applications/            # 7 child Applications (Helm charts + git paths)
├─ cert-manager/          # ClusterIssuers (selfsigned + Let's Encrypt), cert example
├─ ingress-nginx/         # Helm values + default IngressClass
├─ external-dns/          # plain manifest → Azure DNS provider
├─ external-secrets/      # ClusterSecretStore (Azure Key Vault) + ExternalSecret example
├─ kyverno/policies/      # 3 ClusterPolicies (labels, resources, latest-tag)
├─ keda/                  # ScaledObject example (Event Hubs Kafka)
├─ monitoring/            # kube-prometheus-stack values, OTel collector, PrometheusRule
├─ kustomization.yaml     # aggregates everything except the Argo CD install
├─ helmfile.yaml          # ALTERNATIVE bootstrap (non-GitOps, documented only)
└─ README.md
```

Model: one root Application (`ecommerce-apps`) syncs the children in
`argocd/applications/`. The Helm-heavy components (ingress-nginx, cert-manager,
external-secrets, kyverno, keda, kube-prometheus-stack) are chart-based Applications;
external-dns is a git-path Application because the plain manifest and chart would
conflict (two Deployments named `external-dns` in the same namespace). CRD-backed
resources (ClusterPolicy, ClusterIssuer, ClusterSecretStore, PrometheusRule,
ScaledObject) are applied by the top-level kustomization *after* the charts that
provide their CRDs are healthy — see the bootstrap order below.

## Bootstrap (fresh AKS cluster)

Argo CD installs CRDs asynchronously (cert-manager, kyverno, external-secrets,
keda, kube-prometheus-stack), and several cluster-scoped resources depend on
them (`no matches for kind: ClusterPolicy` is the symptom of missing ordering).
The sequence below makes that explicit:

```bash
# 1. Argo CD itself — namespace, CRDs, controllers (remote kustomize base).
#    If your kubectl rejects the remote base, fall back to the official
#    manifests:
#    kubectl apply -f https://raw.githubusercontent.com/argoproj/argo-cd/v2.12.3/manifests/install.yaml
kubectl apply -k cluster/base/argocd/install
kubectl -n argocd wait --for=condition=available deploy/argocd-server --timeout=300s

# 2. Bootstrap the Applications (root + 7 children).
kubectl apply -f cluster/base/argocd/app-of-apps.yaml
kubectl apply -f cluster/base/argocd/applications/

# 3. Wait until the chart-based Applications are Healthy — the resources in
#    step 4 need their CRDs (ClusterIssuer, ClusterPolicy, ClusterSecretStore,
#    PrometheusRule, ScaledObject).
kubectl get applications -n argocd   # repeat until all are Healthy

# 4. Everything else (namespaces already exist — apply is idempotent, and the
#    Applications from step 2 get adopted/reconciled by the root).
kubectl apply -k cluster/base
```

From here on, Argo CD is the source of truth: edits to `cluster/base` are synced
automatically (`automated: prune + selfHeal` on every Application).

Sanity checks:

```bash
kustomize build cluster/base          # must render cleanly at any time
kubectl get applications -n argocd    # should all be Healthy / Synced
kubectl get clusterissuer             # selfsigned, letsencrypt-staging, letsencrypt-prod
kubectl get clusterpolicy             # require-labels, require-resources, disallow-latest-tag
```

### Argo CD UI

```bash
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
kubectl port-forward -n argocd svc/argocd-server 8080:443
# open https://localhost:8080 → user: admin
```

## Alternative: helmfile (non-GitOps)

`helmfile.yaml` installs the same components (plus Argo CD) without Argo CD
managing them — it is the comparison/fallback path, not the primary one:

```bash
helmfile apply
kubectl apply -k cluster/base    # CRD-backed resources only (skipping the
                                 # Application CRs is fine — they are Argo CD CRs)
```

Do NOT mix helmfile with the Argo CD Applications for the same component:
external-dns is explicitly called out (chart vs git-path manifest).

## What you MUST fill in (placeholders)

| Where | Placeholder | Replace with |
|---|---|---|
| every Argo CD Application | `https://github.com/<org>/<repo>.git` | your repo URL (and `targetRevision` if not `main`) |
| `argocd/install/kustomization.yaml` | `?ref=v2.12.3` | your chosen Argo CD release tag |
| `cert-manager/cluster-issuer.yaml` | `<email>` | your Let's Encrypt account email |
| `cert-manager/certificates.yaml` | `<domain>` | your real domain (uncomment first) |
| `external-dns/external-dns-azure.yaml` | `<domain>`, `<subscription-id>`, `<dns-zone-resource-group>`, `<service-principal-client-id>`, `<service-principal-client-secret>` | Azure DNS zone + service principal; `AZURE_TENANT_ID` |
| `external-secrets/cluster-secret-store.yaml` | `<kv-name>`, `<identity-client-id>`, `AZURE_TENANT_ID` | your Key Vault (infra/terraform output) and the ESO managed identity |
| `external-secrets/example-external-secret.yaml` | vault secret `db-password` | any vault secret to expose as `ecommerce-db-credentials` |
| `keda/scaledobject-example.yaml` | `<event-hubs-ns>` + env names `EVENT_HUBS_SASL_USERNAME/PASSWORD` in the `order-worker` Deployment | your Event Hubs namespace and the deployment env |
| `monitoring/kube-prometheus-stack/values.yaml` | `<grafana-admin-password>` | a real admin password (or adminPasswordSecret via external-secrets) |
| all `targetRevision` / `version:` fields | `# bump me` comments | check the chart repositories for latest stable versions |

## Notes and known trade-offs

- **Bootstrap ordering**: step 4 depends on charts from step 3 having installed
  their CRDs; the README order avoids `no matches for kind` errors. Inside Argo
  CD, the chart apps are independent — the true dependency chain is enforced by
  the kubectl sequence.
- **Kyverno policies target app namespaces**: `cert-manager`, `ingress-nginx`,
  `external-dns`, `external-secrets`, `keda`, `observability`, `platform`,
  `argocd`, `kyverno`, `kube-system` are excluded via `namespaceSelector`
  (charts set `app.kubernetes.io/name`, not `app`). `require-labels` and
  `disallow-latest-tag` run in `Enforce`; `require-resources` runs in `Audit`
  until every workload declares resources (flip it when ready).
- **OTel collector** is plain-manifest (no child Application): it is applied by
  the bootstrap kustomization and only drift-managed at the root path. Promoting
  it to an Application is a Phase 4 concern along with `cluster/overlays/`.
- **Argo CD is installed by bootstrap only** — it does not manage itself
  (self-management would need a bootstrap step before Argo CD exists). Step 1 is
  that one-time step.
- **`certificates.yaml` is commented-only** by design: wildcard certificates need
  a DNS-01 solver (Azure DNS), which the current issuers do not provide. Uncomment
  the file, switch the issuer to dns01/azuredns, and add it to `kustomization.yaml`.