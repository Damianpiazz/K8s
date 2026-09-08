# recommendation-svc — Product recommendation REST API

In-memory, collaborative-ish scoring seeded from a small by-customer purchase
map (plus an also-bought graph). Two entry points: recommendations per customer
and "similar to this product" per customer. Production design: offline ML → Redis
cache (see below).

## Endpoints

| Method | Path | Behavior |
|---|---|---|
| GET | `/api/recommendations?customerId=<id>&limit=<n>` | recommendations for a customer (unknown id → popular fallback) |
| GET | `/api/recommendations/{customerId}/similar?productId=<id>&limit=<n>` | products similar to the seed product for that customer |

`limit` defaults to `recommendation.default-limit` (5) and is capped at
`recommendation.max-limit` (10). Each result carries `productId`, `name`,
`category`, `score`, `reason` (`also-bought` / `same-category`).

## Run locally

```bash
cd services/recommendation-svc
mvn spring-boot:run          # starts on :8092
```

```bash
curl "localhost:8092/api/recommendations?customerId=cust-1"
curl "localhost:8092/api/recommendations/cust-1/similar?productId=1"
```

## Production design

The naive in-memory scoring recomputes everything at request time. Production:

1. **Offline ML job** (e.g. Azure Databricks / Synapse) trains a collaborative
   filter or embeddings model nightly from the analytics events
   (`analytics-svc`) and writes the top-N recommendations per customer.
2. **Redis cache** (Azure Cache for Redis) serves reads: `GET
   recs:{customerId}` is an O(N) cache lookup; a miss falls back to a coarse
   "popular in category" default.
3. The REST contract above stays identical — only `RecommendationService`
   changes to a Redis-backed implementation.

## Kubernetes

| Manifest | Purpose |
|---|---|
| `k8s/base/deployment.yaml` | 1 replica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `recommendation-svc:8092` |
| `k8s/base/hpa.yaml` | CPU 70%, 1→5 replicas |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress from api-gateway; egress DNS + peers + 443 |
| `k8s/overlays/prod/` | 2 replicas, bigger resources |

## Wiring (one-time, per env)

Add `../../../services/recommendation-svc/k8s/{base|overlays/prod}` to the
`resources:` list of `cluster/overlays/{env}/kustomization.yaml` (base for
dev/staging, prod overlay for prod) — the `images:` entry is pre-listed so CD
tags it from day one.

## Placeholders to replace

1. `acr.azurecr.io` → your ACR login server (kustomization files + deployment).