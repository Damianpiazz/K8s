# Service Bus

Emulación de Azure Service Bus con un **plano de administración** para la topología de entidades (colas, temas,
suscripciones) por HTTP, y un **plano de datos AMQP 1.0** respaldado por un sidecar Apache ActiveMQ Artemis
gestionado automáticamente por floci-az.

| Plano | Protocolo | Transporte | Puerto |
|---|---|---|---|
| Administración | HTTP | `/{account}-servicebus/` en `:4577` | `4577` |
| Datos | AMQP 1.0 | sidecar Apache ActiveMQ Artemis | `5673` (AMQP) / `5674` (AMQPS) |

La topología de entidades se crea dinámicamente a través de la API de administración (o se crea automáticamente en el primer
uso por el SDK), y también se puede **aprovisionar previamente de forma declarativa** desde un archivo `Config.json` en el
formato del emulador oficial de Service Bus — ver [Topología declarativa](#topología-declarativa-configjson).

> **Modo simulado (por defecto).** Con `mocked: true` el sidecar de Artemis no se inicia: la API de
> administración responde, pero el plano de datos AMQP no está disponible. Configura `mocked: false` (y expón los puertos
> AMQP) para enviar y recibir mensajes.

## Plano de administración

El CRUD de entidades se sirve por HTTP en `/{account}-servicebus/`:

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/{account}-servicebus/$Resources/queues` | Listar colas |
| `PUT` / `DELETE` | `/{account}-servicebus/{queue}` | Crear / eliminar una cola |
| `GET` | `/{account}-servicebus/$Resources/topics` | Listar temas |
| `PUT` / `DELETE` | `/{account}-servicebus/{topic}` | Crear / eliminar un tema |
| `GET` | `/{account}-servicebus/{topic}/subscriptions` | Listar suscripciones |
| `PUT` / `GET` / `DELETE` | `/{account}-servicebus/{topic}/subscriptions/{sub}` | Gestionar una suscripción |
| `GET` | `/{account}-servicebus/{topic}/subscriptions/{sub}/rules` | Listar las reglas de una suscripción |
| `PUT` / `GET` / `DELETE` | `/{account}-servicebus/{topic}/subscriptions/{sub}/rules/{rule}` | Gestionar una regla de suscripción |

## Topología declarativa (Config.json)

Al iniciarse, floci-az aplica un archivo de topología declarativa en el
[formato `Config.json` del emulador oficial de Service Bus](https://learn.microsoft.com/azure/service-bus-messaging/test-locally-with-service-bus-emulator)
— el mismo archivo que la integración de hosting de Service Bus de .NET Aspire escribe desde el modelo AppHost. La
ubicación del archivo se resuelve en orden:

1. `floci-az.services.service-bus.topology-file` (env `FLOCI_AZ_SERVICES_SERVICE_BUS_TOPOLOGY_FILE`)
2. `/ServiceBus_Emulator/ConfigFiles/Config.json` — la ruta de montaje del emulador oficial, de modo que un volumen
   preparado para el emulador oficial funcione sin cambios

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    volumes:
      - ./Config.json:/ServiceBus_Emulator/ConfigFiles/Config.json:ro
      - /var/run/docker.sock:/var/run/docker.sock
```

Los espacios de nombres, colas, temas, suscripciones y reglas del archivo se crean a través de los mismos
caminos de código que la API de administración, antes de que se conecte cualquier cliente — útil para aplicaciones cuyos
consumidores se conectan a entidades preaprovisionadas y nunca las crean. Semántica:

- El primer espacio de nombres enlaza los puertos AMQP configurados (`amqp-port`/`amqp-tls-port`); el emulador
  oficial admite un único espacio de nombres, y los espacios de nombres adicionales obtienen puertos dinámicos.
- Un archivo de topología detectado tiene prioridad sobre `start-on-boot`; su primer espacio de nombres es dueño de los
  puertos configurados, y no se inicia ningún espacio de nombres `default` adicional.
- Una suscripción con `Rules` declaradas obtiene exactamente esas reglas — se elimina el `$Default`
  TrueFilter implícito, igual que en el emulador oficial. Una suscripción sin reglas conserva `$Default`.
- El reemplazo de reglas es atómico. Si una recarga de una suscripción existente contiene una regla no válida,
  el conjunto completo de reglas anterior permanece activo; una suscripción nueva conserva solo las declaraciones válidas.
- Las propiedades de las entidades respetan la misma validación que la API de administración (`LockDuration` ≤ `PT5M`,
  `MaxDeliveryCount` 1–2000, ventana de detección de duplicados `PT20S`–`P7D`).
- `ForwardTo`/`ForwardDeadLetteredMessagesTo` no se emulan y registran una advertencia si están presentes.
- La carga es de mejor esfuerzo: una entidad no válida se omite con un registro `ERROR` y el resto de la
  topología sigue cargando; un archivo ausente o no analizable nunca hace fallar el inicio.

## Reglas y filtros de suscripción

Las suscripciones filtran qué mensajes de un tema reciben mediante **reglas** nombradas, acordes con la semántica
de Azure:

- Cada suscripción nueva comienza con la regla implícita **`$Default`** (un `TrueFilter` que acepta
  todo). El flujo usual del SDK — agregar la regla real y luego eliminar `$Default` — funciona igual que en Azure,
  al igual que pasar un `DefaultRuleDescription` en el cuerpo de creación de la suscripción
  (`CreateSubscriptionAsync(subscriptionOptions, ruleOptions)`).
- **`CorrelationFilter`** — coincidencia exacta (sensible a mayúsculas) de tipo AND sobre `CorrelationId`,
  `Label`/`Subject`, `SessionId` y propiedades de la aplicación.
- **`SqlFilter`** — expresiones SQL92 sobre propiedades de la aplicación y `sys.CorrelationId`,
  `sys.Label`, `sys.Subject`, `sys.SessionId` (incluidos `LIKE`, `IN`, `BETWEEN`, `IS NULL`,
  `EXISTS(prop)`, operadores aritméticos y booleanos).
- **`TrueFilter`** / **`FalseFilter`** — aceptar-todo / no-aceptar-nada.
- Varias reglas se combinan como un **OR** lógico y entregan una **única** copia de un mensaje que coincide.
  Una suscripción cuyas reglas se hayan eliminado por completo no recibe nada.

Los filtros se compilan a [selectores de cola de Artemis](https://activemq.apache.org/components/artemis/documentation/latest/filter-expressions.html),
de modo que la evaluación ocurre dentro del broker en el momento del enrutamiento — los mensajes que no coinciden con las
reglas de una suscripción nunca se enrutan hacia ella (sin inflación del contador de entregas, sin envíos espurios a la
cola de mensajes fallidos).

**Desviaciones del emulador respecto de Azure:**

- Los filtros sobre `MessageId`, `To`, `ReplyTo`, `ReplyToSessionId` o `ContentType` (y sus formas `sys.*`)
  se **rechazan con HTTP 400** — estos campos AMQP no tienen un mapeo de selector en el lado del broker.
- **Las acciones de reglas** (`SqlRuleAction`) se almacenan y se reflejan de vuelta por la API de administración, pero
  **no se aplican** a los mensajes entregados, y las reglas con acciones no producen copias adicionales de mensajes.
- Los nombres de propiedades en los filtros son sensibles a mayúsculas y deben ser identificadores de selector válidos
  (letras, dígitos, `_`, `$`). Los valores de filtros de correlación tipados `int`/`long`/`double`/`boolean`
  etc. se comparan con su tipo declarado; otros tipos no string se comparan como cadenas.
- Los cambios de reglas actualizan el filtro de la suscripción en su lugar (los mensajes ya enrutados a la
  suscripción permanecen, los receptores se mantienen conectados) y, como en Azure, se aplican solo a mensajes futuros.
- Si falla la persistencia de un cambio de regla, el emulador reintenta restaurar el filtro anterior del broker
  y devuelve HTTP 500. Si todos los intentos de restauración fallan, registra la inconsistencia y deja
  el broker en ejecución para preservar los mensajes en cola y las conexiones de los clientes. El filtro del broker
  puede diferir entonces de las reglas almacenadas hasta que una actualización posterior exitosa de reglas las reconcilie.

## Sesiones de mensajes

Las colas y suscripciones creadas con `RequiresSession` admiten los receptores de sesión de los SDK de Azure.
Establece `SessionId` en los mensajes enviados y luego usa un receptor de sesión específica, un receptor de
aceptar-la-siguiente-sesión o un procesador de sesiones. El broker traduce el filtro de sesión AMQP de Azure a un
selector `JMSXGroupID` de Artemis, lo que mantiene cada sesión en un receptor y preserva el orden FIFO
dentro de esa sesión. Las respuestas de conexión incluyen la propiedad `com.microsoft:locked-until-utc` de Azure.

La propiedad de la sesión dura mientras dura el enlace del receptor. El estado de la sesión y la renovación explícita del
bloqueo de sesión no se emulan actualmente.

Lee la subcola de mensajes fallidos de una entidad con sesiones habilitadas con un receptor común. Los SDK de Azure no
exponen receptores de sesión para subcolas de mensajes fallidos, y recibir de esa subcola no
requiere un bloqueo de sesión.

## Inspección de mensajes

Los receptores de colas y suscripciones admiten las llamadas `peekMessages()` de los SDK de Azure a través del nodo AMQP
`$management` de cada entidad. La inspección preserva los cuerpos de los mensajes, las propiedades del sistema y las
propiedades de la aplicación; admite `maxMessages` y `fromSequenceNumber`; y no bloquea, elimina ni cambia el
contador de entregas. Cada llamada devuelve como máximo el límite de 250 mensajes de Azure y puede devolver menos
cuando sea necesario para mantener acotada la respuesta AMQP. Las entidades vacías devuelven un resultado vacío.

## Cadena de conexión

```
Endpoint=sb://localhost:5673;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=devkey;UseDevelopmentEmulator=true;
```

`UseDevelopmentEmulator=true` le indica al SDK usar AMQP simple (sin TLS). El valor de `SharedAccessKey` se
ignora — Artemis se ejecuta sin autenticación en modo de desarrollo.

## Endpoint determinista para orquestadores

El plano de datos AMQP siempre enlaza los puertos de host configurados (`amqp-port`, por defecto `5673`;
`amqp-tls-port`, por defecto `5674`), de modo que un orquestador que inicia floci-az conoce el endpoint de Service Bus
de antemano — no hay ningún puerto dinámico que descubrir. Dos piezas más completan la historia:

- **`start-on-boot: true`** inicia el espacio de nombres `default` (y su sidecar de Artemis) junto con
  el emulador, en lugar de hacerlo en la primera llamada de gestión de entidades. Sin él, nada escucha en el
  puerto AMQP hasta que se crea una cola, un tema o un espacio de nombres, lo que rompe los health checks y los clientes
  que se conectan al iniciarse.
- **`GET /{account}-servicebus/namespaces`** informa el `amqpPort`/`amqpsPort` real de cada espacio de nombres en
  ejecución, para las herramientas que quieren verificar o descubrir el endpoint en tiempo de ejecución.

Esto es en lo que deben confiar las integraciones de hosting (p. ej. .NET Aspire): pasa
`FLOCI_AZ_SERVICES_SERVICE_BUS_AMQP_PORT` (con `..._MOCKED=false` y `..._START_ON_BOOT=true`),
y luego entrega a los clientes `Endpoint=sb://<host>:<ese puerto>;...;UseDevelopmentEmulator=true;`.

## SDK de Python

```python
from azure.servicebus import ServiceBusClient, ServiceBusMessage

CONN = (
    "Endpoint=sb://localhost:5673;"
    "SharedAccessKeyName=RootManageSharedAccessKey;"
    "SharedAccessKey=devkey;"
    "UseDevelopmentEmulator=true;"
)

with ServiceBusClient.from_connection_string(CONN) as client:
    with client.get_queue_sender("myqueue") as sender:
        sender.send_messages(ServiceBusMessage("hello world"))

    with client.get_queue_receiver("myqueue", max_wait_time=5) as receiver:
        for msg in receiver:
            print(str(msg))
            receiver.complete_message(msg)
```

## SDK de .NET

```csharp
await using var client = new ServiceBusClient(
    "Endpoint=sb://localhost:5673;SharedAccessKeyName=RootManageSharedAccessKey;" +
    "SharedAccessKey=devkey;UseDevelopmentEmulator=true;");
ServiceBusSender sender = client.CreateSender("myqueue");
await sender.SendMessageAsync(new ServiceBusMessage("hello world"));
```

El sidecar de Artemis incluye el mecanismo SASL anónimo `MSSBCBS` que espera
`Azure.Messaging.ServiceBus`; la autorización continúa a través del enlace CBS estándar.

## Configuración

### Docker Compose

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"   # floci-az HTTP (management plane)
      - "5673:5673"   # Service Bus AMQP (Artemis)
      - "5674:5674"   # Service Bus AMQPS (Artemis)
    environment:
      FLOCI_AZ_SERVICES_SERVICE_BUS_ENABLED: "true"
      FLOCI_AZ_SERVICES_SERVICE_BUS_MOCKED: "false"
      FLOCI_AZ_SERVICES_SERVICE_BUS_START_ON_BOOT: "true"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

### Variables de entorno

| Variable | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_SERVICE_BUS_ENABLED` | `true` | Habilitar/deshabilitar el servicio |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_MOCKED` | `true` | Modo simulado (solo plano de administración, sin Artemis) |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_START_ON_BOOT` | `false` | Iniciar el espacio de nombres `default` con el emulador cuando no se detecta ningún archivo de topología |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_TOPOLOGY_FILE` | *(sin establecer)* | Ruta a un `Config.json` de topología declarativa; cuando no se establece, se sondea `/ServiceBus_Emulator/ConfigFiles/Config.json` |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_AMQP_PORT` | `5673` | Puerto de host para AMQP (Artemis) |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_AMQP_TLS_PORT` | `5674` | Puerto de host para AMQPS |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_ARTEMIS_IMAGE` | `apache/activemq-artemis:2.44.0` | Imagen de Artemis; debe coincidir con los parches de protocolo incluidos |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_MAX_DELIVERY_COUNT` | `10` | Máximo de intentos de entrega por defecto antes de enviar a mensajes fallidos, para entidades que no establecen `MaxDeliveryCount` |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_LOCK_DURATION_SECONDS` | `60` | Duración por defecto del bloqueo de inspección, para entidades que no establecen `LockDuration` |

### application.yml

```yaml
floci-az:
  services:
    service-bus:
      enabled: true
      mocked: true              # true = management plane only, no Docker. false = real Artemis sidecar
      start-on-boot: false      # true = default namespace starts when no topology file is discovered
      amqp-port: 5673
      amqp-tls-port: 5674
      artemis-image: "apache/activemq-artemis:2.44.0"
      max-delivery-count: 10      # default when the entity omits MaxDeliveryCount
      lock-duration-seconds: 60   # default when the entity omits LockDuration
```

### Configuración de entrega por entidad

Las colas y suscripciones respetan `MaxDeliveryCount` (1–2000) y `LockDuration`
(hasta `PT5M`) del payload de creación de la entidad, igual que Azure. Un mensaje se
envía a la cola de mensajes fallidos una vez que su contador de entregas supera el `MaxDeliveryCount` de la entidad.
`LockDuration` se aplica a los bloqueos de sesión y a las entregas individuales de bloqueo de inspección sin sesión,
incluidas las colas de mensajes fallidos. Los mensajes caducados quedan
disponibles para otros receptores mientras el receptor original permanece abierto; la resolución tardía
devuelve `MessageLockLost`. Las entidades que omiten cualquiera de las dos propiedades vuelven
a los valores por defecto configurados arriba.

## Metadatos de los mensajes recibidos

Los mensajes recibidos exponen los valores `SequenceNumber` y `EnqueuedTime` asignados por el broker, que coinciden con la
inspección del mismo mensaje en la misma entidad. Esto incluye receptores de sesión, colas de mensajes fallidos,
`ReceiveAndDelete` y mensajes grandes transmitidos en flujo. La reentrega por abandono y por expiración del bloqueo
preserva ambos valores; el envío a mensajes fallidos preserva la marca de tiempo original de encolado.

Los números de secuencia usan los IDs de mensaje del broker de Artemis, como ya lo hace el peek. Pueden contener huecos,
y mover un mensaje a una cola de mensajes fallidos puede asignar un ID nuevo. La asignación de secuencias consecutivas
por entidad de Azure no se emula.

## Fuera de alcance (trabajo futuro)

- Estado de sesión y renovación explícita del bloqueo de sesión
- Renovación explícita del bloqueo de mensajes
- Mensajes diferidos y reenvío automático
- Transacciones de mensajes
- Recuperación ante desastres geográfica y entidades particionadas