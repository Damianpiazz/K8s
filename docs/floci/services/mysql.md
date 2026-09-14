# Azure Database for MySQL (Flexible Server)

Compatible con MySQL Connector/J (JDBC), `mysqlclient` / `PyMySQL`, `MySqlConnector` para .NET
y cualquier cliente que hable el protocolo MySQL.

> **Requiere Docker** — cada servidor flexible lógico se asigna a un contenedor `mysql`.
> El plano de datos (puerto 3306) va **directamente** al contenedor; floci-az solo gestiona
> el plano de administración (API REST de ARM). **No hay EULA** — la imagen `mysql` tiene
> licencia GPL.

---

## Características

- **Servidores flexibles** — crear, obtener, listar, actualizar (PATCH), eliminar; un contenedor Docker por servidor lógico
- **Bases de datos** — crear, obtener, listar, eliminar (solo metadatos; ver la nota de abajo)
- **Reglas de firewall** — CRUD completo; solo metadatos (sin filtrado de IP real en modo de desarrollo)
- **Configuraciones** — obtener, listar, poner (parámetros del servidor almacenados como metadatos)
- **Comprobación de disponibilidad del nombre** — `POST .../checkNameAvailability`
- **Cadenas de conexión** — endpoint de conveniencia que devuelve cadenas JDBC, URI, CLI `mysql` y .NET
- **Modo simulado** — solo plano de administración, sin Docker, para `plan`/CI rápidos

---

## Nota sobre TLS (divergencia solo local)

Azure Database for MySQL Flexible Server **exige TLS** en la nube
(`require_secure_transport=ON`, TLS 1.2+). La imagen estándar `mysql` **no** sirve TLS por
defecto, por lo que la cadena JDBC devuelta por floci-az lo desactiva (`useSSL=false`) y la cadena
de CLI no pasa ninguna opción SSL. Es una diferencia deliberada solo local; la
cadena de conexión de producción sigue usando TLS.

---

## Las bases de datos son solo metadatos

Crear un recurso `Microsoft.DBforMySQL/flexibleServers/databases` registra la base de datos en
el plano de administración pero **no** ejecuta `CREATE DATABASE` dentro del contenedor (el mismo
modelo que floci-az usa para Azure SQL y PostgreSQL). Crea la base de datos real y el esquema desde
la aplicación o las herramientas de migración (Flyway, Liquibase, EF Core, `mysql`) usando los
detalles de conexión del endpoint `/connect`.

---

## Endpoints

### Ruta ARM (usada por los SDK de Azure / Terraform)

```
/subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.DBforMySQL/flexibleServers/{serverName}
/subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.DBforMySQL/flexibleServers/{serverName}/databases/{dbName}
```

### Ruta de conveniencia (pruebas rápidas)

```
/{account}-mysql/flexibleServers/{serverName}
/{account}-mysql/flexibleServers/{serverName}/connect
```

El endpoint `/connect` es un **añadido de floci-az** — devuelve todos los formatos de cadena de conexión en una sola llamada.

---

## Inicio rápido

### 1 — Crear un servidor

```bash
curl -X PUT "http://localhost:4577/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/my-rg/providers/Microsoft.DBforMySQL/flexibleServers/my-server?api-version=2023-06-30" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "administratorLogin": "mysqladmin",
      "administratorLoginPassword": "Str0ng!Passw0rd",
      "version": "8.0"
    }
  }'
```

### 2 — Obtener las cadenas de conexión

```bash
curl "http://localhost:4577/devstoreaccount1-mysql/flexibleServers/my-server/connect"
```

```json
{
  "server": "my-server",
  "host": "localhost",
  "port": 54321,
  "jdbcUrl": "jdbc:mysql://localhost:54321/floci?user=mysqladmin&password=Str0ng!Passw0rd&useSSL=false&allowPublicKeyRetrieval=true",
  "uri": "mysql://mysqladmin:Str0ng!Passw0rd@localhost:54321/floci",
  "mysql": "mysql -h localhost -P 54321 -u mysqladmin -pStr0ng!Passw0rd floci",
  "dotNet": "Server=localhost;Port=54321;Database=floci;Uid=mysqladmin;Pwd=Str0ng!Passw0rd;SslMode=none;"
}
```

Cada cadena generada selecciona **`floci`**, una base de datos que el contenedor crea al iniciarse
(`MYSQL_DATABASE=floci`). Está lista para usar de inmediato, a diferencia de los recursos ARM
`databases` de abajo, que son solo metadatos.

`allowPublicKeyRetrieval=true` no es decorativo: `mysql:8.0` se autentica con
`caching_sha2_password`, que se niega a entregar su clave pública por un socket sin TLS
a menos que el cliente lo acepte explícitamente. Si lo quitas, la conexión falla con "Public Key Retrieval is
not allowed".

En modo simulado `/connect` sigue respondiendo, pero el servidor nunca se inició, así que `port` es `0`
y cada cadena apunta a `localhost:0`.

### 3 — Conectarse mediante el CLI de mysql

```bash
mysql -h localhost -P 54321 -u mysqladmin -pStr0ng!Passw0rd floci
```

---

## Conexión con SDK

