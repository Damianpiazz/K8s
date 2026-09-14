# Event Grid

Compatible con el SDK de publicación `azure-messaging-eventgrid`, el plano de administración
`azure-resourcemanager-eventgrid` / ARM y cualquier cliente HTTP. Event Grid es la contraparte de
Azure de un enrutador de eventos (publicar/suscribir con fan-out por webhook) — un **Custom Topic**
recibe eventos y los envía a los endpoints **webhook** de los suscriptores.

> **Solo HTTP: sin Docker.** Los topics, las suscripciones, la publicación y la entrega ocurren
> todos en proceso. No hay sidecar.

---

## Características

- **Custom Topics** — CreateOrUpdate, Get, Delete, List (por grupo de recursos y por suscripción),
  con `properties.endpoint` e `inputSchema` (`EventGridSchema` por defecto, o `CloudEventSchemaV1_0`)
- **Access keys** — `listKeys` devuelve `{key1, key2}`; `regenerateKey` rota una de ellas
- **Suscripciones de eventos** — `eventSubscriptions` clásicas con ámbito, con destino
  **WebHook**, `filter` (`subjectBeginsWith`, `subjectEndsWith`, `includedEventTypes`,
  `isSubjectCaseSensitive`) y `retryPolicy`
- **Publicar** — `POST /api/events` acepta una matriz JSON de eventos en el esquema **Event Grid**
  o **CloudEvents 1.0**
- **Entrega** — los eventos se distribuyen asincrónicamente a los suscriptores que coinciden, con
  reintentos según el `retryPolicy` de la suscripción y backoff exponencial
- **Handshake de validación** — la creación de una suscripción webhook dispara un
  `Microsoft.EventGrid.SubscriptionValidationEvent` (o, para CloudEvents, la sonda `OPTIONS` de
  protección contra abuso)

---

## Endpoints

Las operaciones de administración usan rutas ARM; la publicación usa el endpoint del plano de
datos del topic.

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.EventGrid/topics/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.EventGrid/topics/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.EventGrid/topics/{name}
POST   .../topics/{name}/listKeys
POST   .../topics/{name}/regenerateKey         # body: {"keyName":"key1"|"key2"}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.EventGrid/topics
GET    /subscriptions/{sub}/providers/Microsoft.EventGrid/topics

PUT    {topicId}/providers/Microsoft.EventGrid/eventSubscriptions/{name}
GET    {topicId}/providers/Microsoft.EventGrid/eventSubscriptions/{name}
DELETE {topicId}/providers/Microsoft.EventGrid/eventSubscriptions/{name}
GET    {topicId}/providers/Microsoft.EventGrid/eventSubscriptions

POST   /{name}-eventgrid/api/events            # data-plane publish (topic endpoint)
```

---

## Inicio rápido

### 1 — Crear un topic

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.EventGrid/topics/my-topic?api-version=2025-02-15" \
  -H "Content-Type: application/json" \
  -d '{"location":"eastus","properties":{"inputSchema":"EventGridSchema"}}'
```

La respuesta incluye el endpoint del plano de datos:

```json
{
  "name": "my-topic",
  "type": "Microsoft.EventGrid/topics",
  "properties": {
    "provisioningState": "Succeeded",
    "endpoint": "http://localhost:4577/my-topic-eventgrid/api/events",
    "inputSchema": "EventGridSchema"
  }
}
```

Obtenga las claves con `POST .../topics/my-topic/listKeys` → `{"key1":"…","key2":"…"}`.

### 2 — Suscribir un webhook

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.EventGrid/topics/my-topic/providers/Microsoft.EventGrid/eventSubscriptions/my-sub-1?api-version=2025-02-15" \
  -H "Content-Type: application/json" \
  -d '{
    "properties": {
      "destination": {"endpointType": "WebHook", "properties": {"endpointUrl": "https://my-app.example/hook"}},
      "filter": {"subjectBeginsWith": "/orders"}
    }
  }'
```

El emulador ejecuta de inmediato el handshake de validación contra el webhook (un
`SubscriptionValidationEvent`; el suscriptor debe devolver `{"validationResponse": "<code>"}`).

### 3 — Publicar eventos

```python
from azure.core.credentials import AzureKeyCredential
from azure.eventgrid import EventGridPublisherClient, EventGridEvent

client = EventGridPublisherClient(
    "http://localhost:4577/my-topic-eventgrid/api/events", AzureKeyCredential("<key1>"))
client.send([EventGridEvent(
    subject="/orders/123", event_type="Order.Created",
    data={"orderId": 123}, data_version="1.0")])
```

Los eventos cuyo `subject` coincide con el filtro de la suscripción se publican (como matriz JSON)
en el webhook, con `topic` establecido en el id de recurso completo del topic y un header
`aeg-event-type: Notification`.

---

## Configuración

```yaml
floci-az:
  services:
    event-grid:
      enabled: true
      default-region: eastus          # region label baked into the topic endpoint host text
      max-delivery-attempts: 30        # default when a subscription omits its own retryPolicy
```

| Variable de entorno | Por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_EVENT_GRID_ENABLED` | `true` | Habilita o deshabilita el servicio |
| `FLOCI_AZ_SERVICES_EVENT_GRID_DEFAULT_REGION` | `eastus` | Etiqueta de región para el endpoint del topic |
| `FLOCI_AZ_SERVICES_EVENT_GRID_MAX_DELIVERY_ATTEMPTS` | `30` | Intentos de entrega por defecto |

---

## Notas y limitaciones

- **Solo destinos WebHook.** Los destinos Service Bus, Event Hub, Storage Queue y Azure Function,
  además de Domains, Partner/System Topics y la superficie del Event Grid Namespace (MQTT/pull),
  quedan fuera del alcance.
- **El dead-lettering es best-effort.** Cuando la entrega agota
  `retryPolicy.maxDeliveryAttempts`, el evento se registra y se descarta; no se escribe en un
  contenedor de blobs `deadLetterDestination`.
- **Autenticación permisiva.** El header `aeg-sas-key` se acepta pero no se valida contra las claves
  del topic (modo de desarrollo), igual que el resto del emulador.
- **`CustomEventSchema`** se acepta pero se trata como el esquema Event Grid.
- **Advanced filters** (`advancedFilters`, filtrado por matrices) se aceptan pero no se evalúan.