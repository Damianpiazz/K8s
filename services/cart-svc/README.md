# cart-svc — API REST de carrito de compras

Carritos por sesión. El store es un `ConcurrentHashMap` en memoria para la
demo — producción debería usar Redis (Azure Cache for Redis), ver abajo.

## Endpoints

| Método | Path | Comportamiento |
|---|---|---|
| GET | `/api/cart/{cartId}` | carrito completo (items + total calculado) |
| POST | `/api/cart/{cartId}/items` | agrega/mergea línea — body `{"productId": 1, "quantity": 2, "unitPrice": 29.99}` |
| DELETE | `/api/cart/{cartId}/items/{productId}` | elimina línea (204; 404 si no existe) |
| GET | `/api/cart/{cartId}/total` | `{"cartId", "total", "itemCount"}` |

`cartId` es el identificador de sesión (cualquier string); el api-gateway
reenvía `/api/cart/**` sin tocar.

## Correrlo localmente

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

## Store de producción: Redis

1. Descomentá `spring-boot-starter-data-redis` en `pom.xml`.
2. Reemplazá `CartService` con una implementación respaldada por Redis
   (`data/redis` in-cluster, o Azure Cache for Redis via las env vars
   `REDIS_HOST`/`REDIS_PORT` ya provistas por `ecommerce-env-config`).
3. Recién ahí es seguro escalar réplicas — los carritos pasan a ser
   compartidos, no por pod.

## Kubernetes

| Manifiesto | Propósito |
|---|---|
| `k8s/base/deployment.yaml` | 1 réplica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `cart-svc:8082` |
| `k8s/base/hpa.yaml` | CPU 70%, réplicas 1→5 |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress desde api-gateway; egress DNS + peers + 6380/443 |
| `k8s/overlays/prod/` | 2 réplicas (¡después de Redis!) |

## Placeholders a reemplazar

1. `acr.azurecr.io` → tu ACR login server.