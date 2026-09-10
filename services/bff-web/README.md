# bff-web — Backend-for-frontend del storefront web

Agregación no bloqueante de catalog-svc (:8081) + cart-svc (:8082) en vistas
del storefront usando Spring WebFlux WebClient (timeouts de 2s). Cuando un
backend no está disponible, el BFF degrada con gracia — la página sigue
renderizando con datos parciales y un flag `degraded: true` (demo de
resiliencia).

## Endpoints

| Método | Path | Comportamiento |
|---|---|---|
| GET | `/api/bff/home` | `{products, hero, degraded}` — grilla de productos + producto destacado |
| GET | `/api/bff/cart/{cartId}` | `{cartId, items[], total, degraded}` — carrito unido con detalles de producto |

Reglas de degradación:
- catalog-svc caído en `/home` → 200 con `products: []`, `hero: null`, `degraded: true`.
- cart-svc caído en `/cart/{id}` → 200 con `items: []`, `degraded: true`.
- catalog-svc caído al unir items del carrito → los items mantienen
  quantity/price con nombre `"Product #<id>"` (degradación por item).

## Correrlo localmente

Necesita catalog-svc (:8081) y cart-svc (:8082) para datos completos; sin ellos
el BFF igual responde con payloads degradados:

```bash
cd services/bff-web
mvn spring-boot:run          # arranca en :8090
```

```bash
curl localhost:8090/api/bff/home
curl localhost:8090/api/bff/cart/session-1
```

En k8s el deployment sobreescribe las URLs base via
`BFF_CATALOG_BASE_URL=http://catalog-svc:8081` y
`BFF_CART_BASE_URL=http://cart-svc:8082`.

## Kubernetes

| Manifiesto | Propósito |
|---|---|
| `k8s/base/deployment.yaml` | 1 réplica, probes, securityContext, resources, URLs base de backends |
| `k8s/base/service.yaml` | ClusterIP `bff-web:8090` |
| `k8s/base/hpa.yaml` | CPU 70%, réplicas 1→5 |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress desde api-gateway; egress DNS + peers de plataforma + 443 |
| `k8s/overlays/prod/` | 2 réplicas, resources más grandes |

## Wiring (one-time, por env)

Agregá `../../../services/bff-web/k8s/overlays/prod` a la lista
`resources:` de `cluster/overlays/{env}/kustomization.yaml` — la entrada
`images:` ya está pre-listada para que el CD la tagee desde el día uno.

## Placeholders a reemplazar

1. `acr.azurecr.io` → tu ACR login server (archivos de kustomization + deployment).