# returns-svc — API REST de autorización de devoluciones (RMA)

Store de RMA in-memory (estilo cart-svc) con una **regla de aprobación visible
y documentada** para que la demo sea explicable. Las claves de config se
alinean con `config-service` `returns-svc.yml`.

## Regla de auto-aprobación

Una devolución se crea como **`APPROVED`** cuando se cumplen *todas* estas:

1. `reason` está en la allow-list — `returns.auto-approvable-reasons`
   (`defective, wrong-item, not-as-described`);
2. llega dentro de la ventana de devolución — `returns.return-window-days` (30);
3. `quantity` ≤ `returns.max-quantity-auto-approve` (10).

Si no, la RMA se crea como **`PENDING_REVIEW`** y un operador decide.

## Endpoints

| Método | Path | Comportamiento |
|---|---|---|
| POST | `/api/returns` | crea — body `{"orderId": "12", "productId": 3, "reason": "defective", "quantity": 1}` → 201 (regla de arriba) |
| GET | `/api/returns/{id}` | RMA individual (404 si desconocida) |
| GET | `/api/returns/order/{orderId}` | RMAs de un pedido (más nuevas primero) |
| POST | `/api/returns/{id}/approve` | aprobación de operador (409 si ya está rechazada) |
| POST | `/api/returns/{id}/reject` | rechazo de operador (409 si ya está aprobada) |

## Correrlo localmente

```bash
cd services/returns-svc
mvn spring-boot:run          # arranca en :8095
```

```bash
curl -X POST localhost:8095/api/returns \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"12","productId":3,"reason":"defective","quantity":1}'
curl -X POST localhost:8095/api/returns/2/approve
curl localhost:8095/api/returns/order/12
```

## Diseño de producción

- **Store**: mover las RMAs a una base de datos (compartida, durable, amigable
  con audit-trail).
- **Política como código**: mantener la regla en un solo lugar (idealmente un
  rules engine / workflow como Azure Logic Apps) para que las aprobaciones sean
  consistentes entre canales; agregar captura de evidencia (fotos) y
  orquestación de reembolsos en la aprobación.

## Kubernetes

| Manifiesto | Propósito |
|---|---|
| `k8s/base/deployment.yaml` | 1 réplica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `returns-svc:8095` |
| `k8s/base/hpa.yaml` | CPU 70%, réplicas 1→5 |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress desde api-gateway; egress DNS + peers + 443 |
| `k8s/overlays/prod/` | 2 réplicas, resources más grandes (⚠ nota in-memory) |

## Wiring (one-time, por env)

Agregá `../../../services/returns-svc/k8s/{base|overlays/prod}` a la lista
`resources:` de `cluster/overlays/{env}/kustomization.yaml` (base para
dev/staging, overlay de prod para prod) — la entrada `images:` ya está
pre-listada para que el CD la tagee desde el día uno.

## Placeholders a reemplazar

1. `acr.azurecr.io` → tu ACR login server (archivos de kustomization + deployment).
2. Store in-memory → RMAs respaldadas por base de datos para producción.