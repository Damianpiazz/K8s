# Azure Cache for Redis

Compatible con el SDK `azure-mgmt-redis`, la CLI `az redis`, el `azurerm_redis_cache` de Terraform
y cualquier cliente que hable ARM para el plano de administración — además de **cualquier cliente Redis estándar**
(redis-py, StackExchange.Redis, Jedis, `redis-cli`, …) para el plano de datos.

> **Sidecar real (por defecto).** Con `mocked=false` (el valor por defecto), cada caché está respaldada por un contenedor
> real (`valkey/valkey:8-alpine` — un fork de Redis compatible con RESP e intercambiable) al que los clientes se conectan
> con el protocolo Redis. Los perfiles de pruebas unitarias fuerzan `mocked=true` (solo plano de administración, sin
> Docker).

---

## Características

- **Ciclo de vida** — CreateOrUpdate, Get, Delete, Patch (etiquetas / config), List (por suscripción y por grupo de recursos)
- **Claves de acceso** — `listKeys` y `regenerateKey`; la respuesta de creación incorpora `properties.accessKeys`
- **Plano de datos real** — un contenedor Redis por caché; la clave de acceso principal es la contraseña de Redis
  (`--requirepass`), y tanto la clave principal como la secundaria se autentican mediante una ACL de Redis sobre el
  usuario `default`
- **Aprovisionamiento asíncrono** — las cachés no simuladas inician con `provisioningState=Creating` y cambian a
  `Succeeded` una vez que el contenedor responde `PING`; las cachés simuladas devuelven `Succeeded` de inmediato

---

## Endpoints

Todas las operaciones de administración usan rutas ARM:

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Cache/redis/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Cache/redis/{name}
PATCH  /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Cache/redis/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Cache/redis/{name}
POST   .../redis/{name}/listKeys
POST   .../redis/{name}/regenerateKey      # body: {"keyType":"Primary"|"Secondary"}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Cache/redis
GET    /subscriptions/{sub}/providers/Microsoft.Cache/redis
```

---

## Inicio rápido

### 1 — Crear una caché

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.Cache/redis/my-cache?api-version=2024-11-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "sku": {"name": "Basic", "family": "C", "capacity": 0},
      "enableNonSslPort": true,
      "minimumTlsVersion": "1.2"
    }
  }'
```

La respuesta contiene los datos de conexión y las claves:

```json
{
  "name": "my-cache",
  "type": "Microsoft.Cache/Redis",
  "properties": {
    "provisioningState": "Succeeded",
    "hostName": "localhost",
    "port": 6379,
    "sslPort": 6380,
    "accessKeys": {"primaryKey": "…", "secondaryKey": "…"}
  }
}
```

### 2 — Conectarse con un cliente Redis

```python
import redis
client = redis.Redis(host="localhost", port=6379, password="<primaryKey>")
client.set("greeting", "hello")
print(client.get("greeting"))   # b'hello'
```

```bash
redis-cli -h localhost -p 6379 -a "<primaryKey>" ping   # PONG
```

### 3 — Rotar una clave

```bash
curl -s -X POST \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.Cache/redis/my-cache/regenerateKey?api-version=2024-11-01" \
  -H "Content-Type: application/json" -d '{"keyType":"Primary"}'
```

En modo no simulado, la nueva clave se aplica al contenedor en ejecución de inmediato (sin reinicio).

---

## Configuración

```yaml
floci-az:
  services:
    redis:
      enabled: true
      mocked: false             # false (default) = real cache container per cache. true = no Docker, management plane only
      default-image: "valkey/valkey:8-alpine"
      base-port: 6379           # host port range start for cache containers
      max-port: 6399            # host port range end
      max-memory: "256mb"       # per-instance maxmemory
```

| Variable de entorno | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_REDIS_ENABLED` | `true` | Habilitar/deshabilitar el servicio |
| `FLOCI_AZ_SERVICES_REDIS_MOCKED` | `false` | Modo simulado (solo plano de administración, sin Docker) |
| `FLOCI_AZ_SERVICES_REDIS_DEFAULT_IMAGE` | `valkey/valkey:8-alpine` | Imagen del contenedor de caché (compatible con RESP) |
| `FLOCI_AZ_SERVICES_REDIS_BASE_PORT` | `6379` | Inicio del rango de puertos de host |
| `FLOCI_AZ_SERVICES_REDIS_MAX_PORT` | `6399` | Fin del rango de puertos de host |
| `FLOCI_AZ_SERVICES_REDIS_MAX_MEMORY` | `256mb` | `maxmemory` por instancia |

---

## Notas y limitaciones

- **Resolución de endpoint.** `hostName` devuelve el host realmente alcanzable — `localhost` de forma nativa,
  o el nombre del contenedor cuando floci-az mismo se ejecuta en Docker — no el FQDN real
  `{name}.redis.cache.windows.net`, para que los clientes Redis estándar puedan conectarse al sidecar.
  `port` es el puerto de host asignado dinámicamente y mapeado al `6379` del contenedor.
- **Solo sin SSL (por ahora).** El plano de datos se sirve en el puerto sin SSL; `sslPort` (6380) se
  informa por fidelidad de la API, pero la terminación TLS aún no está cableada. Utilice el puerto sin SSL para conectarse.
- **Nodo único.** La agrupación en clústeres (`shardCount`), la geo-replicación, los private endpoints, las reglas de
  firewall y los cronogramas de parches se aceptan en el plano de administración pero no se aplican.
- El modo simulado devuelve `hostName=localhost` sin contenedor de respaldo — útil para pruebas de
  aprovisionamiento, pero las conexiones del plano de datos fallarán.