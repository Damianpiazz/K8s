# order-svc — Order management REST API

Order CRUD backed by Spring Data JPA. Demo profile uses in-memory H2 and seeds
2 sample orders on startup; the deployment can flip to Azure managed Postgres
without code changes. Config keys align with `config-service`
`order-svc.yml` (`order.max-items-per-order`).

## Endpoints

| Method | Path | Behavior |
|---|---|---|
| POST | `/api/orders` | create: `{customerId, items:[{productId, quantity, price}], total}` → 201 + order (status `CREATED`) |
| GET | `/api/orders` | list all orders |
| GET | `/api/orders/{id}` | single order (404 if unknown) |
| GET | `/api/orders/customer/{customerId}` | orders of one customer (newest first) |

Validation: at least one item, positive quantities, ≤ `order.max-items-per-order` (50).

## Run locally

```bash
cd services/order-svc
mvn spring-boot:run          # starts on :8084, seeds sample orders
```

```bash
curl localhost:8084/api/orders
curl localhost:8084/api/orders/customer/cust-42
curl -X POST localhost:8084/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"cust-1","items":[{"productId":1,"quantity":2,"price":29.99}],"total":59.98}'
```

## Switching to Azure managed Postgres

`k8s/base/configmap.yaml` (`order-svc-config`) holds the datasource defaults.
The `ecommerce-env-config` ConfigMap (from `cluster/overlays/*`) already carries
`DB_HOST` / `DB_PORT` / `DB_NAME`. Override the `SPRING_DATASOURCE_*` keys per
the commented instructions in that file, and put the password in Azure Key Vault
(external-secrets pattern — see `cluster/base/external-secrets/`).

## Kubernetes

| Manifest | Purpose |
|---|---|
| `k8s/base/deployment.yaml` | 1 replica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `order-svc:8084` |
| `k8s/base/configmap.yaml` | datasource defaults + config/eureka URLs |
| `k8s/base/hpa.yaml` | CPU 70%, 1→5 replicas |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress from api-gateway; egress DNS + peers + 5432/443 |
| `k8s/overlays/prod/` | 2 replicas, bigger resources (⚠ H2 note) |

## Wiring (one-time, per env)

Add `../../../services/order-svc/k8s/overlays/prod` to the
`resources:` list of `cluster/overlays/{env}/kustomization.yaml` — the
`images:` entry is pre-listed so CD tags it from day one.

## Placeholders to replace

1. `acr.azurecr.io` → your ACR login server (kustomization files + deployment).
2. Datasource → Azure Postgres (see configmap comments) + DB credentials in Key Vault.