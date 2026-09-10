# recommendation-svc — API REST de recomendaciones de productos

Scoring en memoria, estilo colaborativo, seedeado desde un pequeño map de
compras por cliente (más un grafo also-bought). Dos entry points:
recomendaciones por cliente y "similar a este producto" por cliente. Diseño de
producción: ML offline → cache Redis (ver abajo).

## Endpoints

| Método | Path | Comportamiento |
|---|---|---|
| GET | `/api/recommendations?customerId=<id>&limit=<n>` | recomendaciones para un cliente (id desconocido → fallback popular) |
| GET | `/api/recommendations/{customerId}/similar?productId=<id>&limit=<n>` | productos similares al producto seed para ese cliente |

`limit` default a `recommendation.default-limit` (5) y está acotado a
`recommendation.max-limit` (10). Cada resultado lleva `productId`, `name`,
`category`, `score`, `reason` (`also-bought` / `same-category`).

## Correrlo localmente

```bash
cd services/recommendation-svc
mvn spring-boot:run          # arranca en :8092
```

```bash
curl "localhost:8092/api/recommendations?customerId=cust-1"
curl "localhost:8092/api/recommendations/cust-1/similar?productId=1"
```

## Diseño de producción

El scoring naive en memoria recalcula todo en tiempo de request. Producción:

1. **Job de ML offline** (p. ej. Azure Databricks / Synapse) entrena un
   collaborative filter o embeddings models nightly desde los eventos de
   analytics (`analytics-svc`) y escribe las recomendaciones top-N por cliente.
2. **Cache Redis** (Azure Cache for Redis) sirve las lecturas: `GET
   recs:{customerId}` es una lookup O(N) de cache; un miss cae a un default
   grueso "popular en categoría".
3. El contrato REST de arriba queda idéntico — solo cambia
   `RecommendationService` a una implementación respaldada por Redis.

## Kubernetes

| Manifiesto | Propósito |
|---|---|
| `k8s/base/deployment.yaml` | 1 réplica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `recommendation-svc:8092` |
| `k8s/base/hpa.yaml` | CPU 70%, réplicas 1→5 |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress desde api-gateway; egress DNS + peers + 443 |
| `k8s/overlays/prod/` | 2 réplicas, resources más grandes |

## Wiring (one-time, por env)

Agregá `../../../services/recommendation-svc/k8s/{base|overlays/prod}` a la
lista `resources:` de `cluster/overlays/{env}/kustomization.yaml` (base para
dev/staging, overlay de prod para prod) — la entrada `images:` ya está
pre-listada para que el CD la tagee desde el día uno.

## Placeholders a reemplazar

1. `acr.azurecr.io` → tu ACR login server (archivos de kustomization + deployment).