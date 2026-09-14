# Azure Database for MariaDB

Compatible con MariaDB Connector/J (JDBC), MySQL Connector/J, `mysqlclient` / `PyMySQL`,
`MySqlConnector` para .NET y cualquier cliente que hable el protocolo MySQL.

> **Requiere Docker** — cada servidor lógico se asigna a un contenedor `mariadb`.
> El plano de datos (puerto 3306) va **directamente** al contenedor; floci-az solo gestiona
> el plano de administración (API REST de ARM). **No hay EULA** — la imagen `mariadb` tiene
> licencia GPL.

> **Modelo de servidor único.** Azure Database for MariaDB usa
> `Microsoft.DBforMariaDB/servers`, no `flexibleServers`. Ese es el tipo real de recurso
> de Azure, y es la razón por la que las rutas de abajo difieren de MySQL y PostgreSQL.

---

## Características

- **Servidores** — crear, obtener, listar, actualizar (PATCH), eliminar; un contenedor Docker por servidor lógico
- **Bases de datos** — crear, obtener, listar, eliminar (solo metadatos; ver la nota de abajo)
- **Reglas de firewall** — CRUD completo; solo metadatos (sin filtrado de IP real en modo de desarrollo)
- **Configuraciones** — obtener, listar, poner (parámetros del servidor almacenados como metadatos)
- **Comprobación de disponibilidad del nombre** — `POST .../checkNameAvailability`
- **Cadenas de conexión** — endpoint de conveniencia que devuelve cadenas JDBC, URI, CLI y .NET
- **Modo simulado** — solo plano de administración, sin Docker, para `plan`/CI rápidos

---

## Nota sobre TLS (divergencia solo local)

Azure Database for MariaDB **exige TLS** en la nube. La imagen estándar `mariadb` **no**
ofrece TLS por defecto, por lo que las cadenas de conexión devueltas por floci-az lo desactivan. Es una
diferencia deliberada solo local; la cadena de conexión de producción sigue usando TLS.

---

## Las bases de datos son solo metadatos

Crear un recurso `Microsoft.DBforMariaDB/servers/databases` registra la base de datos en el
plano de administración pero **no** ejecuta `CREATE DATABASE` dentro del contenedor (el mismo modelo
que floci-az usa para Azure SQL, PostgreSQL y MySQL). Crea la base de datos real y el esquema desde
la aplicación o las herramientas de migración usando los detalles de conexión de `/connect`.

---

## Endpoints

### Ruta ARM (usada por los SDK de Azure / Terraform)

```
/subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.DBforMariaDB/servers/{serverName}
/subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.DBforMariaDB/servers/{serverName}/databases/{dbName}
```

### Ruta de conveniencia (pruebas rápidas)

```
/{account}-mariadb/servers/{serverName}
/{account}-mariadb/servers/{serverName}/connect
```

El endpoint `/connect` es un **añadido de floci-az** — devuelve todos los formatos de cadena de conexión en una sola llamada.

---

## Inicio rápido

### 1 — Crear un servidor

```bash
curl -X PUT "http://localhost:4577/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/my-rg/providers/Microsoft.DBforMariaDB/servers/my-server?api-version=2018-06-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "administratorLogin": "mariaadmin",
      "administratorLoginPassword": "Str0ng!Passw0rd",
      "version": "10.3"
    }
  }'
```

### 2 — Obtener las cadenas de conexión

```bash
curl "http://localhost:4577/devstoreaccount1-mariadb/servers/my-server/connect"
```

```json
{
  "server": "my-server",
  "host": "localhost",
  "port": 54322,
  "jdbcUrl": "jdbc:mariadb://localhost:54322/floci?user=mariaadmin&password=Str0ng!Passw0rd&useSSL=false",
  "uri": "mariadb://mariaadmin:Str0ng!Passw0rd@localhost:54322/floci",
  "mysql": "mysql -h localhost -P 54322 -u mariaadmin -pStr0ng!Passw0rd floci",
  "dotNet": "Server=localhost;Port=54322;Database=floci;Uid=mariaadmin;Pwd=Str0ng!Passw0rd;SslMode=none;"
}
```

Cada cadena generada selecciona **`floci`**, una base de datos que el contenedor crea al iniciarse
(`MARIADB_DATABASE=floci`). Está lista para usar de inmediato, a diferencia de los recursos ARM
`databases` de abajo, que son solo metadatos.

En modo simulado `/connect` sigue respondiendo, pero el servidor nunca se inició, así que `port` es `0`
y cada cadena apunta a `localhost:0`.

### 3 — Conectarse mediante el CLI de mysql

```bash
mysql -h localhost -P 54322 -u mariaadmin -pStr0ng!Passw0rd floci
```

---

## Conexión con SDK

=== "Java (JDBC)"

    ```java
    String url = "jdbc:mariadb://localhost:54322/floci?useSSL=false";
    try (Connection c = DriverManager.getConnection(url, "mariaadmin", "Str0ng!Passw0rd")) {
        c.createStatement().execute("CREATE DATABASE IF NOT EXISTS appdb");
    }
    ```

