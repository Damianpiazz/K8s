# Configuración de Docker Compose

La mayoría de los usuarios configura floci-az completamente mediante variables de entorno — no se necesitan archivos de configuración.
Cada ajuste `floci-az.*` se corresponde con una variable de entorno `FLOCI_AZ_*` (reemplaza `.` por `_`, en mayúsculas).

---

## Escenarios comunes

### Solo almacenamiento (sin Functions)

La configuración más simple — omite por completo el montaje del socket de Docker:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    environment:
      FLOCI_AZ_SERVICES_FUNCTIONS_ENABLED: "false"
```

### Todos los servicios (por defecto)

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock  # required for Azure Functions
```

### Con almacenamiento persistente

Los datos sobreviven a los reinicios del contenedor. Monta un directorio local y define un modo de almacenamiento:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - ./data:/app/data
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_AZ_STORAGE_MODE: hybrid        # in-memory + async flush every 5 s (recommended)
      # FLOCI_AZ_STORAGE_MODE: wal         # every write goes to disk before responding
      # FLOCI_AZ_STORAGE_MODE: persistent  # flush only on graceful shutdown
```

### Con Azure SQL Database

SQL usa por defecto el modo solo plano de control y no necesita el socket de Docker. Para usar contenedores de SQL Server
administrados, define el proveedor en `managed`, acepta la EULA y monta el socket de Docker:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_AZ_SERVICES_SQL_DATA_PLANE_PROVIDER: managed
      FLOCI_AZ_SERVICES_SQL_ACCEPT_EULA: "Y"   # accept the Microsoft SQL Server EULA
      # FLOCI_AZ_SERVICES_SQL_IMAGE: "mcr.microsoft.com/mssql/server:2025-latest"
```

> Los contenedores de SQL Server vinculan un puerto aleatorio del host directamente mediante Docker — **no** agregues
> esos puertos al bloque `ports:` del servicio `floci-az`. Usa el endpoint `/connect` para descubrir el puerto.

### CI / Efímero — máxima velocidad

Todo en memoria, sin socket requerido, el arranque más rápido:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    environment:
      FLOCI_AZ_STORAGE_MODE: memory
      FLOCI_AZ_SERVICES_FUNCTIONS_ENABLED: "false"
```

### Selección de servicios

Desactiva los servicios que no usas:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    environment:
      FLOCI_AZ_SERVICES_BLOB_ENABLED: "true"
      FLOCI_AZ_SERVICES_QUEUE_ENABLED: "true"
      FLOCI_AZ_SERVICES_TABLE_ENABLED: "false"
      FLOCI_AZ_SERVICES_FUNCTIONS_ENABLED: "false"
      FLOCI_AZ_SERVICES_APP_CONFIG_ENABLED: "false"
```

### Anulación de almacenamiento por servicio

Ejecuta la mayoría de los servicios en memoria, pero usa WAL para la durabilidad de los blobs:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - ./data:/app/data
    environment:
      FLOCI_AZ_STORAGE_MODE: memory
      FLOCI_AZ_STORAGE_SERVICES_BLOB_MODE: wal
```

### Con motores respaldados por Docker (Cosmos MongoDB, Event Hubs…)

Los motores respaldados por Docker (Cosmos MongoDB/PostgreSQL/Cassandra/Gremlin) y los sidecars de Event Hubs
(Artemis, Redpanda) se lanzan como **contenedores hermanos** mediante floci-az a través del socket de Docker.
Vinculan sus puertos directamente en el host — **no** publiques esos puertos en el servicio `floci-az`:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
      - "4578:4578"   # Cosmos DB — Java SDK (HTTPS)
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      # Cosmos engines
      FLOCI_AZ_SERVICES_COSMOS_ENGINES_MONGODB_ENABLED: "true"
      FLOCI_AZ_SERVICES_COSMOS_ENGINES_POSTGRESQL_ENABLED: "true"
      # Event Hubs
      FLOCI_AZ_SERVICES_EVENT_HUB_ENABLED: "true"
```

