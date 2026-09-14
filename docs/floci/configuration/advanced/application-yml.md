# Referencia de application.yml

Floci-AZ es una aplicación Quarkus. Todos los ajustes se pueden anular mediante variables de entorno
(reemplaza `.` por `_` y usa mayúsculas, p. ej. `floci-az.storage.mode` → `FLOCI_AZ_STORAGE_MODE`).

```yaml
floci-az:
  port: 4577
  base-url: http://localhost:4577

  # When set, overrides the hostname used in URLs returned by the API
  # (e.g. blob SAS URLs, function invoke URLs).
  # hostname: myhost.internal

  auth:
    # dev:    accept any credentials without signature validation (default)
    # strict: validate HMAC-SHA256 shared-key signatures
    mode: dev

  storage:
    # Global default — applies to every service unless overridden below.
    # Supported: memory | persistent | hybrid | wal
    mode: memory
    path: /app/data

    wal:
      compaction-interval-ms: 30000

    hybrid:
      flush-interval-ms: 5000

    # Per-service storage overrides (uncomment to activate)
    services:
      blob:
        # mode: wal
        flush-interval-ms: 5000
      queue:
        # mode: hybrid
        flush-interval-ms: 5000
      table:
        # mode: persistent
        flush-interval-ms: 5000
      app-config:
        # mode: persistent
        flush-interval-ms: 5000
      container-apps:
        # mode: persistent
        flush-interval-ms: 5000

  dns:
    # When floci-az runs inside Docker, an embedded DNS server starts on UDP/53
    # and is injected into every Azure Functions container as their resolver.
    # It resolves *.hostname (above) and any extra suffixes to floci-az's
    # Docker-network IP so virtual-hosted URLs work from inside function containers.
    # extra-suffixes:
    #   - myapp.internal

  docker:
    docker-host: unix:///var/run/docker.sock
    log-max-size: "10m"
    log-max-file: "3"
    resource-namespace: ""            # Optional; inserted into sidecar container/volume names (floci-az-<ns>-...)

  services:
    blob:
      enabled: true
    queue:
      enabled: true
    table:
      enabled: true
    functions:
      enabled: true
      # Directory where extracted function code is stored on the host.
      code-path: ~/.floci-az/functions
      # ephemeral: true  →  fresh container per invocation (no warm reuse)
      ephemeral: false
      # Evict warm containers idle longer than this (seconds); 0 disables eviction
      container-idle-timeout-seconds: 300
    app-config:
      enabled: true
    container-apps:
      enabled: true
      mocked: true
      dns-suffix: azurecontainerapps.io
      ingress-timeout-seconds: 60
```

## Variables de entorno clave

| Variable de entorno | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_PORT` | `4577` | Puerto de escucha |
| `FLOCI_AZ_AUTH_MODE` | `dev` | `dev` o `strict` |
| `FLOCI_AZ_STORAGE_MODE` | `memory` | Modo de almacenamiento global |
| `FLOCI_AZ_STORAGE_PATH` | `/app/data` | Directorio de persistencia |
| `FLOCI_AZ_STORAGE_SERVICES_BLOB_MODE` | _(global)_ | Modo blob por servicio |
| `FLOCI_AZ_STORAGE_SERVICES_QUEUE_MODE` | _(global)_ | Modo cola por servicio |
| `FLOCI_AZ_STORAGE_SERVICES_TABLE_MODE` | _(global)_ | Modo tabla por servicio |
| `FLOCI_AZ_STORAGE_SERVICES_APP_CONFIG_MODE` | _(global)_ | Modo App Configuration por servicio |
| `FLOCI_AZ_STORAGE_SERVICES_CONTAINER_APPS_MODE` | _(global)_ | Modo Container Apps por servicio |
| `FLOCI_AZ_SERVICES_FUNCTIONS_EPHEMERAL` | `false` | Contenedor nuevo por invocación |
| `FLOCI_AZ_SERVICES_FUNCTIONS_CONTAINER_IDLE_TIMEOUT_SECONDS` | `300` | Expulsa los contenedores calientes inactivos por más tiempo que esto (segundos); `0` desactiva la expulsión |
| `FLOCI_AZ_SERVICES_APP_CONFIG_ENABLED` | `true` | Habilita/deshabilita App Configuration |
| `FLOCI_AZ_SERVICES_CONTAINER_APPS_MOCKED` | `true` | Mantiene el estado ARM de Container Apps sin runtimes Docker |
| `FLOCI_AZ_SERVICES_FUNCTIONS_CODE_PATH` | `~/.floci-az/functions` | Directorio del código de funciones |
| `FLOCI_AZ_DOCKER_DOCKER_HOST` | `unix:///var/run/docker.sock` | Socket del daemon de Docker |

Las claves de firma SAS del servicio Blob se configuran por cuenta (independientes de `auth.mode`):

```yaml
floci-az:
  auth:
    storage-account-keys:
      myaccount: "<base64-account-key>"
```

`devstoreaccount1` usa por defecto la clave de cuenta estándar de Azurite. Para esa entrada existente, la
anulación por variable de entorno es `FLOCI_AZ_AUTH_STORAGE_ACCOUNT_KEYS_DEVSTOREACCOUNT1`.
Usa YAML para declarar nombres de cuenta adicionales. El SAS de delegación de usuario conserva sus propias
claves de firma separadas, locales al proceso.