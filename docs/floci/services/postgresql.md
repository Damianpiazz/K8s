# Azure Database for PostgreSQL (Flexible Server)

Compatible con `pgjdbc` (JDBC), `psycopg`, `Npgsql` y cualquier cliente que hable libpq.

> **Requiere Docker** — cada servidor flexible lógico se asigna a un contenedor `postgres`.
> El plano de datos (puerto 5432) va **directamente** al contenedor; floci-az solo gestiona
> el plano de administración (API REST de ARM). A diferencia de Azure SQL, **no hay EULA** —
> la imagen `postgres` tiene licencia PostgreSQL.

---

## Características

- **Servidores flexibles** — crear, obtener, listar, actualizar (PATCH), eliminar; un contenedor Docker por servidor lógico
- **Bases de datos** — crear, obtener, listar, eliminar (solo metadatos; ver la nota de abajo)
- **Reglas de firewall** — CRUD completo; solo metadatos (sin filtrado de IP real en modo de desarrollo)
- **Configuraciones** — obtener, listar, poner (parámetros del servidor almacenados como metadatos)
- **Comprobación de disponibilidad del nombre** — `POST .../checkNameAvailability`
- **Cadenas de conexión** — endpoint de conveniencia que devuelve cadenas JDBC, URI libpq, `psql` y Npgsql
- **Modo simulado** — solo plano de administración, sin Docker, para `plan`/CI rápidos

---

## Nota sobre TLS (divergencia solo local)

Azure Database for PostgreSQL Flexible Server **exige TLS** en la nube
(`require_secure_transport=ON`, TLS 1.2+). La imagen estándar `postgres` **no** sirve TLS por
defecto, por lo que las cadenas de conexión devueltas por floci-az usan **`sslmode=disable`**.
Es una diferencia deliberada solo local; la cadena de conexión de producción sigue usando TLS.

---

## Las bases de datos son solo metadatos

Crear un recurso `Microsoft.DBforPostgreSQL/flexibleServers/databases` registra la
base de datos en el plano de administración pero **no** ejecuta `CREATE DATABASE` dentro del
contenedor (el mismo modelo que floci-az usa para Azure SQL). Crea la base de datos/esquema real
desde la aplicación o las herramientas de migración (Flyway, Liquibase, EF Core, `psql`, etc.) usando
los detalles de conexión del endpoint `/connect`. La base de datos `postgres` por defecto está
siempre disponible.

---

## Endpoints

### Ruta ARM (usada por los SDK de Azure / Terraform)

```
/subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.DBforPostgreSQL/flexibleServers/{serverName}
/subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.DBforPostgreSQL/flexibleServers/{serverName}/databases/{dbName}
```

### Ruta de conveniencia (pruebas rápidas)

```
/{account}-postgres/flexibleServers/{serverName}
/{account}-postgres/flexibleServers/{serverName}/connect
```

El endpoint `/connect` es un **añadido de floci-az** — devuelve todos los formatos de cadena de conexión en una sola llamada.

---

## Inicio rápido

### 1 — Crear un servidor

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.DBforPostgreSQL/flexibleServers/myserver?api-version=2025-08-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "sku": { "name": "Standard_B1ms", "tier": "Burstable" },
    "properties": {
      "administratorLogin": "psqladmin",
      "administratorLoginPassword": "FlociAz_Strong123!",
      "version": "16",
      "storage": { "storageSizeGB": 32 }
    }
  }'