Una vez que los sidecars se inician, sus puertos están disponibles en el host: `localhost:27017` (MongoDB),
`localhost:5432` (PostgreSQL), `localhost:5672` (AMQP / Artemis).

### Multi-contenedor (tu aplicación + floci-az)

Cuando tu aplicación también se ejecuta en Docker, usa el nombre del servicio como hostname:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    networks:
      - app-net

  my-app:
    image: my-app:latest
    environment:
      AZURE_STORAGE_CONNECTION_STRING: >-
        DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;
        AccountKey=<devstoreaccount1-key>;
        BlobEndpoint=http://floci-az:4577/devstoreaccount1;
        QueueEndpoint=http://floci-az:4577/devstoreaccount1-queue;
        TableEndpoint=http://floci-az:4577/devstoreaccount1-table;
      # App Configuration — https:// required by the SDK; use ForceHttp transport in your client
      AZURE_APPCONFIG_ENDPOINT: https://floci-az:4577/devstoreaccount1-appconfig
    depends_on:
      - floci-az
    networks:
      - app-net

networks:
  app-net:
```

---

## Referencia de variables de entorno

Todas las variables son opcionales; el valor por defecto se aplica cuando no están definidas.

### Core

| Variable | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_PORT` | `4577` | Puerto en el que escucha el emulador |
| `FLOCI_AZ_BASE_URL` | `http://localhost:4577` | URL base incrustada en las respuestas de las APIs |
| `FLOCI_AZ_HOSTNAME` | _(sin definir)_ | Anula el hostname en las URLs de SAS e invocación (útil detrás de un proxy inverso) |
| `FLOCI_AZ_AUTH_MODE` | `dev` | `dev` — acepta cualquier credencial; `strict` — valida firmas HMAC-SHA256 |

### Almacenamiento

| Variable | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_STORAGE_MODE` | `memory` | Backend global de almacenamiento: `memory` · `persistent` · `hybrid` · `wal` |
| `FLOCI_AZ_STORAGE_PERSISTENT_PATH` | `/app/data` | Directorio del lado del contenedor para el estado persistido |
| `FLOCI_AZ_STORAGE_HOST_PERSISTENT_PATH` | _(igual que el anterior)_ | Ruta del lado del host al ejecutar Docker-in-Docker |
| `FLOCI_AZ_STORAGE_WAL_COMPACTION_INTERVAL_MS` | `30000` | Cada cuánto se compacta el WAL (ms) |
| `FLOCI_AZ_STORAGE_HYBRID_FLUSH_INTERVAL_MS` | `5000` | Cada cuánto el modo híbrido vacía a disco (ms) |

### Anulaciones de almacenamiento por servicio

| Variable | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_STORAGE_SERVICES_BLOB_MODE` | _(global)_ | Modo de almacenamiento solo para Blob Storage |
| `FLOCI_AZ_STORAGE_SERVICES_QUEUE_MODE` | _(global)_ | Modo de almacenamiento solo para Queue Storage |
| `FLOCI_AZ_STORAGE_SERVICES_TABLE_MODE` | _(global)_ | Modo de almacenamiento solo para Table Storage |
| `FLOCI_AZ_STORAGE_SERVICES_APP_CONFIG_MODE` | _(global)_ | Modo de almacenamiento solo para App Configuration |

### Habilitar / deshabilitar servicios

