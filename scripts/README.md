# scripts/ — Operational helpers (Phase 9)

Local utilities for the e-commerce platform: cluster bootstrap, environment
deploys, Azure login, Argo CD operations and manifest/test validation. The
**primary path** for this course is PowerShell on Windows (all `.ps1` files);
the `.sh` twins are Linux/macOS equivalents for anyone working outside Windows.

> Everything here is a **convenience wrapper around commands already documented
> in the repo** (`cluster/base/README.md`, `cluster/overlays/README.md`,
> `.github/workflows/*.yml`). The real deployment pipeline remains GitHub
> Actions (`cd.yml`); the scripts let a human do the same steps by hand.

## Script matrix

| Script | What it does | Requires |
|---|---|---|
| `bootstrap/minikube-start.ps1` / `.sh` | Start Minikube (profile `ecommerce`, docker driver, cpus/memory), enable `ingress` + `metallb` addons, wait for cluster + ingress-nginx Ready | minikube, kubectl, Docker |
| `bootstrap/kind-start.ps1` / `.sh` | Create a Kind cluster (`ecommerce`) with the ingress-ready node + host port 80/443 mappings, install the official kind ingress-nginx manifest, wait for it | kind, kubectl, Docker |
| `bootstrap/kustomize-render.ps1` / `.sh` | Render `cluster/overlays/<env>` to a single multi-doc YAML (`kustomize build`, `kubectl kustomize` fallback) for inspection — same render Argo CD does | kubectl (kustomize optional) |
| `deploy/apply-overlay.ps1` / `.sh` | Pre-checks (kubectl present, overlay exists, kustomize renders clean) then `kubectl apply -k cluster/overlays/<env>`; warns about the Kyverno `disallow-latest-tag` gate | kubectl, (kustomize optional) |
| `deploy/bootstrap-argocd.ps1` / `.sh` | Install Argo CD (`cluster/base/argocd/install`, official-manifest fallback), wait for `argocd-server`, print `argocd-initial-admin-secret`, open port-forward to the UI, print the remaining bootstrap steps | kubectl |
| `deploy/sync-argocd-app.ps1` / `.sh` | `argocd app sync <name> --prune` + status — manual twin of the CD Argo CD job | argocd CLI |
| `azure/az-login.ps1` / `.sh` | `az login` → `az account set` (subscription from param) → `az aks get-credentials`; optional `-UseTerraformOutputs` reads RG/cluster name from `terraform output` | az CLI (+ kubectl, optional terraform) |
| `azure/deploy-cd-manual.ps1` | Manual fallback for `cd.yml`: `az acr login`, `docker build`+`push` per service, rewrite `newTag` in the overlay by matching `newName == <acr>.azurecr.io/<svc>` (same contract as the yq step in cd.yml) | az CLI, docker, git |
| `tests/run-unit-tests.ps1` / `.sh` | `mvn -B test` per service (`mvnw` fallback), per-service pass/fail report, non-zero exit on failure | Java 17, Maven |
| `tests/validate-manifests.ps1` / `.sh` | YAML parse check (all `.yaml`/`.yml` via python) + `kustomize build` across `cluster/` + `services/` + `observability/` + `security/` | python + pyyaml, (kustomize or kubectl optional) |

## Requirements

| Tool | Used by | Install (Windows) |
|---|---|---|
| `kubectl` | all deploy/apply/render scripts | `winget install Kubernetes.kubectl` or via Docker Desktop |
| `minikube` | minikube-start | `winget install minikube` |
| `kind` | kind-start | `winget install kind` (or `go install sigs.k8s.io/kind@latest`) |
| `kustomize` | render/apply/validate (optional — `kubectl kustomize` fallback) | `winget install kustomize` |
| `argocd` (CLI) | sync-argocd-app | see <https://argo-cd.readthedocs.io/en/stable/cli_installation/> |
| `az` (Azure CLI) | az-login, deploy-cd-manual | `winget install Microsoft.AzureCLI` |
| `docker` | minikube/kind drivers, deploy-cd-manual | Docker Desktop |
| `python` + `pyyaml` | tests/ python checks | `python -m pip install pyyaml` |
| Java 17 + Maven | run-unit-tests | `winget install Oracle.JDK.17` / Maven zip on PATH (or add `mvnw`) |
| `terraform` | az-login `-UseTerraformOutputs` (optional) | `winget install Hashicorp.Terraform` |

## Environment consistent paths

All scripts resolve the repo root relative to their own location
(`$PSScriptRoot` / `$(dirname "$0")`), so they work from **any** working
directory. Rendered output goes to the OS temp dir by default — never into the
repo (`build/` and similar are not git-ignored on purpose).

## Placeholders you MUST replace

| Placeholder | Where | What to put |
|---|---|---|
| `acr.azurecr.io` (scripts use `-Acr acr` by default) | `deploy-cd-manual.ps1 -Acr` and every `cluster/overlays/*/kustomization.yaml` `newName` | your ACR login server (match the overlay's `newName`) |
| `rg-ecommerce-<env>-PLACEHOLDER` / `aks-ecommerce-<env>-PLACEHOLDER` | `az-login.ps1/.sh` defaults | real RG / cluster name — or use `-UseTerraformOutputs` |
| `-SubscriptionId <ID>` | `az-login.ps1/.sh` | `az account list -o table` output |
| `api.<domain>` (in manifests) | not a script placeholder — ingress host | your DNS zone (external-dns) |
| `argocd-initial-admin-secret` password | printed by `bootstrap-argocd` | change it on first login |

## Notes

- **PowerShell first**: the `.ps1` files are the reference implementations
  (Windows course). The `.sh` files mirror them 1:1 with bash 3.2+ syntax —
  report drift as a bug.
- **No secrets**: scripts never read or write credentials; they call `az login`
  / `docker login` / `argocd login` interactively. GitHub secret names from the
  cloud pipeline (`ACR_PASSWORD`, `ARGOCD_AUTH_PASSWORD`, …) do NOT belong in
  any of these scripts.
- **Kyverno gate**: `cluster/base/kyverno` enforces `disallow-latest-tag` in
  `ecommerce`. Manual applies with `:latest` images are rejected — run
  `deploy-cd-manual.ps1` (or the CD pipeline) to pin real tags first.
- **CI wiring**: `tests/validate-manifests.ps1` and the python checks in
  `tests/manifests/` overlap with the `ci.yml` lint jobs. Nothing here edits
  `.github/` — wiring these into CI is a later, deliberate change.