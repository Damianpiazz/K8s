# Event Hubs

Emulación de Azure Event Hubs respaldada por dos contenedores sidecar ligeros administrados
automáticamente por floci-az:

| Protocolo | Sidecar | Puerto |
|---|---|---|
| AMQP 1.0 | Apache ActiveMQ Artemis | `5672` |
| Kafka (opcional) | Redpanda | `9093` |

## Cadena de conexión

```
Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=devkey;UseDevelopmentEmulator=true;
```

`UseDevelopmentEmulator=true` indica al SDK que use AMQP simple (sin TLS). El valor de
`SharedAccessKey` se ignora — Artemis se ejecuta sin autenticación en modo de desarrollo.

## SDK de Python

```python
from azure.eventhub import EventHubProducerClient, EventHubConsumerClient

CONNECTION_STR = (
    "Endpoint=sb://localhost;"
    "SharedAccessKeyName=RootManageSharedAccessKey;"
    "SharedAccessKey=devkey;"
    "UseDevelopmentEmulator=true;"
)
EVENTHUB_NAME = "eh1"

# Send events
producer = EventHubProducerClient.from_connection_string(
    conn_str=CONNECTION_STR,
    eventhub_name=EVENTHUB_NAME,
)
with producer:
    batch = producer.create_batch()
    batch.add(EventData("hello world"))
    producer.send_batch(batch)

# Receive events
consumer = EventHubConsumerClient.from_connection_string(
    conn_str=CONNECTION_STR,
    consumer_group="$Default",
    eventhub_name=EVENTHUB_NAME,
)
def on_event(partition_context, event):
    print(event.body_as_str())
    partition_context.update_checkpoint()

with consumer:
    consumer.receive(on_event=on_event, starting_position="-1")
```

## SDK de Java

```java
EventHubProducerClient producer = new EventHubClientBuilder()
    .connectionString(
        "Endpoint=sb://localhost;"
        + "SharedAccessKeyName=RootManageSharedAccessKey;"
        + "SharedAccessKey=devkey;"
        + "UseDevelopmentEmulator=true;",
        "eh1")
    .buildProducerClient();

EventDataBatch batch = producer.createBatch();
batch.tryAdd(new EventData("hello world"));
producer.send(batch);
producer.close();
```

## Configuración

### Docker Compose

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"   # floci-az HTTP
      - "5672:5672"   # Event Hubs AMQP (Artemis)
      - "9093:9093"   # Event Hubs Kafka (Redpanda, optional)
    environment:
      FLOCI_AZ_SERVICES_EVENT_HUB_ENABLED: "true"
      FLOCI_AZ_SERVICES_EVENT_HUB_DEFAULT_NAMESPACE: "emulatorNs1"
      FLOCI_AZ_SERVICES_EVENT_HUB_ENTITIES: "eh1:4,eh2:2"
      FLOCI_AZ_SERVICES_EVENT_HUB_KAFKA_ENABLED: "true"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

### Variables de entorno

| Variable | Por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_EVENT_HUB_ENABLED` | `true` | Habilita o deshabilita el servicio |
| `FLOCI_AZ_SERVICES_EVENT_HUB_DEFAULT_NAMESPACE` | `emulatorNs1` | Nombre del espacio de nombres AMQP |
| `FLOCI_AZ_SERVICES_EVENT_HUB_ENTITIES` | `eh1:4` | Pares `name:partitions` separados por comas |
| `FLOCI_AZ_SERVICES_EVENT_HUB_AMQP_PORT` | `5672` | Puerto del host para AMQP (Artemis) |
| `FLOCI_AZ_SERVICES_EVENT_HUB_KAFKA_ENABLED` | `false` | Habilita el endpoint compatible con Kafka |
| `FLOCI_AZ_SERVICES_EVENT_HUB_KAFKA_PORT` | `9093` | Puerto del host para Kafka (Redpanda) |
| `FLOCI_AZ_SERVICES_EVENT_HUB_ARTEMIS_IMAGE` | `apache/activemq-artemis:2.44.0` | Imagen de Artemis; debe coincidir con la versión de Artemis contra la que se compiló floci-az, porque los jars AMQP parcheados que copia son específicos de la versión |
| `FLOCI_AZ_SERVICES_EVENT_HUB_REDPANDA_IMAGE` | `redpandadata/redpanda:latest` | Imagen de Redpanda |

### application.yml

```yaml
floci-az:
  services:
    event-hub:
      enabled: true
      default-namespace: emulatorNs1
      entities: "eh1:4"
      amqp-port: 5672
      kafka-enabled: false
      kafka-port: 9093
