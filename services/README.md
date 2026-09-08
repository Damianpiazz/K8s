# Services — E-commerce microservices

This directory holds the microservices that make up the e-commerce backend. Each
service follows the **exact same layout** so the CI/CD pipeline and GitOps
manifests stay uniform.

## Layout

```
services/<svc>/
├─ src/                           # minimal-but-functional source and config
│   └─ resources/application.yml  # or application.yaml
├─ Dockerfile                     # multi-stage maven build → JRE 17 runtime (temurin)
├─ .dockerignore
├─ k8s/
│   ├─ base/                      # environment-agnostic manifests (namespace ecommerce)
│   │   ├─ deployment.yaml
│   │   ├─ service.yaml
│   │   ├─ hpa.yaml
│   │   ├─ pdb.yaml
│   │   ├─ serviceaccount.yaml
│   │   ├─ networkpolicy.yaml
│   │   ├─ configmap.yaml         # only when the service needs env defaults
│   │   └─ kustomization.yaml
│   └─ overlays/
│       └─ prod/                  # thin prod overlay
│           ├─ kustomization.yaml
│           └─ patches/
│               └─ deployment-patch.yaml
```

The manifests are **not** wired into `cluster/base` (top-level): the platform is
deployed via Argo CD relying on a `services-aggregator` app, or directly with
`kubectl apply -k services/<svc>/k8s/base`. The CD workflow (`GITOPS=kustomize`)
bumps image tags by matching `newName` in the kustomization it patches.

## Wiring the services into an environment overlay (one-time, per env)

The CD workflow (`GITOPS=kustomize`, `.github/workflows/cd.yml`) patches
`cluster/overlays/{env}/kustomization.yaml`, replacing `newTag` for every image
whose `newName` equals `<ACR_NAME>.azurecr.io/<svc>` — **that file must include
the service overlays**. In a later phase, add to each
`cluster/overlays/{dev,staging,prod}/kustomization.yaml`:

```yaml
resources:
  # ...existing cluster/base + env-config resources...
  - ../../../services/api-gateway/k8s/overlays/prod
  - ../../../services/catalog-svc/k8s/overlays/prod
  - ../../../services/cart-svc/k8s/overlays/prod
  - ../../../services/auth/k8s/overlays/prod
  # config-service / discovery-service are infrastructure — deploy them first

images:                       # mirrors the CD contract (newName must match ACR_NAME)
  - name: acr.azurecr.io/api-gateway
    newName: acr.azurecr.io/api-gateway
    newTag: latest            # CD rewrites this to the commit SHA
  - name: acr.azurecr.io/catalog-svc
    newName: acr.azurecr.io/catalog-svc
    newTag: latest
  # ... one entry per service ...
```

> Alternatively (Argo CD mode), point an Application at
> `services/<svc>/k8s/overlays/prod` per service, or a single aggregator
> Application at a future `cluster/overlays/{env}` that includes these paths.

## CD matrix note — config-service & discovery-service

The CD build matrix in `.github/workflows/cd.yml` covers `api-gateway`, `auth`,
`catalog-svc`, `cart-svc` and the later-phase services, but **not** the two
infrastructure services. Options:

- Push them manually once: `docker build -t <acr>.azurecr.io/config-service:latest services/config-service && docker push …`
- Or add `config-service` / `discovery-service` to the two matrices in
  `cd.yml` (`build-images`) — a 2-line change in a later phase.

## Placeholders you MUST replace

| Placeholder | Where | What to put |
|---|---|---|
| `acr.azurecr.io` | every `k8s/**/kustomization.yaml` `images[].newName` and `deployment.yaml` image | your ACR login server, e.g. `myacr.azurecr.io` (set `ACR_NAME=myacr` in GitHub Actions and update `newName` once) |
| `api.<domain>` | `api-gateway/k8s/base/ingress.yaml` host + `spec.tls[].hosts` | your public DNS zone managed by external-dns |
| Key Vault name | `cluster/overlays/*/patches/secret-store-patch.yaml` | the Azure Key Vault holding secrets (managed by infra/terraform) |
| Keycloak admin secret | `auth` deployment env (`KEYCLOAK_ADMIN_PASSWORD`) | a real Secret (external-secrets → Azure Key Vault preferred) |

## Common conventions

- Java 17, Spring Boot **3.3.x**, Spring Cloud **2023.0.x** (BOM), Maven group `com.ecommerce`.
- Every service exposes `/actuator/health`, `/actuator/info`, `/actuator/prometheus`, `/actuator/metrics`.
- Every service that needs discovery imports `spring-cloud-starter-netflix-eureka-client`.
- Docker images: `acr.azurecr.io/<svc>:latest` in `base`, and each overlay declares an
  `images:` entry so CD tags `newTag` with the commit SHA.

## What the CI expects

`.github/workflows/ci.yml` builds `services/<name>/Dockerfile` and runs
`mvn -B test --file services/<name>/pom.xml` for every name in its matrix that has
a Dockerfile/pom. Keep a `pom.xml` and tests that pass without external
infrastructure (in-memory/H2 only — no real DB/Redis/Kafka in unit tests).