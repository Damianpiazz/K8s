# notification-svc — API REST de dispatch de notificaciones (+ consumer Kafka opcional)

Guarda notificaciones in-memory a través de una API REST simple, y demuestra
una integración event-driven: un listener de Kafka (Azure Event Hubs en
producción) que convierte eventos de plataforma (`order-events`,
`payment-events`) en notificaciones. El consumer está gateado por
`notifications.kafka.enabled`, así que el servicio compila y sus tests pasan
con **cero** infraestructura de broker.

## Endpoints

| Método | Path | Comportamiento |
|---|---|---|
| POST | `/api/notifications` | crea: `{type, recipient, subject, body}` → 201 + notificación guardada |
| GET | `/api/notifications/{id}` | notificación individual (404 si desconocida) |
| GET | `/api/notifications` | lista todas las notificaciones |

`type` debe ser uno de: `ORDER_CONFIRMED`, `PAYMENT_RECEIVED`,
`PAYMENT_DECLINED`, `SHIPPED`, `DELIVERED`, `PROMOTIONAL`, `SYSTEM`.

## Correrlo localmente

```bash
cd services/notification-svc
mvn spring-boot:run          # arranca en :8086 — consumer OFF por defecto
```

```bash
curl -X POST localhost:8086/api/notifications \
  -H 'Content-Type: application/json' \
  -d '{"type":"ORDER_CONFIRMED","recipient":"customer@example.com","subject":"Order update","body":"Order #42 confirmed"}'
```

## Habilitar el consumer de Kafka (producción / demo)

1. Seteá `NOTIFICATIONS_KAFKA_ENABLED=true` (env) o
   `notifications.kafka.enabled: true` (config).
2. Apuntá `spring.kafka.bootstrap-servers` al broker — en k8s el ConfigMap
   `ecommerce-env-config` ya provee `KAFKA_BOOTSTRAP` con el endpoint
   SASL_SSL de Azure Event Hubs (`*.servicebus.windows.net:9093`); el
   deployment lista `notification-svc-config` antes de `ecommerce-env-config`
   para que el valor del env gane.
3. Agregá credenciales SASL (`spring.kafka.properties.*`) desde Azure Key Vault
   via external-secrets cuando te conectes a Event Hubs.

Topics consumidos: `order-events`, `payment-events` (groupId `notification-svc`).

## Kubernetes

| Manifiesto | Propósito |
|---|---|
| `k8s/base/deployment.yaml` | 1 réplica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `notification-svc:8086` |
| `k8s/base/configmap.yaml` | switch del consumer + default de bootstrap + URLs de config/eureka |
| `k8s/base/hpa.yaml` | CPU 70%, réplicas 1→5 |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress desde api-gateway; egress DNS + peers + 443/9093 (Event Hubs) |
| `k8s/overlays/prod/` | 2 réplicas, resources más grandes |

## Wiring (one-time, por env)

Agregá `../../../services/notification-svc/k8s/overlays/prod` a la lista
`resources:` de `cluster/overlays/{env}/kustomization.yaml` — la entrada
`images:` ya está pre-listada para que el CD la tagee desde el día uno.

## Placeholders a reemplazar

1. `acr.azurecr.io` → tu ACR login server (archivos de kustomization + deployment).
2. Kafka → Azure Event Hubs: habilitá el flag del consumer + credenciales SASL en
   Key Vault (los topics `order-events` / `payment-events` deben existir).