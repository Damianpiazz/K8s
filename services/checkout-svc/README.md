# checkout-svc — Checkout orchestration REST API

Orchestrates a purchase: loads the cart from cart-svc, computes the total and
records an in-memory checkout reference. If cart-svc is unreachable it falls
back to a mock (empty) cart so the flow keeps working standalone — a demo of
graceful degradation between services.

## Endpoints

| Method | Path | Behavior |
|---|---|---|
| POST | `/api/checkout` | run the flow: `{cartId, customerId, paymentMethod}` → 201 `{orderId, total, status}` |
| GET | `/api/checkout/{id}` | single checkout record (404 if unknown) |
| GET | `/api/checkout` | list all recorded checkouts |

## Run locally

```bash
cd services/checkout-svc
mvn spring-boot:run          # starts on :8083 (mock-cart fallback when cart-svc is down)
```

```bash
curl -X POST localhost:8083/api/checkout \
  -H 'Content-Type: application/json' \
  -d '{"cartId":"session-1","customerId":"cust-42","paymentMethod":"card"}'
```

With cart-svc running locally, set the base URL back to the local instance
(the k8s deployment sets `CHECKOUT_CART_BASE_URL=http://cart-svc:8082`):

```bash
CHECKOUT_CART_BASE_URL=http://localhost:8082 mvn spring-boot:run
```

## Production path (commented in code)

- Create the order via `POST http://order-svc:8084/api/orders` (JPA/Postgres)
  instead of the in-memory order id.
- Trigger payment-svc and persist checkouts to Postgres (JPA entity mirroring
  `CheckoutRecord`); emit an `order-events` Kafka message for downstream
  services (see notification-svc consumer).
- Gateways → cart reading stays the same.

## Kubernetes

| Manifest | Purpose |
|---|---|
| `k8s/base/deployment.yaml` | 1 replica, probes, securityContext, resources, `CHECKOUT_CART_BASE_URL` |
| `k8s/base/service.yaml` | ClusterIP `checkout-svc:8083` |
| `k8s/base/hpa.yaml` | CPU 70%, 1→5 replicas |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress from api-gateway; egress DNS + platform peers + 443/5432 |
| `k8s/overlays/prod/` | 2 replicas, bigger resources |

## Wiring (one-time, per env)

Add `../../../services/checkout-svc/k8s/overlays/prod` to the
`resources:` list of `cluster/overlays/{env}/kustomization.yaml` — the
`images:` entry is pre-listed so CD tags it from day one.

## Placeholders to replace

1. `acr.azurecr.io` → your ACR login server (kustomization files + deployment).