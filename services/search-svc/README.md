# search-svc — API REST de búsqueda de productos

Búsqueda read-only en memoria sobre un catálogo estático pequeño (id 1-8,
refleja inventory-svc y el seed del catálogo). Los resultados se scorean y
ordenan best-first. Producción lo respaldaría con Azure AI Search /
Elasticsearch.

## Endpoints

| Método | Path | Comportamiento |
|---|---|---|
| GET | `/api/search?q=<term>&category=<opcional>` | productos que matchean con score de relevancia, mejores primero |
| GET | `/api/search/hot` | términos de query más populares (rankeados, acotados por `search.hot-limit`) |

Scoring (spec): match de nombre = 3, match de descripción = 1, match de tag =
1; solo se devuelven hits con score > 0. El match es `contains`
case-insensitive. Resultados acotados a `search.max-results` (10).

## Correrlo localmente

```bash
cd services/search-svc
mvn spring-boot:run          # arranca en :8091
```

```bash
curl "localhost:8091/api/search?q=keyboard"
curl "localhost:8091/api/search?q=hd&category=Audio"
curl localhost:8091/api/search/hot
```

## Diseño de producción

Un índice estático in-memory único no escala a catálogos reales. Cambiá
`SearchService` por Azure AI Search (índice + full-text scoring +
suggestions); mantené el contrato REST (query → scored hits). Las búsquedas
hot vendrían de analítica de queries agregada en vez de contadores por pod.

## Kubernetes

| Manifiesto | Propósito |
|---|---|
| `k8s/base/deployment.yaml` | 1 réplica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `search-svc:8091` |
| `k8s/base/hpa.yaml` | CPU 70%, réplicas 1→5 |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress desde api-gateway; egress DNS + peers + 443 |
| `k8s/overlays/prod/` | 2 réplicas, resources más grandes |

## Wiring (one-time, por env)

Agregá `../../../services/search-svc/k8s/{base|overlays/prod}` a la lista
`resources:` de `cluster/overlays/{env}/kustomization.yaml` (base para
dev/staging, overlay de prod para prod) — la entrada `images:` ya está
pre-listada para que el CD la tagee desde el día uno.

## Placeholders a reemplazar

1. `acr.azurecr.io` → tu ACR login server (archivos de kustomization + deployment).