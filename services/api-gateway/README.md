# api-gateway (Spring Cloud Gateway)

Single HTTP entrypoint for the platform. External traffic (through the
ingress-nginx Ingress in `k8s/base/ingress.yaml`) lands here and is routed to
backend services using `lb://` URIs resolved through Eureka.

## Run locally

Needs discovery-service (and optionally config-service) running:

```bash
cd services/discovery-service && mvn spring-boot:run          # terminal 1
cd services/api-gateway && mvn spring-boot:run                 # terminal 2
```

- Gateway: `http://localhost:8080/`
- Health: `http://localhost:8080/actuator/health`
- Prometheus metrics: `http://localhost:8080/actuator/prometheus`
- Eureka dashboard: `http://localhost:8761/` (registered instances)

## Routes

| Path | Target (lb://) | Phase |
|---|---|---|
| `/api/catalog/**` | `catalog-svc` | 5 (ready) |
| `/api/cart/**` | `cart-svc` | 5 (ready) |
| `/api/orders/**` | `order-svc` | 6 (ready) |
| `/api/payments/**` | `payment-svc` | 6 (ready) |
| `/api/notifications/**` | `notification-svc` | 6 (ready) |
| `/api/checkout/**` | `checkout-svc` | 6 (ready) |
| `/api/bff/**` | `bff-web` | 6 (ready) |
| `/api/search/**` | `search-svc` | 7 (ready) |
| `/api/recommendations/**` | `recommendation-svc` | 7 (ready) |
| `/api/inventory/**` | `inventory-svc` | 7 (ready) |
| `/api/shipping/**` | `shipping-svc` | 7 (ready) |
| `/api/returns/**` | `returns-svc` | 7 (ready) |
| `/api/analytics/**` | `analytics-svc` | 7 (ready) |
| `/api/customers/**` | `customer-svc` | later |

Later-phase placeholder routes (e.g. `customer-svc`) already point at the future
registration names, so those services start working with **zero gateway
changes**.

## Rate limiting (optional)

Commented out by default: the `RequestRateLimiter` filter needs Redis
(reactive). Enable it by:

1. Uncommenting `spring-boot-starter-data-redis-reactive` in `pom.xml`.
2. Uncommenting the `RequestRateLimiter` filter block in `application.yml`.
3. Having Redis reachable — local, `data/redis`, or Azure Cache for Redis via
   the `REDIS_HOST` env from `ecommerce-env-config`.

## CORS

Configured globally in `application.yml` (`spring.cloud.gateway.globalcors`):
localhost origins for dev + `https://api.<domain>` for prod.

## Kubernetes

| Manifest | Purpose |
|---|---|
| `k8s/base/deployment.yaml` | 1 replica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `api-gateway:8080` |
| `k8s/base/ingress.yaml` | ingress-nginx, host `api.<domain>`, TLS via `letsencrypt-default` |
| `k8s/base/hpa.yaml` | CPU 70%, 1→8 replicas |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress only from ingress-nginx; egress DNS + platform peers + 443 |
| `k8s/overlays/prod/` | 3 replicas, bigger resources |

## Placeholders to replace

1. `acr.azurecr.io` → your ACR login server (kustomization + deployment files).
2. `api.<domain>` → your public DNS zone in `k8s/base/ingress.yaml`
   (and the CORS origin list in `application.yml`).
3. `letsencrypt-default` issuer is already layered by `cluster/overlays/*` —
   no action needed.