```

> La primera llamada inicia el contenedor y espera a que PostgreSQL acepte conexiones
> (unos segundos con una imagen en caché, más tiempo en la primera descarga de `postgres:17-alpine`).

### 2 — Obtener las cadenas de conexión

```bash
curl -s "http://localhost:4577/devstoreaccount1-postgres/flexibleServers/myserver/connect"
```

Respuesta:

```json
{
  "server": "myserver",
  "host": "localhost",
  "port": 54983,
  "jdbcUrl": "jdbc:postgresql://localhost:54983/postgres?user=psqladmin&password=FlociAz_Strong123!&sslmode=disable",
  "uri": "postgresql://psqladmin:FlociAz_Strong123!@localhost:54983/postgres?sslmode=disable",
  "psql": "psql \"host=localhost port=54983 dbname=postgres user=psqladmin password=FlociAz_Strong123! sslmode=disable\"",
  "dotNet": "Host=localhost;Port=54983;Database=postgres;Username=psqladmin;Password=FlociAz_Strong123!;SSL Mode=Disable;"
}
```

### 3 — Conectarse mediante psql

```bash
psql "host=localhost port=54983 dbname=postgres user=psqladmin password=FlociAz_Strong123! sslmode=disable"
```

---

## Conexión con SDK

=== "Java (JDBC)"

    ```java
    String jdbcUrl = "jdbc:postgresql://localhost:54983/postgres"
                   + "?user=psqladmin&password=FlociAz_Strong123!&sslmode=disable";

    try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
        // use conn
    }
    ```

=== "Python (psycopg)"

    ```python
    import psycopg

    conn = psycopg.connect(
        "host=localhost port=54983 dbname=postgres "
        "user=psqladmin password=FlociAz_Strong123! sslmode=disable"
    )
    ```

=== ".NET (Npgsql)"

    ```csharp
    var connStr = "Host=localhost;Port=54983;Database=postgres;"
                + "Username=psqladmin;Password=FlociAz_Strong123!;SSL Mode=Disable;";

    using var conn = new NpgsqlConnection(connStr);
    conn.Open();
    ```

---

## Terraform / OpenTofu

```hcl
resource "azurerm_postgresql_flexible_server" "pg" {
  name                          = "myserver"
  resource_group_name           = azurerm_resource_group.rg.name
  location                      = azurerm_resource_group.rg.location
  version                       = "16"
  administrator_login           = "psqladmin"
  administrator_password        = "FlociAz_Strong123!"
  storage_mb                    = 32768
  sku_name                      = "B_Standard_B1ms"
  public_network_access_enabled = true
}

resource "azurerm_postgresql_flexible_server_database" "db" {
  name      = "appdb"
  server_id = azurerm_postgresql_flexible_server.pg.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}
```

---

## Referencia de la API REST

### Servidores flexibles

| Método | Ruta | Descripción |
|---|---|---|
| `PUT` | `.../flexibleServers/{name}` | Crear o actualizar un servidor |
| `GET` | `.../flexibleServers/{name}` | Obtener las propiedades del servidor |
| `PATCH` | `.../flexibleServers/{name}` | Actualizar los metadatos del servidor (sin reiniciar el contenedor) |
| `DELETE` | `.../flexibleServers/{name}` | Eliminar el servidor y detener su contenedor |
| `GET` | `.../flexibleServers` | Listar todos los servidores del grupo de recursos |
| `POST` | `.../locations/{loc}/checkNameAvailability` | Comprobar si un nombre de servidor está disponible |

### Bases de datos

| Método | Ruta | Descripción |
|---|---|---|
| `PUT` | `.../flexibleServers/{name}/databases/{db}` | Crear o actualizar una base de datos (metadatos) |
| `GET` | `.../flexibleServers/{name}/databases/{db}` | Obtener las propiedades de la base de datos |
| `DELETE` | `.../flexibleServers/{name}/databases/{db}` | Eliminar una base de datos (metadatos) |
| `GET` | `.../flexibleServers/{name}/databases` | Listar todas las bases de datos |

### Reglas de firewall

| Método | Ruta | Descripción |
|---|---|---|
| `PUT` | `.../flexibleServers/{name}/firewallRules/{rule}` | Crear o actualizar una regla de firewall |
| `GET` | `.../flexibleServers/{name}/firewallRules/{rule}` | Obtener una regla de firewall |
| `DELETE` | `.../flexibleServers/{name}/firewallRules/{rule}` | Eliminar una regla de firewall |
| `GET` | `.../flexibleServers/{name}/firewallRules` | Listar todas las reglas de firewall |

### Configuraciones

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `.../flexibleServers/{name}/configurations/{cfg}` | Obtener un parámetro del servidor |
| `PUT` | `.../flexibleServers/{name}/configurations/{cfg}` | Establecer un parámetro del servidor (metadatos) |
| `GET` | `.../flexibleServers/{name}/configurations` | Listar los parámetros del servidor |

### Conveniencia (solo floci-az)

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/{account}-postgres/flexibleServers/{name}/connect` | Todas las cadenas de conexión del servidor |

