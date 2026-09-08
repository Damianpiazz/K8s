# search-svc — Product search REST API

Read-only in-memory search over a small static catalog (id 1-8, mirrors
inventory-svc and the catalog seed). Results are scored and ordered best-first.
Production would back this with Azure AI Search / Elasticsearch.

## Endpoints

| Method | Path | Behavior |
|---|---|---|
| GET | `/api/search?q=<term>&category=<optional>` | matching products with relevance score, best first |
| GET | `/api/search/hot` | most popular query terms (ranked, capped by `search.hot-limit`) |

Scoring (spec): name match = 3, description match = 1, tag match = 1; only hits
with score > 0 are returned. Match is case-insensitive `contains`. Results
capped at `search.max-results` (10).

## Run locally

```bash
cd services/search-svc
mvn spring-boot:run          # starts on :8091
```

```bash
curl "localhost:8091/api/search?q=keyboard"
curl "localhost:8091/api/search?q=hd&category=Audio"
curl localhost:8091/api/search/hot
```

## Production design

A single in-memory static index does not scale to real catalogs. Swap
`SearchService` for Azure AI Search (index + full-text scoring + suggestions);
keep the REST contract (query → scored hits). Hot searches would come from
aggregated query analytics rather than per-pod counters.

## Kubernetes

| Manifest | Purpose |
|---|---|
| `k8s/base/deployment.yaml` | 1 replica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `search-svc:8091` |
| `k8s/base/hpa.yaml` | CPU 70%, 1→5 replicas |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress from api-gateway; egress DNS + peers + 443 |
| `k8s/overlays/prod/` | 2 replicas, bigger resources |

## Wiring (one-time, per env)

Add `../../../services/search-svc/k8s/{base|overlays/prod}` to the `resources:`
list of `cluster/overlays/{env}/kustomization.yaml` (base for dev/staging, prod
overlay for prod) — the `images:` entry is pre-listed so CD tags it from day one.

## Placeholders to replace

1. `acr.azurecr.io` → your ACR login server (kustomization files + deployment).