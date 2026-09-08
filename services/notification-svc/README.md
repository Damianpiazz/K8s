# notification-svc — Notification dispatch REST API (+ optional Kafka consumer)

Stores notifications in-memory through a plain REST API, and demonstrates an
event-driven integration: a Kafka listener (Azure Event Hubs in production)
that turns platform events (`order-events`, `payment-events`) into
notifications. The consumer is gated by `notifications.kafka.enabled`, so the
service compiles and its tests pass with **zero** broker infrastructure.

## Endpoints

| Method | Path | Behavior |
|---|---|---|
| POST | `/api/notifications` | create: `{type, recipient, subject, body}` → 201 + stored notification |
| GET | `/api/notifications/{id}` | single notification (404 if unknown) |
| GET | `/api/notifications` | list all notifications |

`type` must be one of: `ORDER_CONFIRMED`, `PAYMENT_RECEIVED`, `PAYMENT_DECLINED`,
`SHIPPED`, `DELIVERED`, `PROMOTIONAL`, `SYSTEM`.

## Run locally

```bash
cd services/notification-svc
mvn spring-boot:run          # starts on :8086 — consumer OFF by default
```

```bash
curl -X POST localhost:8086/api/notifications \
  -H 'Content-Type: application/json' \
  -d '{"type":"ORDER_CONFIRMED","recipient":"customer@example.com","subject":"Order update","body":"Order #42 confirmed"}'
```

## Enabling the Kafka consumer (production / demo)

1. Set `NOTIFICATIONS_KAFKA_ENABLED=true` (env) or
   `notifications.kafka.enabled: true` (config).
2. Point `spring.kafka.bootstrap-servers` at the broker — in k8s the
   `ecommerce-env-config` ConfigMap already provides `KAFKA_BOOTSTRAP` with the
   Azure Event Hubs SASL_SSL endpoint (`*.servicebus.windows.net:9093`); the
   deployment lists `notification-svc-config` before `ecommerce-env-config` so
   the env value wins.
3. Add SASL credentials (`spring.kafka.properties.*`) from Azure Key Vault via
   external-secrets when connecting to Event Hubs.

Topics consumed: `order-events`, `payment-events` (groupId `notification-svc`).

## Kubernetes

| Manifest | Purpose |
|---|---|
| `k8s/base/deployment.yaml` | 1 replica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `notification-svc:8086` |
| `k8s/base/configmap.yaml` | consumer switch + bootstrap default + config/eureka URLs |
| `k8s/base/hpa.yaml` | CPU 70%, 1→5 replicas |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress from api-gateway; egress DNS + peers + 443/9093 (Event Hubs) |
| `k8s/overlays/prod/` | 2 replicas, bigger resources |

## Wiring (one-time, per env)

Add `../../../services/notification-svc/k8s/overlays/prod` to the
`resources:` list of `cluster/overlays/{env}/kustomization.yaml` — the
`images:` entry is pre-listed so CD tags it from day one.

## Placeholders to replace

1. `acr.azurecr.io` → your ACR login server (kustomization files + deployment).
2. Kafka → Azure Event Hubs: enable the consumer flag + SASL credentials in Key
   Vault (topics `order-events` / `payment-events` must exist).