=== "Java (JDBC)"

    ```java
    String url = "jdbc:mysql://localhost:54321/floci?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection c = DriverManager.getConnection(url, "mysqladmin", "Str0ng!Passw0rd")) {
        c.createStatement().execute("CREATE DATABASE IF NOT EXISTS appdb");
    }
    ```

=== "Python (PyMySQL)"

    ```python
    import pymysql

    conn = pymysql.connect(
        host="localhost", port=54321,
        user="mysqladmin", password="Str0ng!Passw0rd",
    )
    with conn.cursor() as cur:
        cur.execute("CREATE DATABASE IF NOT EXISTS appdb")
    ```

=== ".NET (MySqlConnector)"

    ```csharp
    await using var conn = new MySqlConnection(
        "Server=localhost;Port=54321;Database=floci;Uid=mysqladmin;Pwd=Str0ng!Passw0rd;SslMode=none;");
    await conn.OpenAsync();
    ```

---

## Terraform / OpenTofu

```hcl
resource "azurerm_mysql_flexible_server" "example" {
  name                   = "my-server"
  resource_group_name    = azurerm_resource_group.rg.name
  location               = azurerm_resource_group.rg.location
  administrator_login    = "mysqladmin"
  administrator_password = "Str0ng!Passw0rd"
  sku_name               = "B_Standard_B1ms"
  version                = "8.0.21"
}
```

---

## Referencia de la API REST

### Servidores flexibles

| Método | Ruta | Descripción |
|---|---|---|
| PUT | `.../flexibleServers/{name}` | Crear o actualizar un servidor |
| GET | `.../flexibleServers/{name}` | Obtener un servidor |
| GET | `.../flexibleServers` | Listar los servidores del grupo de recursos |
| PATCH | `.../flexibleServers/{name}` | Actualizar un servidor |
| DELETE | `.../flexibleServers/{name}` | Eliminar un servidor y detener su contenedor |
| POST | `.../locations/{location}/checkNameAvailability` | Comprobar la disponibilidad del nombre |

### Bases de datos

| Método | Ruta | Descripción |
|---|---|---|
| PUT | `.../flexibleServers/{name}/databases/{db}` | Crear un registro de base de datos |
| GET | `.../flexibleServers/{name}/databases/{db}` | Obtener una base de datos |
| GET | `.../flexibleServers/{name}/databases` | Listar las bases de datos |
| DELETE | `.../flexibleServers/{name}/databases/{db}` | Eliminar un registro de base de datos |

### Reglas de firewall

| Método | Ruta | Descripción |
|---|---|---|
| PUT | `.../flexibleServers/{name}/firewallRules/{rule}` | Crear o actualizar una regla |
| GET | `.../flexibleServers/{name}/firewallRules/{rule}` | Obtener una regla |
| GET | `.../flexibleServers/{name}/firewallRules` | Listar las reglas |
| DELETE | `.../flexibleServers/{name}/firewallRules/{rule}` | Eliminar una regla |

### Configuraciones

| Método | Ruta | Descripción |
|---|---|---|
| GET | `.../flexibleServers/{name}/configurations/{setting}` | Obtener un parámetro del servidor |
| GET | `.../flexibleServers/{name}/configurations` | Listar los parámetros del servidor |
| PUT | `.../flexibleServers/{name}/configurations/{setting}` | Establecer un parámetro del servidor |

### Conveniencia (solo floci-az)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/{account}-mysql/flexibleServers/{name}/connect` | Todos los formatos de cadena de conexión |

---

## Configuración

```yaml
floci-az:
  services:
    mysql:
      enabled: true
      mocked: false
      image: "mysql:8.0"
      startup-timeout-seconds: 60
      default-port: 0
```

Con `mocked: true` no se inicia ningún contenedor: los servidores se crean en estado y pasan
inmediatamente a `state=Ready`, sin endpoint activo. Úsalo para ejecuciones de CI rápidas y para
`terraform plan` contra una máquina sin Docker.

| Variable de entorno | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_MYSQL_ENABLED` | `true` | Habilitar el servicio |
| `FLOCI_AZ_SERVICES_MYSQL_MOCKED` | `false` | Omitir Docker; solo plano de administración |
| `FLOCI_AZ_SERVICES_MYSQL_IMAGE` | `mysql:8.0` | Imagen de contenedor por servidor |
| `FLOCI_AZ_SERVICES_MYSQL_STARTUP_TIMEOUT_SECONDS` | `60` | Espera de disponibilidad por contenedor |
| `FLOCI_AZ_SERVICES_MYSQL_DEFAULT_PORT` | `0` | Puerto de host preferido; `0` permite que el SO elija uno libre por servidor |

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
> puerto real de `/connect` o del `fullyQualifiedDomainName` del servidor — no asumas que es 3306.

---

## Arquitectura

```
   client ──JDBC/3306──────────────┐
                                   ▼
   Azure SDK / Terraform     ┌──────────────┐
        │  ARM REST          │  mysql:8.0   │
        ▼                    │  container   │
   ┌─────────────┐  docker   └──────────────┘
   │  floci-az   │──────────────┘
   └─────────────┘
```

floci-az es dueño del plano de administración únicamente: crea, inicia y detiene un contenedor `mysql`
por servidor flexible lógico e informa su puerto de host. El tráfico de clientes nunca pasa por
el emulador, por lo que el protocolo de red es MySQL real en lugar de una emulación del mismo.