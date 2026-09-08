# Runbook: Deploy end-to-end (happy path)

From a clean laptop to a working environment. Each numbered step names the
exact script/config that does it, so this doubles as the exam demo script.
Pick your target: **local (minikube/kind)**, **dev/staging**, or **prod**
(cost-bearing, needs Azure).

## Prerequisites (any path)

1. **Tooling** — `scripts/README.md` has the full matrix with `winget`
   lines: `git`, `kubectl`, `python` (3.12+ with `pip install pyyaml`),
   `kustomize` or `kubectl` (kustomize fallback), `docker`, `mvn` (only for
   `run-unit-tests.ps1`), `az` + `terraform` (only for cloud path),
   `minikube`/`kind` + `argocd` CLI (`winget install Argoproj.ArgoCD`).
2. **Repo** cloned; placeholders documented in `scripts/README.md`:
   `acr.azurecr.io`, `<acr>`, `rg-…`, `aks-…`, `api.<domain>`,
   `<kv-name>`, subscription/tfvars values.
3. **Sanity**: run the four checks in `tests/README.md` — they must all
   pass before touching a cluster:
   ```bash
   python tests/manifests/test-yaml-parse.py
   python tests/manifests/test-kustomize-build.py     # needs kustomize/kubectl
   python tests/manifests/test-service-contract.py
   python tests/manifests/test-overlay-wiring.py
   ```

## Path A — Local cluster (minikube or kind)

```powershell
# 1. Cluster (choose ONE):
.\scripts\bootstrap\minikube-start.ps1        # or
.\scripts\bootstrap\kind-start.ps1

# 2. Render + validate the overlay you want (kustomize without a binary? kubectl kustomize fallback):
.\scripts\bootstrap\kustomize-render.ps1 -Environment dev -OutDir "$env:TEMP\ecommerce-render"

# 3. Apply the cluster/platform layer (namespaces, issuers, observability, security):
kubectl apply -k cluster/base

# 4. Option A — Argo CD GitOps path:
.\scripts\deploy\bootstrap-argocd.ps1          # installs Argo CD + prints admin password
.\scripts\deploy\sync-argocd-app.ps1           # syncs ecommerce-apps (or -AppName <app>)

# 4'. Option B — direct apply (still rejected by Kyverno for :latest images — see below):
.\scripts\deploy\apply-overlay.ps1 -Environment dev -DryRun

# 5. Verify:
kubectl get pods -n ecommerce
kubectl get svc -n ecommerce api-gateway
kubectl port-forward -n ecommerce svc/api-gateway 8080:8080 &  # or ingress host
```

> On local clusters there is no ACR **and no Azure DNS** — images stay at
> the documented placeholder values, so real Pods won't start
> (ImagePullBackOff, see troubleshooting runbook). For a *containers-up*
> local demo you must either use images you built (`docker build` +
> `kind load docker-image` / minikube `--image`), or run the cloud path
> below. Kyverno `disallow-latest-tag` will also reject `:latest` — pin a
> tag (a local build tag works; that is exactly what `cd.yml` does in
> cloud).

## Path B — Azure dev/staging (cloud, low cost)

```powershell
# 0. Azure auth + context (accepts terraform outputs or fallback names):
.\scripts\azure\az-login.ps1 -Environment dev

# 1. Infra: cluster + ACR + managed data (from infra/terraform/envs/dev):
terraform -chdir=infra/terraform/envs/dev init
terraform -chdir=infra/terraform/envs/dev apply -auto-approve

# 2. Point the ConfigMap/ExternalSecret placeholders at real values
#    (DB_HOST, REDIS_HOST, KAFKA_BOOTSTRAP, kv-<env> …) — done by tfvars +
#    secret-store patch (ADR-0006/0007). Commit the resulting overlay.

# 3. Push images and let CD rewrite newTag (this is what GitHub CD does):
.\scripts\azure\deploy-cd-manual.ps1 -Environment dev -Service catalog-svc -Acr <acr>
#    (no -Service = all 17; prints pending git commit commands — commit the
#     newTag rewrite so Argo CD converges)

# 4. Bootstrap Argo CD and sync:
.\scripts\deploy\bootstrap-argocd.ps1
.\scripts\deploy\sync-argocd-app.ps1 -AppName ecommerce-dev -Server <argo-cd-server>

# 5. Check TLS + dashboards (ADR-0008):
kubectl get certificate -n ecommerce          # ready after external-dns propagated
kubectl -n observability port-forward svc/kube-prometheus-stack-grafana 3000:80
```

## Path C — Prod (raise SKUs, real DNS)

1. Same as Path B with `-Environment prod`; prod uses each service's
   `k8s/overlays/prod` patch (more replicas/resources) — validated by
   `test-overlay-wiring.py`.
2. Prod issuer is Let's Encrypt **prod** — see ADR-0001/0003 and the
   `issuer-default.yaml` overlay per env.
3. Assert: `kubectl apply -k cluster/base` will **not** deploy services —
   Argo CD owns that (ADR-0002). Never kubectl-apply the service layer on
   prod manually.

## Post-deploy verification checklist (any path)

- [ ] `kubectl get pods -n ecommerce` — all Running/Ready (CrashLoop →
      troubleshooting runbook)
- [ ] HPA: `kubectl get hpa -n ecommerce` — not `<unknown>`
- [ ] ServiceMonitors wired: `kubectl get servicemonitor -n observability`
      — 17 entries, Prometheus targets UP
- [ ] Dashboards: Grafana sidecar ConfigMaps loaded, `service-sli` shows data
- [ ] Certificate + ingress host reachable over HTTPS
- [ ] Eureka dashboard lists all replicas (`discovery-service:8761`)
- [ ] `argocd app get ecommerce-<env>` — Synced, Healthy

See `runbook-index.md` for the other runbooks.