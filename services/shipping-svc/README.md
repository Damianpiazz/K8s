# shipping-svc — API REST de envíos de pedidos

Envíos creados por pedido (estado `PENDING`) y avanzados manualmente —
determinístico, sin simulación programada. Store in-memory (estilo cart-svc);
producción persistiría los envíos e integraría una API real de carrier.

## Endpoints

| Método | Path | Comportamiento |
|---|---|---|
| POST | `/api/shipping` | crea — body `{"orderId": "12", "address": {"line1": "…", "city": "…", "postalCode": "…", "country": "…"}}` → 201, estado `PENDING` |
| GET | `/api/shipping/{id}` | envío individual (404 si desconocido) |
| GET | `/api/shipping/order/{orderId}` | envíos de un pedido |
| PATCH | `/api/shipping/{id}/status` | transición — body `{"status": "SHIPPED"}`; targets permitidos: `SHIPPED`, `DELIVERED`; `SHIPPED` asigna el tracking number |

Las transiciones son solo manuales: `PENDING → SHIPPED → DELIVERED`
(cualquier envío no terminal también puede saltar directo a `DELIVERED` por
conveniencia de demo). Los envíos terminales (`DELIVERED`/`FAILED`) rechazan
más transiciones (409); valores de estado desconocidos se rechazan (400).
Claves de config: `shipping.default-carrier` ("Ecommerce Express"),
`shipping.tracking-prefix` ("EC").

## Correrlo localmente

```bash
cd services/shipping-svc
mvn spring-boot:run          # arranca en :8094
```

```bash
curl -X POST localhost:8094/api/shipping \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"12","address":{"line1":"Main St 1","city":"Madrid","postalCode":"28001","country":"ES"}}'
curl -X PATCH localhost:8094/api/shipping/1/status \
  -H 'Content-Type: application/json' -d '{"status":"SHIPPED"}'
curl localhost:8094/api/shipping/order/12
```

## Diseño de producción

- **Store**: cambiá el `ConcurrentHashMap` por una base de datos (Azure
  Postgres) para que los envíos sean compartidos y durables entre
  pods/reinicios.
- **Integración de carrier**: un patrón outbox/event publica eventos
  `shipment.created` consumidos por un adaptador de carrier (labels,
  tracking); los webhooks del carrier avanzan el estado en vez del PATCH
  manual.

## Kubernetes

| Manifiesto | Propósito |
|---|---|
| `k8s/base/deployment.yaml` | 1 réplica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `shipping-svc:8094` |
| `k8s/base/hpa.yaml` | CPU 70%, réplicas 1→5 |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress desde api-gateway; egress DNS + peers + 443 |
| `k8s/overlays/prod/` | 2 réplicas, resources más grandes (⚠ nota in-memory) |

## Wiring (one-time, por env)

Agregá `../../../services/shipping-svc/k8s/{base|overlays/prod}` a la lista
`resources:` de `cluster/overlays/{env}/kustomization.yaml` (base para
dev/staging, overlay de prod para prod) — la entrada `images:` ya está
pre-listada para que el CD la tagee desde el día uno.

## Placeholders a reemplazar

1. `acr.azurecr.io` → tu ACR login server (archivos de kustomization + deployment).
2. Store in-memory → base de datos + integración de carrier para producción.