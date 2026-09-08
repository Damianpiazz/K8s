# shipping-svc — Shipping order REST API

Shipments created per order (status `PENDING`) and advanced manually —
deterministic, no scheduled simulation. In-memory store (cart-svc style);
production would persist shipments and integrate a real carrier API.

## Endpoints

| Method | Path | Behavior |
|---|---|---|
| POST | `/api/shipping` | create — body `{"orderId": "12", "address": {"line1": "…", "city": "…", "postalCode": "…", "country": "…"}}` → 201, status `PENDING` |
| GET | `/api/shipping/{id}` | single shipment (404 if unknown) |
| GET | `/api/shipping/order/{orderId}` | shipments of one order |
| PATCH | `/api/shipping/{id}/status` | transition — body `{"status": "SHIPPED"}`; allowed targets: `SHIPPED`, `DELIVERED`; `SHIPPED` assigns the tracking number |

Transitions are manual only: `PENDING → SHIPPED → DELIVERED` (any non-terminal
shipment can also jump straight to `DELIVERED` for demo convenience). Terminal
shipments (`DELIVERED`/`FAILED`) refuse further transitions (409); unknown
status values are rejected (400). Config keys: `shipping.default-carrier`
("Ecommerce Express"), `shipping.tracking-prefix` ("EC").

## Run locally

```bash
cd services/shipping-svc
mvn spring-boot:run          # starts on :8094
```

```bash
curl -X POST localhost:8094/api/shipping \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"12","address":{"line1":"Main St 1","city":"Madrid","postalCode":"28001","country":"ES"}}'
curl -X PATCH localhost:8094/api/shipping/1/status \
  -H 'Content-Type: application/json' -d '{"status":"SHIPPED"}'
curl localhost:8094/api/shipping/order/12
```

## Production design

- **Store**: swap the `ConcurrentHashMap` for a database (Azure Postgres) so
  shipments are shared and durable across pods/restarts.
- **Carrier integration**: an outbox/event pattern publishes `shipment.created`
  events consumed by a carrier adapter (labels, tracking); carrier webhooks
  advance the status instead of the manual PATCH.

## Kubernetes

| Manifest | Purpose |
|---|---|
| `k8s/base/deployment.yaml` | 1 replica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `shipping-svc:8094` |
| `k8s/base/hpa.yaml` | CPU 70%, 1→5 replicas |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress from api-gateway; egress DNS + peers + 443 |
| `k8s/overlays/prod/` | 2 replicas, bigger resources (⚠ in-memory note) |

## Wiring (one-time, per env)

Add `../../../services/shipping-svc/k8s/{base|overlays/prod}` to the
`resources:` list of `cluster/overlays/{env}/kustomization.yaml` (base for
dev/staging, prod overlay for prod) — the `images:` entry is pre-listed so CD
tags it from day one.

## Placeholders to replace

1. `acr.azurecr.io` → your ACR login server (kustomization files + deployment).
2. In-memory store → database + carrier integration for production.