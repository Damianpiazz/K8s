# data/kafka — Kafka deployment options

## Managed (default): Azure Event Hubs with Kafka protocol

Provisioned by `infra/terraform` (Phase 2). Event Hubs exposes a
Kafka-compatible endpoint, which is what the per-env ConfigMap points at:

| Key | Managed value (example) |
|---|---|
| `KAFKA_BOOTSTRAP` | `ns-dev-ecommerce.servicebus.windows.net:9093` |
| `KAFKA_SECURITY_PROTOCOL` | `SASL_SSL` |

Event Hubs specifics services must handle:

- **SASL/PLAIN + TLS** — use `SASL_SSL` with username `$ConnectionString` and
  the namespace's connection string as password (a `Key Vault → ExternalSecret`
  secret, never a ConfigMap).
- **Topics are Event Hubs** — creation is done in Azure (or via the Event Hubs
  admin SDK), not by Kafka auto-create. `auto.create.topics.enable` is ignored
  on the Azure side.
- **Consumer groups** must be created ahead of time (the KEDA ScaledObject in
  `cluster/base/keda/` already references `order-worker` as one).
- Kafka version fingerprint: Event Hubs speaks Kafka protocol 2.x — clients
  should target `kafka.version=2.0.0`-ish; the base ScaledObject uses 2.0.0.

## In-cluster (dev/local alternative): Strimzi

Same operator + CR pattern as any Kafka deployment. Files:

- `strimzi/values.yaml` — operator Helm values (thin: watch all namespaces,
  CRDs installed by the chart).
- `strimzi/kafka-cluster.yaml` — Kafka CR: 3 brokers, ephemeral storage,
  internal + NodePort listeners, metrics placeholder, entity operator with
  topic + user operators.

```bash
kubectl create ns kafka
helm repo add strimzi https://strimzi.io/charts/
helm install strimzi strimzi/strimzi-kafka-operator -n kafka \
  -f data/kafka/strimzi/values.yaml
kubectl apply -f data/kafka/strimzi/kafka-cluster.yaml

# wait for readiness
kubectl -n kafka get kafka ecommerce-kafka -w
```

Service DNS: `ecommerce-kafka-kafka-bootstrap.kafka.svc.cluster.local:9092`
(plaintext, cluster network only). Host access: NodePort 30892.

## What not to do

- Never deploy both paths for the same environment (Strimzi cluster AND Event
  Hubs pointing at the same topic names → split brain consumers).
- Do not use ephemeral storage outside dev — brokers lose data on restart.
  Switch `spec.kafka.storage` to `persistent-claim` for staging/prod.
- Do not expose the plaintext listener outside the cluster. The NodePort
  listener exists for local dev only; remove it on anything shared.
- Event Hubs topics are NOT auto-created from Strimzi-style `auto.create` —
  create hubs + consumer groups in Azure ahead of deployments.