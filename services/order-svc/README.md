# order-svc — API REST de gestión de pedidos

CRUD de pedidos respaldado por Spring Data JPA. El perfil de demo usa H2
in-memory y seedea 2 pedidos de ejemplo al arrancar; el deployment puede
cambiar a Postgres gestionado por Azure sin cambios de código. Las claves de
config se alinean con `config-service` `order-svc.yml`
(`order.max-items-per-order`).

## Endpoints

| Método | Path | Comportamiento |
|---|---|---|
| POST | `/api/orders` | crea: `{customerId, items:[{productId, quantity, price}], total}` → 201 + pedido (estado `CREATED`) |
| GET | `/api/orders` | lista todos los pedidos |
| GET | `/api/orders/{id}` | pedido individual (404 si desconocido) |
| GET | `/api/orders/customer/{customerId}` | pedidos de un cliente (más nuevos primero) |

Validación: al menos un item, quantities positivas, ≤
`order.max-items-per-order` (50).

## Correrlo localmente

```bash
cd services/order-svc
mvn spring-boot:run          # arranca en :8084, seedea pedidos de ejemplo
```

```bash
curl localhost:8084/api/orders
curl localhost:8084/api/orders/customer/cust-42
curl -X POST localhost:8084/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"cust-1","items":[{"productId":1,"quantity":2,"price":29.99}],"total":59.98}'
```

## Cambiar a Postgres gestionado por Azure

`k8s/base/configmap.yaml` (`order-svc-config`) guarda los defaults del
datasource. El ConfigMap `ecommerce-env-config` (de `cluster/overlays/*`) ya
lleva `DB_HOST` / `DB_PORT` / `DB_NAME`. Sobreescribí las claves
`SPRING_DATASOURCE_*` según las instrucciones comentadas en ese archivo, y poné
el password en Azure Key Vault (patrón external-secrets — ver
`cluster/base/external-secrets/`).

## Kubernetes

| Manifiesto | Propósito |
|---|---|
| `k8s/base/deployment.yaml` | 1 réplica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `order-svc:8084` |
| `k8s/base/configmap.yaml` | defaults de datasource + URLs de config/eureka |
| `k8s/base/hpa.yaml` | CPU 70%, réplicas 1→5 |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress desde api-gateway; egress DNS + peers + 5432/443 |
| `k8s/overlays/prod/` | 2 réplicas, resources más grandes (⚠ nota de H2) |

## Wiring (one-time, por env)

Agregá `../../../services/order-svc/k8s/overlays/prod` a la lista
`resources:` de `cluster/overlays/{env}/kustomization.yaml` — la entrada
`images:` ya está pre-listada para que el CD la tagee desde el día uno.

## Placeholders a reemplazar

1. `acr.azurecr.io` → tu ACR login server (archivos de kustomization + deployment).
2. Datasource → Postgres de Azure (ver comentarios del configmap) + credenciales de DB en Key Vault.