# discovery-service (Eureka Server)

Service registry for the whole platform. Every Spring Cloud service registers
here and the api-gateway resolves `lb://` route targets through it.

## Run locally

```bash
cd services/discovery-service
mvn spring-boot:run
```

- Eureka dashboard: `http://localhost:8761/`
- Health: `http://localhost:8761/actuator/health`
- Prometheus metrics: `http://localhost:8761/actuator/prometheus`

## HA note (prod)

Real deployments run **3 Eureka replicas** so the registry survives node loss and
replicates peer state. For the university project, 1 replica (dev) / 2 replicas
(prod overlay) is acceptable. To go full HA:

1. `register-with-eureka: true`, `fetch-registry: true`
2. `defaultZone` pointing at every peer (see prod overlay comment)
3. A `StatefulSet` or stable network IDs per replica (peer DNS names)

## Kubernetes

| Manifest | Purpose |
|---|---|
| `k8s/base/deployment.yaml` | 1 replica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `discovery-service:8761` |
| `k8s/base/hpa.yaml` | CPU 70%, 1→5 replicas |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress from all `ecommerce-platform` pods; egress DNS + intra-namespace + 443 |
| `k8s/overlays/prod/` | 2 replicas, bigger resources |

## Placeholders to replace

1. `acr.azurecr.io` → your ACR login server (`k8s/base/kustomization.yaml`,
   `k8s/overlays/prod/kustomization.yaml`, `k8s/base/deployment.yaml`).