| Variable | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_BLOB_ENABLED` | `true` | Habilita o deshabilita Blob Storage |
| `FLOCI_AZ_SERVICES_QUEUE_ENABLED` | `true` | Habilita o deshabilita Queue Storage |
| `FLOCI_AZ_SERVICES_TABLE_ENABLED` | `true` | Habilita o deshabilita Table Storage |
| `FLOCI_AZ_SERVICES_FUNCTIONS_ENABLED` | `true` | Habilita o deshabilita Azure Functions |
| `FLOCI_AZ_SERVICES_APP_CONFIG_ENABLED` | `true` | Habilita o deshabilita App Configuration |
| `FLOCI_AZ_SERVICES_COSMOS_ENABLED` | `true` | Habilita o deshabilita Cosmos DB (SQL API) |
| `FLOCI_AZ_SERVICES_KEY_VAULT_ENABLED` | `true` | Habilita o deshabilita Key Vault |
| `FLOCI_AZ_SERVICES_EVENT_HUB_ENABLED` | `true` | Habilita o deshabilita Event Hubs |
| `FLOCI_AZ_SERVICES_SQL_ENABLED` | `true` | Habilita o deshabilita Azure SQL Database |

### Azure SQL Database

| Variable | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_SQL_DATA_PLANE_PROVIDER` | `none` | `none` solo para estado ARM; `managed` para un contenedor real de SQL Server; `external` está reservado |
| `FLOCI_AZ_SERVICES_SQL_MOCKED` | _(sin definir)_ | Alias de proveedor obsoleto: `true` = `none`, `false` = `managed` |
| `FLOCI_AZ_SERVICES_SQL_ACCEPT_EULA` | `N` | Define `Y` para aceptar la EULA de Microsoft SQL Server (solo modo administrado) |
| `FLOCI_AZ_SERVICES_SQL_IMAGE` | `mcr.microsoft.com/mssql/server:2025-latest` | Imagen Docker para los contenedores de SQL Server |
| `FLOCI_AZ_SERVICES_SQL_STARTUP_TIMEOUT_SECONDS` | `60` | Segundos de espera hasta que SQL Server esté listo |

### Azure Functions

| Variable | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_FUNCTIONS_EPHEMERAL` | `false` | `true` — contenedor nuevo por invocación; `false` — reutiliza contenedores calientes |
| `FLOCI_AZ_SERVICES_FUNCTIONS_CONTAINER_IDLE_TIMEOUT_SECONDS` | `300` | Expulsa los contenedores calientes inactivos por más tiempo que esto; `0` desactiva la expulsión |
| `FLOCI_AZ_SERVICES_FUNCTIONS_CODE_PATH` | `~/.floci-az/functions` | Dónde se almacena en el host el código de funciones extraído |
| `FLOCI_AZ_SERVICES_FUNCTIONS_DOCKER_HOST_OVERRIDE` | _(sin definir)_ | Anula el hostname que usan los contenedores de funciones para llegar a floci-az |

### Daemon de Docker

| Variable | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_DOCKER_DOCKER_HOST` | `unix:///var/run/docker.sock` | Socket del daemon de Docker — socket unix o `tcp://host:port` |
| `FLOCI_AZ_DOCKER_LOG_MAX_SIZE` | `10m` | Tamaño máximo del archivo de log por contenedor de funciones |
| `FLOCI_AZ_DOCKER_LOG_MAX_FILE` | `3` | Máximo de archivos de log rotados por contenedor de funciones |
| `FLOCI_AZ_DOCKER_DOCKER_CONFIG_PATH` | _(sin definir)_ | Ruta al `config.json` de Docker para la autenticación en registries privados |

---

## Acceso al socket de Docker

El montaje del socket de Docker (`/var/run/docker.sock`) es necesario para Azure Functions. El entrypoint
del contenedor detecta automáticamente el ID de grupo del socket en tiempo de ejecución y ajusta los permisos — esto
funciona tanto en Docker Desktop (macOS/Windows) como en Docker Linux nativo sin configuración manual.

Si no necesitas Functions, omite el montaje del socket y define `FLOCI_AZ_SERVICES_FUNCTIONS_ENABLED=false`.

---

## Comprobación de estado

floci-az expone un endpoint de salud que puedes usar en las condiciones de `depends_on`:

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:4577/health"]
      interval: 5s
      timeout: 3s
      retries: 5

  my-app:
    depends_on:
      floci-az:
        condition: service_healthy
```