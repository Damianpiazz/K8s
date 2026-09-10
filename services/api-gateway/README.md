# api-gateway (Spring Cloud Gateway)

Único entrypoint HTTP de la plataforma. El tráfico externo (a través del
Ingress de ingress-nginx en `k8s/base/ingress.yaml`) llega acá y se rutea a los
servicios backend usando URIs `lb://` resueltas via Eureka.

## Correrlo localmente

Necesita discovery-service (y opcionalmente config-service) corriendo:

```bash
cd services/discovery-service && mvn spring-boot:run          # terminal 1
cd services/api-gateway && mvn spring-boot:run                 # terminal 2
```

- Gateway: `http://localhost:8080/`
- Health: `http://localhost:8080/actuator/health`
- Métricas de Prometheus: `http://localhost:8080/actuator/prometheus`
- Dashboard de Eureka: `http://localhost:8761/` (instancias registradas)

## Rutas

| Path | Target (lb://) | Fase |
|---|---|---|
| `/api/catalog/**` | `catalog-svc` | 5 (lista) |
| `/api/cart/**` | `cart-svc` | 5 (lista) |
| `/api/orders/**` | `order-svc` | 6 (lista) |
| `/api/payments/**` | `payment-svc` | 6 (lista) |
| `/api/notifications/**` | `notification-svc` | 6 (lista) |
| `/api/checkout/**` | `checkout-svc` | 6 (lista) |
| `/api/bff/**` | `bff-web` | 6 (lista) |
| `/api/search/**` | `search-svc` | 7 (lista) |
| `/api/recommendations/**` | `recommendation-svc` | 7 (lista) |
| `/api/inventory/**` | `inventory-svc` | 7 (lista) |
| `/api/shipping/**` | `shipping-svc` | 7 (lista) |
| `/api/returns/**` | `returns-svc` | 7 (lista) |
| `/api/analytics/**` | `analytics-svc` | 7 (lista) |
| `/api/customers/**` | `customer-svc` | posterior |

Las rutas placeholder de fases posteriores (p. ej. `customer-svc`) ya apuntan a
los futuros nombres de registro, así que esos servicios arrancan a funcionar con
**cero cambios en el gateway**.

## Rate limiting (opcional)

Comentado por defecto: el filtro `RequestRateLimiter` necesita Redis
(reactivo). Habilitalo:

1. Descomentando `spring-boot-starter-data-redis-reactive` en `pom.xml`.
2. Descomentando el bloque del filtro `RequestRateLimiter` en `application.yml`.
3. Teniendo Redis alcanzable — local, `data/redis`, o Azure Cache for Redis via
   el env `REDIS_HOST` de `ecommerce-env-config`.

## CORS

Configurado globalmente en `application.yml` (`spring.cloud.gateway.globalcors`):
orígenes localhost para dev + `https://api.<domain>` para prod.

## Kubernetes

| Manifiesto | Propósito |
|---|---|
| `k8s/base/deployment.yaml` | 1 réplica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `api-gateway:8080` |
| `k8s/base/ingress.yaml` | ingress-nginx, host `api.<domain>`, TLS via `letsencrypt-default` |
| `k8s/base/hpa.yaml` | CPU 70%, réplicas 1→8 |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress solo desde ingress-nginx; egress DNS + peers de plataforma + 443 |
| `k8s/overlays/prod/` | 3 réplicas, resources más grandes |

## Placeholders a reemplazar

1. `acr.azurecr.io` → tu ACR login server (archivos de kustomization + deployment).
2. `api.<domain>` → tu zona DNS pública en `k8s/base/ingress.yaml`
   (y la lista de orígenes CORS en `application.yml`).
3. El issuer `letsencrypt-default` ya está layerizado por `cluster/overlays/*` —
   no requiere acción.