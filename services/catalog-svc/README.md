# catalog-svc — Product catalog REST API

Product CRUD backed by Spring Data JPA. Demo profile uses in-memory H2 and
seeds 4 products on startup; the deployment can flip to Azure managed Postgres
without code changes.

## Endpoints

| Method | Path | Behavior |
|---|---|---|
| GET | `/api/catalog/products` | list all (optional `?category=` filter) |
| GET | `/api/catalog/products/{id}` | single product (404 if unknown) |
| POST | `/api/catalog/products` | create (returns 201 + entity with id) |
| PUT | `/api/catalog/products/{id}` | full update (404 if unknown) |
| DELETE | `/api/catalog/products/{id}` | delete (204; 404 if unknown) |

## Run locally

```bash
cd services/catalog-svc
mvn spring-boot:run          # starts on :8081, seeds demo data
```

```bash
curl localhost:8081/api/catalog/products
curl -X POST localhost:8081/api/catalog/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Webcam HD-720","description":"1080p","price":59.99,"stock":25,"category":"Accessories"}'
```

## Switching to Azure managed Postgres

`k8s/base/configmap.yaml` (`catalog-svc-config`) holds the datasource defaults.
The `ecommerce-env-config` ConfigMap (from `cluster/overlays/*`) already carries
`DB_HOST` / `DB_PORT` / `DB_NAME`. Override the `SPRING_DATASOURCE_*` keys per
the commented instructions in that file, and put the password in Azure Key Vault
(external-secrets pattern — see `cluster/base/external-secrets/`).

## Kubernetes

| Manifest | Purpose |
|---|---|
| `k8s/base/deployment.yaml` | 1 replica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `catalog-svc:8081` |
| `k8s/base/configmap.yaml` | datasource defaults + config/eureka URLs |
| `k8s/base/hpa.yaml` | CPU 70%, 1→5 replicas |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress from api-gateway; egress DNS + peers + 5432/443 |
| `k8s/overlays/prod/` | 3 replicas, bigger resources |

## Placeholders to replace

1. `acr.azurecr.io` → your ACR login server.
2. Datasource → Azure Postgres (see configmap comments) + DB credentials in Key Vault.