---

## Configuración

```yaml
floci-az:
  services:
    postgres:
      enabled: true
      mocked: false                 # false (default) = real postgres container. true = management plane only, no Docker
      image: "postgres:17-alpine"
      startup-timeout-seconds: 60
```

En modo **simulado** (`mocked: true`) los servidores se crean en estado e informan
`state=Ready` / `provisioningState=Succeeded` sin contenedor — útil para pruebas del plano de
administración sin Docker. El plano de datos no está disponible (sin endpoint activo), por lo que el endpoint
`/connect` no devuelve un puerto utilizable.

| Variable de entorno | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_POSTGRES_ENABLED` | `true` | Habilitar o deshabilitar el servicio PostgreSQL |
| `FLOCI_AZ_SERVICES_POSTGRES_MOCKED` | `false` | Modo simulado (solo plano de administración, sin Docker) |
| `FLOCI_AZ_SERVICES_POSTGRES_IMAGE` | `postgres:17-alpine` | Imagen de Docker para los contenedores de PostgreSQL |
| `FLOCI_AZ_SERVICES_POSTGRES_STARTUP_TIMEOUT_SECONDS` | `60` | Segundos de espera hasta que PostgreSQL esté listo |

---

## Docker Compose

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock   # required for PostgreSQL containers
```

> **Puertos sidecar:** los contenedores de PostgreSQL enlazan un puerto de host directamente a través del daemon de Docker;
> estos puertos **no** se publican en el servicio `floci-az`. Con
> `FLOCI_AZ_SERVICES_POSTGRES_DEFAULT_PORT=0` (el valor por defecto), el SO asigna uno por cada servidor.
> Si lo configuras a un puerto específico, el **primer** servidor en iniciarse enlaza exactamente ese puerto; cualquier
> servidor adicional, o un inicio cuando el puerto ya está ocupado, cae en un puerto asignado por el SO
> con una advertencia en el registro. La disponibilidad se evalúa cuando el servidor se inicia, por lo que un puerto que
> algo más tome en el mismo instante hace fallar la creación como cualquier otro conflicto de enlace. Lee el puerto real de `properties.localPort` o
> de `/connect` en lugar de asumirlo. Cuando floci-az mismo se ejecuta en un contenedor, el puerto fijo se
> enlaza en el host de Docker, mientras que los clientes de la red compartida todavía alcanzan el sidecar por
> nombre de contenedor en el 5432.

---

## Arquitectura

```
┌──────────────────────────────────────────────────────────────┐
│  Your App                                                    │
│                                                              │
│  ARM REST calls ──────► floci-az :4577 ──► PostgresHandler   │
│  (create server,                          (state, routing)   │
│   create database,                                           │
│   get conn strings)                                          │
│                                                              │
│  libpq / JDBC ────────────────────────────────────────────►  │
│  (SQL queries, DDL)          PostgreSQL container :54983     │
└──────────────────────────────────────────────────────────────┘
```

El plano de administración (API ARM) pasa por floci-az en el puerto 4577.
El plano de datos (protocolo de red de PostgreSQL) se conecta **directamente** al contenedor en su puerto dinámico — floci-az no está en la ruta de datos.