# config-service (Spring Cloud Config Server)

Centralised configuration for every service. Serves YAML from a **native
filesystem** backend (`src/main/resources/config/`) — no Git server needed to
run. Start it, and clients fetch `catalog-svc.yml`, `cart-svc.yml` etc. from
`http://config-service:8888`.

## Run locally

```bash
cd services/config-service
mvn spring-boot:run
```

- Config endpoint: `http://localhost:8888/<service-name>/demo`
- Health: `http://localhost:8888/actuator/health`
- Prometheus metrics: `http://localhost:8888/actuator/prometheus`
- Env (debug which property wins): `http://localhost:8888/actuator/env`

## Switching to a Git backend (production pattern)

In `src/main/resources/application.yml`, replace the `native` block with:

```yaml
spring:
  cloud:
    config:
      server:
        git:
          uri: https://github.com/<you>/<config-repo>.git
          default-label: main
          clone-on-start: true
```

Clients are unaffected — Spring Cloud Config clients don't care about the backend.

## Kubernetes

| Manifest | Purpose |
|---|---|
| `k8s/base/deployment.yaml` | 1 replica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `config-service:8888` |
| `k8s/base/hpa.yaml` | CPU 70%, 1→5 replicas |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress from all `ecommerce-platform` pods; egress DNS + 443 |
| `k8s/overlays/prod/` | 2 replicas, bigger resources, ACR image |

## Placeholders to replace

1. `acr.azurecr.io` → your ACR login server (in `k8s/base/kustomization.yaml`,
   `k8s/overlays/prod/kustomization.yaml` and `k8s/base/deployment.yaml`) —
   the CD workflow tags images by matching `newName` in the overlay.
2. Nothing else — the service itself has no external dependencies.

> NOTE: Kyverno's `disallow-latest-tag` policy (Enforce) means the `:latest`
> placeholder image is rejected until the overlay's `newTag` carries a commit
> SHA. Run the CD pipeline once, or set `newTag` manually before applying.