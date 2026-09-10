# checkout-svc — API REST de orquestación de checkout

Orquesta una compra: carga el carrito desde cart-svc, calcula el total y
registra una referencia de checkout en memoria. Si cart-svc no está
alcanzable, cae a un carrito mock (vacío) para que el flujo siga funcionando
standalone — una demo de degradación con gracia entre servicios.

## Endpoints

| Método | Path | Comportamiento |
|---|---|---|
| POST | `/api/checkout` | corre el flujo: `{cartId, customerId, paymentMethod}` → 201 `{orderId, total, status}` |
| GET | `/api/checkout/{id}` | registro de checkout individual (404 si desconocido) |
| GET | `/api/checkout` | lista todos los checkouts registrados |

## Correrlo localmente

```bash
cd services/checkout-svc
mvn spring-boot:run          # arranca en :8083 (fallback mock-cart cuando cart-svc está caído)
```

```bash
curl -X POST localhost:8083/api/checkout \
  -H 'Content-Type: application/json' \
  -d '{"cartId":"session-1","customerId":"cust-42","paymentMethod":"card"}'
```

Con cart-svc corriendo localmente, setea la URL base de vuelta a la instancia
local (el deployment de k8s setea `CHECKOUT_CART_BASE_URL=http://cart-svc:8082`):

```bash
CHECKOUT_CART_BASE_URL=http://localhost:8082 mvn spring-boot:run
```

## Camino de producción (comentado en código)

- Crear la order via `POST http://order-svc:8084/api/orders` (JPA/Postgres)
  en vez del id de order in-memory.
- Disparar payment-svc y persistir los checkouts en Postgres (entidad JPA que
  refleja `CheckoutRecord`); emitir un mensaje Kafka `order-events` para
  servicios downstream (ver el consumer de notification-svc).
- Gateways → la lectura del carrito sigue igual.

## Kubernetes

| Manifiesto | Propósito |
|---|---|
| `k8s/base/deployment.yaml` | 1 réplica, probes, securityContext, resources, `CHECKOUT_CART_BASE_URL` |
| `k8s/base/service.yaml` | ClusterIP `checkout-svc:8083` |
| `k8s/base/hpa.yaml` | CPU 70%, réplicas 1→5 |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress desde api-gateway; egress DNS + peers de plataforma + 443/5432 |
| `k8s/overlays/prod/` | 2 réplicas, resources más grandes |

## Wiring (one-time, por env)

Agregá `../../../services/checkout-svc/k8s/overlays/prod` a la lista
`resources:` de `cluster/overlays/{env}/kustomization.yaml` — la entrada
`images:` ya está pre-listada para que el CD la tagee desde el día uno.

## Placeholders a reemplazar

1. `acr.azurecr.io` → tu ACR login server (archivos de kustomization + deployment).