```

## Soporte para múltiples espacios de nombres

Cada espacio de nombres de Event Hubs recibe su propio contenedor Artemis aislado con puertos
asignados dinámicamente. El espacio de nombres por defecto se inicia automáticamente desde la
configuración. Se pueden crear espacios de nombres adicionales en tiempo de ejecución mediante la
API de administración.

### API de administración de espacios de nombres

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/{account}-eventhub/namespaces` | Lista todos los espacios de nombres |
| `PUT` | `/{account}-eventhub/namespaces/{ns}` | Crea un espacio de nombres nuevo |
| `DELETE` | `/{account}-eventhub/namespaces/{ns}` | Elimina un espacio de nombres |
| `GET` | `/{account}-eventhub/namespaces/{ns}/connection` | Obtiene la información de conexión (puertos) |
| `GET` | `/{account}-eventhub/namespaces/{ns}/tls-cert` | Obtiene el PEM del certificado TLS |

#### Crear un espacio de nombres

```bash
curl -X PUT http://localhost:4577/devstoreaccount1-eventhub/namespaces/myns \
  -H 'Content-Type: application/json' \
  -d '{"entities":"eh1:4,eh2:2","consumerGroups":"$Default,my-group"}'
```

Respuesta `201`:
```json
{"name":"myns","amqpPort":47123,"amqpsPort":47124}
```

#### Obtener información de conexión

```bash
curl http://localhost:4577/devstoreaccount1-eventhub/namespaces/myns/connection
```

```json
{
  "namespace": "myns",
  "amqpPort": 47123,
  "amqpsPort": 47124,
  "amqpEndpoint": "amqp://localhost:47123",
  "amqpsEndpoint": "amqps://localhost:47124"
}
```

Conéctese con el SDK de Python usando el puerto asignado dinámicamente:

```python
conn_str = (
    "Endpoint=sb://localhost;"
    "SharedAccessKeyName=RootManageSharedAccessKey;"
    "SharedAccessKey=devkey;"
    "UseDevelopmentEmulator=true;"
)
producer = EventHubProducerClient.from_connection_string(
    conn_str=conn_str,
    eventhub_name="eh1",
)
```

> Nota: `UseDevelopmentEmulator=true` omite TLS; el SDK se conecta por el puerto AMQP simple. Para
> usar el puerto dinámico del espacio de nombres, construya la cadena de conexión con el puerto
> explícito: `Endpoint=sb://localhost:47123;...`

## Comprobación de estado

```
GET /{account}-eventhub/health
```

Devuelve `200` cuando el broker AMQP del espacio de nombres por defecto es alcanzable, y `503` en
caso contrario:

```json
{"amqp":{"port":5672,"status":"up"},"amqps":{"port":5671,"status":"up"},"kafka":{"enabled":false}}
```

## Grupos de consumidores

Los grupos de consumidores se declaran en la configuración `consumer-groups` (separados por
comas). El grupo `$Default` siempre se crea automáticamente. Los grupos se aprovisionan en cada
espacio de nombres al inicio mediante la API Jolokia de Artemis.

## Limitaciones

- La configuración de entidades para espacios de nombres adicionales usa los mismos valores por
  defecto que el espacio de nombres por defecto, salvo que se anulen en el cuerpo del `PUT`
- Schema Registry y Kafka Capture quedan fuera del alcance para v1