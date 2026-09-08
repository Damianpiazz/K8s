# cart-svc — Shopping cart REST API

Per-session carts. The store is an in-memory `ConcurrentHashMap` for the demo —
production should use Redis (Azure Cache for Redis), see below.

## Endpoints

| Method | Path | Behavior |
|---|---|---|
| GET | `/api/cart/{cartId}` | full cart (items + computed total) |
| POST | `/api/cart/{cartId}/items` | add/merge line — body `{"productId": 1, "quantity": 2, "unitPrice": 29.99}` |
| DELETE | `/api/cart/{cartId}/items/{productId}` | remove line (204; 404 if absent) |
| GET | `/api/cart/{cartId}/total` | `{"cartId", "total", "itemCount"}` |

`cartId` is the session identifier (any string); the api-gateway forwards
`/api/cart/**` untouched.

## Run locally

```bash
cd services/cart-svc
mvn spring-boot:run
```

```bash
curl localhost:8082/api/cart/session-1
curl -X POST localhost:8082/api/cart/session-1/items \
  -H 'Content-Type: application/json' \
  -d '{"productId":1,"quantity":2,"unitPrice":29.99}'
curl localhost:8082/api/cart/session-1/total
```

## Production store: Redis

1. Uncomment `spring-boot-starter-data-redis` in `pom.xml`.
2. Replace `CartService` with a Redis-backed implementation (`data/redis`
   in-cluster, or Azure Cache for Redis via the `REDIS_HOST`/`REDIS_PORT`
   env vars already provided by `ecommerce-env-config`).
3. Then it's safe to scale replicas — carts become shared, not per-pod.

## Kubernetes

| Manifest | Purpose |
|---|---|
| `k8s/base/deployment.yaml` | 1 replica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `cart-svc:8082` |
| `k8s/base/hpa.yaml` | CPU 70%, 1→5 replicas |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress from api-gateway; egress DNS + peers + 6380/443 |
| `k8s/overlays/prod/` | 2 replicas (after Redis!) |

## Placeholders to replace

1. `acr.azurecr.io` → your ACR login server.