# Fix 14: Kafka local vía contenedor standalone (el emulador no levanta Redpanda)

El emulador Floci-AZ anuncia en su UI un endpoint Kafka en el puerto 9093,
pero no implementa el sidecar que debería servirlo. Se resolvió con un
contenedor Redpanda standalone, ejecutado aparte del emulador.

## Error observado

- `FLOCI_AZ_SERVICES_EVENT_HUB_KAFKA_ENABLED=true` no producía ningún sidecar
  Kafka: no aparecía contenedor ni proceso asociado.
- El puerto 9093 que la UI del emulador muestra como endpoint de Event Hubs
  permanecía mudo (nada respondía).
- `docker compose` mapeaba `9093:9093`, pero el puerto lo tomaba
  `com.docker.backend` (proceso de Docker Desktop), no un broker.

## Por qué ocurre

`floci-az:latest` implementa solo Artemis/AMQP (el broker arranca tras un PUT
de namespace) y **no** implementa el spawn de Redpanda que el flag de
configuración sugiere. La UI muestra el endpoint esperado (9093), pero ningún
proceso lo sirve.

## Fix aplicado

1. **Quitar el mapeo del emulador** en `infra/floci/docker-compose.yml`:
   eliminado `9093:9093` para liberar el puerto que ocupaba
   `com.docker.backend`.
2. **Contenedor Redpanda standalone** `floci-az-kafka-sc` (imagen
   `redpandadata/redpanda:latest`):

```bash
docker run -d --name floci-az-kafka-sc \
  -p 9093:9092 \
  redpandadata/redpanda:latest \
  --overprovisioned --smp 1 --memory 512M --reserve-memory 0M --node-id 0 --check=false \
  --set redpanda.advertised_kafka_api[0].address=host.docker.internal \
  --set redpanda.advertised_kafka_api[0].port=9093
```

Detalles:

- El flag `--advertised-kafka-addr` **no existe** en el wrapper `rpk`: falla
  con `unrecognised option`. La dirección anunciada se configura con `--set`
  sobre `redpanda.advertised_kafka_api` (dirección `host.docker.internal`,
  puerto `9093`).
- El advertised por defecto (`127.0.0.1:9092`) no sirve a los pods de k3s:
  necesitan la dirección del host.
- Topics creados: `order-events` y `payment-events`.

Caveat: el standalone **auto-crea topics con 1 partición** (el PUT de
namespace solicitaba 3). Si el contrato exige 3 particiones, hay que
reconciliarlo.

## Cómo verificar

```bash
docker exec floci-az-kafka-sc rpk cluster status
docker exec floci-az-kafka-sc rpk topic list
```

- `rpk cluster status` muestra el broker `0*` con dirección
  `host.docker.internal` y puerto `9093`.
- `rpk topic list` lista `order-events` y `payment-events`.