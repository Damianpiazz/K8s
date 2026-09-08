# inventory-svc — Inventory & reservation REST API

Stock levels backed by Spring Data JPA (H2 for the demo, Azure managed Postgres
in production) plus a reservation lifecycle: reserve → release / confirm.
Seeded with 8 SKUs (product ids 1-8, mirroring the search/recommendation demo
catalogs). Config keys align with `config-service` `inventory-svc.yml`
(`inventory.reservation-ttl-minutes`).

## Endpoints

| Method | Path | Behavior |
|---|---|---|
| GET | `/api/inventory` | all items `{id, productId, sku, name, stockLevel, reserved}` |
| GET | `/api/inventory/{productId}` | single item (404 if unknown) |
| PUT | `/api/inventory/{productId}` | set stock — body `{"quantity": 80}` |
| POST | `/api/inventory/reserve` | reserve — body `{"productId": 1, "quantity": 3}` → 201 reservation; **409 if insufficient stock** |
| POST | `/api/inventory/release` | release — body `{"reservationId": 12}` → status `RELEASED`, units return to available |
| POST | `/api/inventory/confirm/{reservationId}` | commit reservation → status `CONFIRMED` (stock stays held) |

Available stock = `stockLevel - reserved`; a reserve beyond it fails with 409.

## Run locally

```bash
cd services/inventory-svc
mvn spring-boot:run          # starts on :8093, seeds 8 SKUs
```

```bash
curl localhost:8093/api/inventory
curl -X POST localhost:8093/api/inventory/reserve \
  -H 'Content-Type: application/json' -d '{"productId":1,"quantity":3}'
curl -X PUT localhost:8093/api/inventory/1 \
  -H 'Content-Type: application/json' -d '{"quantity":120}'
```

## Production design

- **Datasource**: switch `k8s/base/configmap.yaml` to Azure managed Postgres
  (see commented keys; credentials via Key Vault/external-secrets).
- **Reservations**: persist them beside the stock row and keep the reserve
  operation transactional — `SELECT … FOR UPDATE` on the stock row so scaling
  out to multiple pods stays correct. The demo map is per-pod, so
  reserving while running 2 replicas distributes reservations per pod.
- **Expiry**: a sweeper job marks `RESERVED` past `expiresAt` as `RELEASED`.

## Kubernetes

| Manifest | Purpose |
|---|---|
| `k8s/base/deployment.yaml` | 1 replica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `inventory-svc:8093` |
| `k8s/base/configmap.yaml` | datasource defaults + config/eureka URLs |
| `k8s/base/hpa.yaml` | CPU 70%, 1→5 replicas |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress from api-gateway; egress DNS + peers + 5432/443 |
| `k8s/overlays/prod/` | 2 replicas, bigger resources (⚠ H2 note) |

## Wiring (one-time, per env)

Add `../../../services/inventory-svc/k8s/{base|overlays/prod}` to the
`resources:` list of `cluster/overlays/{env}/kustomization.yaml` (base for
dev/staging, prod overlay for prod) — the `images:` entry is pre-listed so CD
tags it from day one.

## Placeholders to replace

1. `acr.azurecr.io` → your ACR login server (kustomization files + deployment).
2. Datasource → Azure Postgres (see configmap comments) + DB credentials in Key Vault.
3. Persist reservations + expiry sweeper before scaling out (see above).