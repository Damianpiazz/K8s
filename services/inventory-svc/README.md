# inventory-svc — API REST de inventario y reservas

Niveles de stock respaldados por Spring Data JPA (H2 para la demo, Postgres
gestionado por Azure en producción) más un ciclo de vida de reservas: reservar
→ liberar / confirmar. Seedeado con 8 SKUs (product ids 1-8, reflejando los
catálogos demo de search/recommendation). Las claves de config se alinean con
`config-service` `inventory-svc.yml` (`inventory.reservation-ttl-minutes`).

## Endpoints

| Método | Path | Comportamiento |
|---|---|---|
| GET | `/api/inventory` | todos los items `{id, productId, sku, name, stockLevel, reserved}` |
| GET | `/api/inventory/{productId}` | item individual (404 si desconocido) |
| PUT | `/api/inventory/{productId}` | setea stock — body `{"quantity": 80}` |
| POST | `/api/inventory/reserve` | reserva — body `{"productId": 1, "quantity": 3}` → 201 reserva; **409 si no hay stock suficiente** |
| POST | `/api/inventory/release` | libera — body `{"reservationId": 12}` → estado `RELEASED`, las unidades vuelven a disponibles |
| POST | `/api/inventory/confirm/{reservationId}` | commitea la reserva → estado `CONFIRMED` (el stock sigue retenido) |

Stock disponible = `stockLevel - reserved`; una reserva que lo supere falla con
409.

## Correrlo localmente

```bash
cd services/inventory-svc
mvn spring-boot:run          # arranca en :8093, seedea 8 SKUs
```

```bash
curl localhost:8093/api/inventory
curl -X POST localhost:8093/api/inventory/reserve \
  -H 'Content-Type: application/json' -d '{"productId":1,"quantity":3}'
curl -X PUT localhost:8093/api/inventory/1 \
  -H 'Content-Type: application/json' -d '{"quantity":120}'
```

## Diseño de producción

- **Datasource**: cambiá `k8s/base/configmap.yaml` a Postgres gestionado por
  Azure (ver claves comentadas; credenciales via Key Vault/external-secrets).
- **Reservas**: persistilas junto a la fila de stock y mantené la operación de
  reserva transaccional — `SELECT … FOR UPDATE` sobre la fila de stock para que
  escalar a múltiples pods siga siendo correcto. El map de la demo es por pod,
  así que reservar corriendo 2 réplicas distribuye las reservas por pod.
- **Expiración**: un job sweeper marca los `RESERVED` pasados de `expiresAt`
  como `RELEASED`.

## Kubernetes

| Manifiesto | Propósito |
|---|---|
| `k8s/base/deployment.yaml` | 1 réplica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `inventory-svc:8093` |
| `k8s/base/configmap.yaml` | defaults de datasource + URLs de config/eureka |
| `k8s/base/hpa.yaml` | CPU 70%, réplicas 1→5 |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress desde api-gateway; egress DNS + peers + 5432/443 |
| `k8s/overlays/prod/` | 2 réplicas, resources más grandes (⚠ nota de H2) |

## Wiring (one-time, por env)

Agregá `../../../services/inventory-svc/k8s/{base|overlays/prod}` a la lista
`resources:` de `cluster/overlays/{env}/kustomization.yaml` (base para
dev/staging, overlay de prod para prod) — la entrada `images:` ya está
pre-listada para que el CD la tagee desde el día uno.

## Placeholders a reemplazar

1. `acr.azurecr.io` → tu ACR login server (archivos de kustomization + deployment).
2. Datasource → Postgres de Azure (ver comentarios del configmap) + credenciales de DB en Key Vault.
3. Persistir reservas + sweeper de expiración antes de escalar (ver arriba).