=== "Python (PyMySQL)"

    ```python
    import pymysql

    conn = pymysql.connect(
        host="localhost", port=54322,
        user="mariaadmin", password="Str0ng!Passw0rd",
    )
    with conn.cursor() as cur:
        cur.execute("CREATE DATABASE IF NOT EXISTS appdb")
    ```

=== ".NET (MySqlConnector)"

    ```csharp
    await using var conn = new MySqlConnection(
        "Server=localhost;Port=54322;Database=floci;Uid=mariaadmin;Pwd=Str0ng!Passw0rd;SslMode=none;");
    await conn.OpenAsync();
    ```

---

## Terraform / OpenTofu

```hcl
resource "azurerm_mariadb_server" "example" {
  name                         = "my-server"
  resource_group_name          = azurerm_resource_group.rg.name
  location                     = azurerm_resource_group.rg.location
  administrator_login          = "mariaadmin"
  administrator_login_password = "Str0ng!Passw0rd"
  sku_name                     = "B_Gen5_1"
  version                      = "10.3"
  ssl_enforcement_enabled      = false
}
```

---

## Referencia de la API REST

### Servidores

| Método | Ruta | Descripción |
|---|---|---|
| PUT | `.../servers/{name}` | Crear o actualizar un servidor |
| GET | `.../servers/{name}` | Obtener un servidor |
| GET | `.../servers` | Listar los servidores del grupo de recursos |
| PATCH | `.../servers/{name}` | Actualizar un servidor |
| DELETE | `.../servers/{name}` | Eliminar un servidor y detener su contenedor |
| POST | `.../locations/{location}/checkNameAvailability` | Comprobar la disponibilidad del nombre |

### Bases de datos

| Método | Ruta | Descripción |
|---|---|---|
| PUT | `.../servers/{name}/databases/{db}` | Crear un registro de base de datos |
| GET | `.../servers/{name}/databases/{db}` | Obtener una base de datos |
| GET | `.../servers/{name}/databases` | Listar las bases de datos |
| DELETE | `.../servers/{name}/databases/{db}` | Eliminar un registro de base de datos |

### Reglas de firewall

| Método | Ruta | Descripción |
|---|---|---|
| PUT | `.../servers/{name}/firewallRules/{rule}` | Crear o actualizar una regla |
| GET | `.../servers/{name}/firewallRules/{rule}` | Obtener una regla |
| GET | `.../servers/{name}/firewallRules` | Listar las reglas |
| DELETE | `.../servers/{name}/firewallRules/{rule}` | Eliminar una regla |

### Configuraciones

| Método | Ruta | Descripción |
|---|---|---|
| GET | `.../servers/{name}/configurations/{setting}` | Obtener un parámetro del servidor |
| GET | `.../servers/{name}/configurations` | Listar los parámetros del servidor |
| PUT | `.../servers/{name}/configurations/{setting}` | Establecer un parámetro del servidor |

### Conveniencia (solo floci-az)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/{account}-mariadb/servers/{name}/connect` | Todos los formatos de cadena de conexión |

---

## Configuración

```yaml
floci-az:
  services:
    maria-db:
      enabled: true
      mocked: false
      image: "mariadb:10.11"
      startup-timeout-seconds: 60
      default-port: 0
```

Observe que la clave de configuración es `maria-db`: SmallRye la deriva del accessor `mariaDb()`, de modo que el
prefijo de entorno es `FLOCI_AZ_SERVICES_MARIA_DB_`.

Con `mocked: true` no se inicia ningún contenedor: los servidores se crean en estado y pasan
inmediatamente a `userVisibleState=Ready`, sin endpoint activo.

| Variable de entorno | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_MARIA_DB_ENABLED` | `true` | Habilitar el servicio |
| `FLOCI_AZ_SERVICES_MARIA_DB_MOCKED` | `false` | Omitir Docker; solo plano de administración |
| `FLOCI_AZ_SERVICES_MARIA_DB_IMAGE` | `mariadb:10.11` | Imagen de contenedor por servidor |
| `FLOCI_AZ_SERVICES_MARIA_DB_STARTUP_TIMEOUT_SECONDS` | `60` | Espera de disponibilidad por contenedor |
| `FLOCI_AZ_SERVICES_MARIA_DB_DEFAULT_PORT` | `0` | Puerto de host preferido; `0` permite que el SO elija uno libre por servidor |

---

## Docker Compose

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

> **Puertos sidecar:** cada servidor enlaza su propio puerto de host, asignado por el SO. Lee el
> puerto real de `/connect` — no asumas que es 3306.

---

## Arquitectura

```
   client ──JDBC/3306──────────────┐
                                   ▼
   Azure SDK / Terraform     ┌───────────────┐
        │  ARM REST          │ mariadb:10.11 │
        ▼                    │  container    │
   ┌─────────────┐  docker   └───────────────┘
   │  floci-az   │──────────────┘
   └─────────────┘
```

floci-az es dueño del plano de administración únicamente: crea, inicia y detiene un contenedor `mariadb`
por servidor lógico e informa su puerto de host. El tráfico de clientes nunca pasa por el
emulador, por lo que el protocolo de red es MariaDB real en lugar de una emulación del mismo.