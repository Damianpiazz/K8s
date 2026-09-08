# payment-svc — Payment processing REST API

Processes payment attempts with a simple approval rule and keeps an in-memory
store for the demo. The JPA/H2/Postgres stack is on the classpath with config
keys aligned to `config-service` `payment-svc.yml` — ready for Azure Postgres.

## Endpoints

| Method | Path | Behavior |
|---|---|---|
| POST | `/api/payments` | process: `{orderId, amount, method, cardLast4?}` → 201 + payment with `APPROVED`/`DECLINED` |
| GET | `/api/payments/{id}` | single payment (404 if unknown) |
| GET | `/api/payments/order/{orderId}` | all payments of one order |
| GET | `/api/payments` | list all payments |

Approval rule (demo): `amount ≤ 0` → DECLINED; placeholder `cardLast4`
ending in `0000` → DECLINED; everything else → APPROVED. A real system would
delegate the decision to a PSP (Stripe/Adyen) tokenization service.

## Run locally

```bash
cd services/payment-svc
mvn spring-boot:run          # starts on :8085
```

```bash
curl -X POST localhost:8085/api/payments \
  -H 'Content-Type: application/json' \
  -d '{"orderId":1,"amount":149.48,"method":"card","cardLast4":"4242"}'
```

## Switching to Azure managed Postgres

`k8s/base/configmap.yaml` (`payment-svc-config`) holds the datasource defaults.
Override the `SPRING_DATASOURCE_*` keys per the commented instructions (DB
credentials from Azure Key Vault via external-secrets) and swap the in-memory
`PaymentService` for its `PaymentRepository` (JPA) implementation — the REST
contract stays identical.

## Kubernetes

| Manifest | Purpose |
|---|---|
| `k8s/base/deployment.yaml` | 1 replica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `payment-svc:8085` |
| `k8s/base/configmap.yaml` | datasource defaults + config/eureka URLs |
| `k8s/base/hpa.yaml` | CPU 70%, 1→5 replicas |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress from api-gateway; egress DNS + peers + 5432/443 |
| `k8s/overlays/prod/` | 2 replicas, bigger resources (⚠ in-memory note) |

## Wiring (one-time, per env)

Add `../../../services/payment-svc/k8s/overlays/prod` to the
`resources:` list of `cluster/overlays/{env}/kustomization.yaml` — the
`images:` entry is pre-listed so CD tags it from day one.

## Placeholders to replace

1. `acr.azurecr.io` → your ACR login server (kustomization files + deployment).
2. Store → Azure Postgres (see configmap comments) + credentials in Key Vault.