# tests/ — Integration-oriented test assets (Phase 9)

This directory holds **repo-level contract checks** that complement — but do
NOT replace — the per-service JUnit tests (`services/<svc>/src/test/`, 17
Spring Boot suites, e.g. `CatalogServiceApplicationTests`). The JUnit tests
prove each service works; these checks prove the **manifests and wiring**
around the services keep matching the repo's contracts (services/README.md,
cd.yml, cluster/overlays wiring, observability/ + security/ integration).

## What each check verifies

| Check | Command | Verifies |
|---|---|---|
| `manifests/test-yaml-parse.py` | `python tests\manifests\test-yaml-parse.py` | Every `.yaml`/`.yml` in the repo is valid YAML with no duplicate mapping keys. |
| `manifests/test-kustomize-build.py` | `python tests\manifests\test-kustomize-build.py` | `kustomize build` (or `kubectl kustomize` fallback) renders cleanly for `cluster/base`, `cluster/overlays/{dev,staging,prod}`, `observability`, `security`, and every `services/*/k8s/base` + `services/*/k8s/overlays/prod`. Skips with a warning if neither tool is present. |
| `manifests/test-service-contract.py` | `python tests\manifests\test-service-contract.py` | The 17-service layout contract: `Dockerfile`, the seven `k8s/base` manifests, `images[].name == newName == acr.azurecr.io/<svc>`, pod labels `app` + `env`, `securityContext.runAsNonRoot` + `seccompProfile: RuntimeDefault`, an `/actuator/health` probe, and a `Service` port named `http`. |
| `manifests/test-overlay-wiring.py` | `python tests\manifests\test-overlay-wiring.py` | `cluster/overlays/{dev,staging,prod}`: every `resources[]` entry resolves, `images[]` covers all 17 services with the CD `newName` contract, dev/staging point at `k8s/base` while prod points at `k8s/overlays/prod`, and the Phase 8 `observability` + `security` layers are wired in. |

The checks exit non-zero on failure and are safe to run on Windows (pathlib +
UTF-8 everywhere). Only dependency: PyYAML (`python -m pip install pyyaml`);
if it is missing, the scripts print an install hint.

## How to run

```powershell
# From the repo root (paths are resolved relative to the script location,
# so any working directory works):
python tests\manifests\test-yaml-parse.py
python tests\manifests\test-kustomize-build.py
python tests\manifests\test-service-contract.py
python tests\manifests\test-overlay-wiring.py

# Or all of it plus the kustomize tree check in one go:
.\scripts\tests\validate-manifests.ps1            # PowerShell (Windows)
./scripts/tests/validate-manifests.sh             # bash (Linux/macOS)
```

## Why these contracts exist (Phase 8 tie-in)

- **Observability**: `test-service-contract.py` checks the exact properties the
  ServiceMonitors in `observability/servicemonitors/` depend on — pod label
  `app: <svc>` and a Service port named `http`. `test-overlay-wiring.py`
  confirms every env actually loads the `observability/` layer. Without those,
  Prometheus (kube-prometheus-stack) discovers nothing and the SLI dashboards
  (`observability/dashboards/service-sli.json`) stay empty.
- **Security**: the `securityContext` checks (runAsNonRoot, `seccompProfile:
  RuntimeDefault`) are the PSA-restricted gate from
  `security/pod-security/pod-security-labels.yaml` — the known blocker called
  out in `security/README.md`. The `networkpolicy.yaml` presence check is part
  of the default-deny model: a service without a per-service NetworkPolicy is
  cut off completely by `security/network-policies/default-deny-all.yaml`.
- **CD contract**: `images[].newName == acr.azurecr.io/<svc>` is exactly the
  selector `.github/workflows/cd.yml` uses with `yq` to rewrite `newTag`. If a
  service's `newName` drifts, the pipeline silently stops bumping its images.

## CI integration note

`.github/workflows/ci.yml` already runs a kustomize build over
`cluster/overlays/*` and Maven tests per service. Wiring `tests/manifests/*.py`
into CI (e.g. a `test-manifests` job running the four checks) would close the
gap between "renders" and "contracts" — **deliberately not done here**: the
brief scopes this phase to local + test assets, and `.github/` is read-only for
this phase. When you do wire it, use `pip install pyyaml` or
`actions/setup-python` + a requirements file.

## Scope note

These checks are static/structural. True integration (service-to-service over
the cluster, e2e flows through the api-gateway, load tests) belongs in a later
phase — the directory layout (`tests/manifests/`) leaves room for
`tests/e2e/` and `tests/load/` next to it.