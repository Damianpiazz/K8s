# data/kafka — Opciones de despliegue de Kafka

## Gestionado (default): Azure Event Hubs con protocolo Kafka

Aprovisionado por `infra/terraform` (Fase 2). Event Hubs expone un endpoint
compatible con Kafka, que es a lo que apunta el ConfigMap por ambiente:

| Clave | Valor gestionado (ejemplo) |
|---|---|
| `KAFKA_BOOTSTRAP` | `ns-dev-ecommerce.servicebus.windows.net:9093` |
| `KAFKA_SECURITY_PROTOCOL` | `SASL_SSL` |

Particularidades de Event Hubs que los servicios deben manejar:

- **SASL/PLAIN + TLS** — usá `SASL_SSL` con usuario `$ConnectionString` y la
  connection string del namespace como password (un secreto `Key Vault →
  ExternalSecret`, nunca un ConfigMap).
- **Los topics son Event Hubs** — la creación se hace en Azure (o via el admin
  SDK de Event Hubs), no por auto-create de Kafka.
  `auto.create.topics.enable` se ignora del lado de Azure.
- **Los consumer groups** deben crearse con anticipación (el ScaledObject de
  KEDA en `cluster/base/keda/` ya referencía `order-worker` como uno).
- Fingerprint de la versión de Kafka: Event Hubs habla protocolo Kafka 2.x —
  los clientes deberían apuntar a `kafka.version=2.0.0`-ish; el ScaledObject
  base usa 2.0.0.

## In-cluster (alternativa dev/local): Strimzi

Mismo patrón de operador + CR que cualquier despliegue de Kafka. Archivos:

- `strimzi/values.yaml` — valores Helm del operador (thin: mira todos los
  namespaces, los CRDs los instala el chart).
- `strimzi/kafka-cluster.yaml` — CR de Kafka: 3 brokers, storage efímero,
  listeners internal + NodePort, placeholder de métricas, entity operator con
  topic + user operators.

```bash
kubectl create ns kafka
helm repo add strimzi https://strimzi.io/charts/
helm install strimzi strimzi/strimzi-kafka-operator -n kafka \
  -f data/kafka/strimzi/values.yaml
kubectl apply -f data/kafka/strimzi/kafka-cluster.yaml

# esperá la readiness
kubectl -n kafka get kafka ecommerce-kafka -w
```

DNS del servicio: `ecommerce-kafka-kafka-bootstrap.kafka.svc.cluster.local:9092`
(plaintext, solo red del clúster). Acceso del host: NodePort 30892.

## Qué NO hacer

- Nunca despliegues ambos caminos para el mismo ambiente (clúster Strimzi Y
  Event Hubs apuntando a los mismos nombres de topic → consumidores con split
  brain).
- No uses storage efímero fuera de dev — los brokers pierden datos al
  reiniciar. Cambiá `spec.kafka.storage` a `persistent-claim` para
  staging/prod.
- No expongas el listener plaintext fuera del clúster. El listener NodePort
  existe solo para dev local; removelo en cualquier cosa compartida.
- Los topics de Event Hubs NO se auto-crean con el `auto.create` estilo
  Strimzi — creá los hubs y consumer groups en Azure antes de los despliegues.