# bff-web — Backend-for-frontend for the web storefront

Non-blocking aggregation of catalog-svc (:8081) + cart-svc (:8082) into
storefront views using Spring WebFlux WebClient (2s timeouts). When a backend
is unreachable the BFF degrades gracefully — the page keeps rendering with
partial data and a `degraded: true` flag (resilience demo).

## Endpoints

| Method | Path | Behavior |
|---|---|---|
| GET | `/api/bff/home` | `{products, hero, degraded}` — product grid + featured product |
| GET | `/api/bff/cart/{cartId}` | `{cartId, items[], total, degraded}` — cart joined with product details |

Degradation rules:
- catalog-svc down on `/home` → 200 with `products: []`, `hero: null`, `degraded: true`.
- cart-svc down on `/cart/{id}` → 200 with `items: []`, `degraded: true`.
- catalog-svc down while joining cart items → items keep quantity/price with
  name `"Product #<id>"` (per-item degrade).

## Run locally

Needs catalog-svc (:8081) and cart-svc (:8082) for full data; without them the
BFF still answers with degraded payloads:

```bash
cd services/bff-web
mvn spring-boot:run          # starts on :8090
```

```bash
curl localhost:8090/api/bff/home
curl localhost:8090/api/bff/cart/session-1
```

In k8s the deployment overrides the base URLs via
`BFF_CATALOG_BASE_URL=http://catalog-svc:8081` and
`BFF_CART_BASE_URL=http://cart-svc:8082`.

## Kubernetes

| Manifest | Purpose |
|---|---|
| `k8s/base/deployment.yaml` | 1 replica, probes, securityContext, resources, backend base URLs |
| `k8s/base/service.yaml` | ClusterIP `bff-web:8090` |
| `k8s/base/hpa.yaml` | CPU 70%, 1→5 replicas |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress from api-gateway; egress DNS + platform peers + 443 |
| `k8s/overlays/prod/` | 2 replicas, bigger resources |

## Wiring (one-time, per env)

Add `../../../services/bff-web/k8s/overlays/prod` to the
`resources:` list of `cluster/overlays/{env}/kustomization.yaml` — the
`images:` entry is pre-listed so CD tags it from day one.

## Placeholders to replace

1. `acr.azurecr.io` → your ACR login server (kustomization files + deployment).