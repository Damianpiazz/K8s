# Queue Storage

Compatible con los SDK `azure-storage-queue` (Java, Python, Node.js), la CLI de Azure
(`az storage queue`) y las cadenas de conexión estilo Azurite. Habla el protocolo REST de Azure Storage Queue
con autenticación de clave compartida y respuestas XML.

> **Solo HTTP — sin Docker.** Los datos los guarda el [backend de almacenamiento](../configuration/storage.md) configurado
> (`memory` por defecto; `persistent`, `hybrid` o `wal` para durabilidad).

---

## Características

- **Colas** — Crear, Eliminar, Obtener/establecer metadatos; la creación duplicada es idempotente, y una cola
  inexistente devuelve la forma Azure `404 QueueNotFound`
- **Mensajes** — Encolar (enviar), Desencolar (recibir), Eliminar, Inspeccionar; múltiples mensajes en tránsito
- **Inspeccionar (peek) no consume** — `?peekonly=true` devuelve los mensajes sin incrementar el contador de
  desencolado ni ocultarlos
- **Tiempo de visibilidad** — un mensaje recibido queda oculto durante su `visibilitytimeout` y reaparece
  cuando este expira
- **Validación de pop receipt** — eliminar/actualizar requieren el pop receipt actual; un receipt obsoleto se
  rechaza, y actualizar un mensaje rota el receipt
- **TTL de mensajes** — los mensajes expiran y se eliminan después del `messagettl`
- **Actualizar mensaje** — reemplaza el contenido y restablece la visibilidad, devolviendo un pop receipt nuevo

## Endpoint

```
http://localhost:4577/{account}-queue/{queue}                    # queue operations
http://localhost:4577/{account}-queue/{queue}/messages           # message operations
```

La cuenta también responde en la dirección estilo host `{account}.queue.core.windows.net` cuando se
establece el encabezado `Host`.

## Inicio rápido

=== "Python"

    ```python
    from azure.storage.queue import QueueClient

    conn = ("DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
            "AccountKey=<devstoreaccount1-key>;"
            "QueueEndpoint=http://localhost:4577/devstoreaccount1-queue;")
    queue = QueueClient.from_connection_string(conn, "my-queue")
    queue.create_queue()
    queue.send_message("hello world")
    msg = queue.receive_message()
    print(msg.content)
    queue.delete_message(msg)
    ```

=== "Azure CLI"

    ```bash
    az storage message put --queue-name my-queue --content "hello world" \
      --connection-string "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=...;QueueEndpoint=http://localhost:4577/devstoreaccount1-queue;"
    ```

La clave de desarrollo bien conocida de Azurite (`<devstoreaccount1-key>`) se acepta; cualquier nombre de cuenta funciona.

## Configuración

```yaml
floci-az:
  services:
    queue:
      enabled: true
  storage:
    services:
      queue:
        # mode: persistent     # override the global storage.mode for queue only
        flush-interval-ms: 5000
```

| Propiedad | Variable de entorno | Valor por defecto | Descripción |
|---|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_QUEUE_ENABLED` | `true` | Habilita el servicio Queue Storage |
| `storage.services.queue.mode` | `FLOCI_AZ_STORAGE_SERVICES_QUEUE_MODE` | *(hereda de `storage.mode`)* | Anulación del backend por servicio (`memory` / `persistent` / `hybrid` / `wal`) |
| `storage.services.queue.flush-interval-ms` | `FLOCI_AZ_STORAGE_SERVICES_QUEUE_FLUSH_INTERVAL_MS` | `5000` | Intervalo de vaciado a disco en segundo plano solo para el modo `hybrid`; lo ignoran `memory` / `persistent` / `wal` (`wal` compacta según `storage.wal.compaction-interval-ms`) |

## Desviaciones intencionales

- **Las firmas de clave compartida se aceptan pero no se verifican criptográficamente.**
- **Sin aplicación de SAS** — los parámetros de consulta SAS se analizan pero no se validan.
- **El contador de desencolado de mensajes** se rastrea, pero la gestión de mensajes tóxicos `maxDequeueCount` no se modela.