# analytics-svc — API REST de ingestión de eventos y reporting

Demuestra el patrón clásico de análisis write-then-query: los servicios hacen
POST de eventos, este servicio mantiene un log en memoria acotado más
contadores agregados O(1), y los endpoints de reporte derivan de los
contadores. Diseño de producción: Azure Event Hubs + agregador (ver abajo).
Las claves de config se alinean con `config-service` `analytics-svc.yml`
(`analytics.default-top-limit`, `analytics.max-events-buffer`).

## Endpoints

| Método | Path | Comportamiento |
|---|---|---|
| POST | `/api/analytics/events` | acepta — body `{"type": "VIEW", "productId": 3, "customerId": "cust-1", "value": 29.99}` → 201 + evento (id, timestamp) |
| GET | `/api/analytics/events?type=VIEW` | eventos en el buffer, opcionalmente filtrados por tipo (más nuevos primero) |
| GET | `/api/analytics/reports/top-products?limit=5` | productos por conteo total de eventos, más altos primero |
| GET | `/api/analytics/reports/summary` | totales por tipo de evento `{VIEW: n, CLICK: n, …}` |

Tipos de evento: `VIEW`, `ADD_TO_CART`, `PURCHASE`, `SEARCH`, `CLICK`.
`productId`, `customerId` y `value` son opcionales. Tipos desconocidos se
rechazan (400).

## Correrlo localmente

```bash
cd services/analytics-svc
mvn spring-boot:run          # arranca en :8096
```

```bash
curl -X POST localhost:8096/api/analytics/events \
  -H 'Content-Type: application/json' \
  -d '{"type":"VIEW","productId":3,"customerId":"cust-1"}'
curl "localhost:8096/api/analytics/events?type=VIEW"
curl "localhost:8096/api/analytics/reports/top-products?limit=5"
curl localhost:8096/api/analytics/reports/summary
```

## Diseño de producción

- **Ingestión**: los POST no son un pipe de eventos escalable. Los servicios
  en su lugar publican eventos a **Azure Event Hubs** (endpoint compatible con
  Kafka, env `KAFKA_BOOTSTRAP` en `ecommerce-env-config`), con un pequeño
  forwarder que mantiene este endpoint REST para backfill/demo.
- **Agregación**: un extractor (Azure Functions / Stream Analytics) lee el hub
  y escribe rollups por minuto en una tabla; `top-products` y `summary` leen
  los rollups, así los reportes siguen siendo rápidos sin importar el volumen
  de eventos, y desaparece la limitación del buffer por pod.

## Kubernetes

| Manifiesto | Propósito |
|---|---|
| `k8s/base/deployment.yaml` | 1 réplica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `analytics-svc:8096` |
| `k8s/base/hpa.yaml` | CPU 70%, réplicas 1→5 |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress desde api-gateway; egress DNS + peers + 443/9093 |
| `k8s/overlays/prod/` | 2 réplicas, resources más grandes (⚠ nota del buffer por pod) |

## Wiring (one-time, por env)

Agregá `../../../services/analytics-svc/k8s/{base|overlays/prod}` a la lista
`resources:` de `cluster/overlays/{env}/kustomization.yaml` (base para
dev/staging, overlay de prod para prod) — la entrada `images:` ya está
pre-listada para que el CD la tagee desde el día uno.

## Placeholders a reemplazar

1. `acr.azurecr.io` → tu ACR login server (archivos de kustomization + deployment).
2. Buffer in-memory → Event Hubs + agregador para producción.