# analytics-svc — Event ingestion & reporting REST API

Demonstrates the classic write-then-query analytics pattern: services POST
events, this service keeps a bounded in-memory log plus O(1) aggregated
counters, and report endpoints derive from the counters. Production design:
Azure Event Hubs + aggregator (see below). Config keys align with
`config-service` `analytics-svc.yml` (`analytics.default-top-limit`,
`analytics.max-events-buffer`).

## Endpoints

| Method | Path | Behavior |
|---|---|---|
| POST | `/api/analytics/events` | accept — body `{"type": "VIEW", "productId": 3, "customerId": "cust-1", "value": 29.99}` → 201 + event (id, timestamp) |
| GET | `/api/analytics/events?type=VIEW` | events in the buffer, optionally filtered by type (newest first) |
| GET | `/api/analytics/reports/top-products?limit=5` | products by total event count, highest first |
| GET | `/api/analytics/reports/summary` | totals per event type `{VIEW: n, CLICK: n, …}` |

Event types: `VIEW`, `ADD_TO_CART`, `PURCHASE`, `SEARCH`, `CLICK`. `productId`
and `customerId` and `value` are optional. Unknown types are rejected (400).

## Run locally

```bash
cd services/analytics-svc
mvn spring-boot:run          # starts on :8096
```

```bash
curl -X POST localhost:8096/api/analytics/events \
  -H 'Content-Type: application/json' \
  -d '{"type":"VIEW","productId":3,"customerId":"cust-1"}'
curl "localhost:8096/api/analytics/events?type=VIEW"
curl "localhost:8096/api/analytics/reports/top-products?limit=5"
curl localhost:8096/api/analytics/reports/summary
```

## Production design

- **Ingestion**: POSTs are not a scalable event pipe. Services instead publish
  events to **Azure Event Hubs** (Kafka-compatible endpoint,
  `KAFKA_BOOTSTRAP` env in `ecommerce-env-config`), with a small forwarder
  keeping this REST endpoint for backfill/demo.
- **Aggregation**: an extractor (Azure Functions / Stream Analytics) reads the
  hub and writes per-minute rollups to a table; `top-products` and `summary`
  read the rollups, so reports stay fast regardless of event volume, and the
  per-pod buffer limitation disappears.

## Kubernetes

| Manifest | Purpose |
|---|---|
| `k8s/base/deployment.yaml` | 1 replica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `analytics-svc:8096` |
| `k8s/base/hpa.yaml` | CPU 70%, 1→5 replicas |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress from api-gateway; egress DNS + peers + 443/9093 |
| `k8s/overlays/prod/` | 2 replicas, bigger resources (⚠ per-pod buffer note) |

## Wiring (one-time, per env)

Add `../../../services/analytics-svc/k8s/{base|overlays/prod}` to the
`resources:` list of `cluster/overlays/{env}/kustomization.yaml` (base for
dev/staging, prod overlay for prod) — the `images:` entry is pre-listed so CD
tags it from day one.

## Placeholders to replace

1. `acr.azurecr.io` → your ACR login server (kustomization files + deployment).
2. In-memory buffer → Event Hubs + aggregator for production.