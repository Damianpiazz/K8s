# Azure SQL Database

Las operaciones del plano de administración funcionan sin Docker por defecto. Configura el proveedor del plano de datos
en `managed` para usar `mssql-jdbc`, `pyodbc`, `System.Data.SqlClient` u otro cliente que hable TDS
contra un contenedor real de SQL Server 2025.

---

## Características

- **Servidores** — crear, obtener, listar, eliminar; estado ARM inmediato por defecto
- **Bases de datos** — crear (con intercalación opcional), obtener, listar, eliminar; protegidas contra el borrado de `master`
- **Reglas de firewall** — CRUD completo; solo metadatos (sin filtrado de IP real en modo de desarrollo)
- **Política de conexión** — GET devuelve `Default` (solo lectura)
- **Comprobación de disponibilidad del nombre** — `POST .../checkNameAvailability`
- **Proveedores del plano de datos** — `none` (por defecto), `managed` y `external` reservado
- **Aprovisionamiento administrado asíncrono** — el `PUT` del servidor devuelve `202` con el sondeo de `Location` de Azure
- **Cadenas de conexión** — el modo administrado devuelve cadenas JDBC, ADO.NET, pyodbc y EF Core
- **Guardia de EULA** — la creación de servidores administrados requiere `FLOCI_AZ_SERVICES_SQL_ACCEPT_EULA=Y`

---

## Proveedores del plano de datos

| Proveedor | Comportamiento |
|---|---|
| `none` | Por defecto. Solo estado ARM compatible con Azure; sin acceso a Docker, sin descarga de imágenes, sin EULA ni endpoint TDS. |
| `managed` | Inicia un contenedor real de SQL Server por servidor lógico. Requiere Docker y la aceptación del EULA. |
| `external` | Reservado para una versión posterior. Las solicitudes devuelven actualmente `DataPlaneProviderUnavailable`. |

Cuando se selecciona `none`, `/connect` devuelve `409 DataPlaneNotEnabled`. Las respuestas ARM igualmente exponen
el nombre de host `{server}.database.windows.net` con forma de Azure y nunca incluyen datos de puerto exclusivos del emulador.

El ajuste obsoleto `mocked` sigue siendo un alias de compatibilidad cuando `data-plane.provider` está ausente:
`true` se asigna a `none`; `false` se asigna a `managed`.

Para compatibilidad de actualización, `accept-eula: "Y"` también selecciona `managed` cuando no hay configurado
`data-plane.provider` ni `mocked`. Un proveedor explícito o un valor `mocked` heredado
siempre tiene prioridad.

La creación de servidores administrados no espera la descarga de la imagen ni el arranque del motor en el hilo de la
solicitud. Un servidor nuevo se persiste con `state=Creating` y devuelve `202 Accepted`, `Location` y `Retry-After`.
Consulta `Location` hasta que devuelva `200` con `status=Succeeded`; un aprovisionamiento fallido devuelve un sobre de error
ARM no-2xx que contiene el código y el mensaje del error de aprovisionamiento. El GET del servidor expone el
estado del recurso `Creating`, `Ready` o `Failed` correspondiente. Los reintentos de PUT equivalentes reutilizan la misma
operación; las actualizaciones en conflicto durante el aprovisionamiento devuelven `409 ConflictingServerOperation`.

---

## Requisito de EULA (solo proveedor administrado)

SQL Server está cubierto por el [EULA de Microsoft SQL Server](https://go.microsoft.com/fwlink/?linkid=857698).
Debe aceptarlo explícitamente antes de que el emulador inicie cualquier contenedor:

```bash
# Environment variable (docker-compose / CLI)
FLOCI_AZ_SERVICES_SQL_ACCEPT_EULA=Y

# JVM system property (quarkus:dev)
-Dfloci-az.services.sql.accept-eula=Y
```

Sin ello, el `PUT /servers/{name}` administrado devuelve:

```json
{ "error": { "code": "EulaNotAccepted", "message": "..." } }
```

---

## Endpoints

### Ruta ARM (usada por los SDK de Azure)

```
/subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.Sql/servers/{serverName}
/subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.Sql/servers/{serverName}/databases/{dbName}
```

### Ruta de conveniencia (pruebas rápidas)

```
/{account}-sql/servers/{serverName}
/{account}-sql/servers/{serverName}/databases/{dbName}
/{account}-sql/servers/{serverName}/connect
/{account}-sql/servers/{serverName}/databases/{dbName}/connect
```

Los endpoints `/connect` son un **añadido de floci-az** — devuelven todos los formatos de cadena de conexión en una sola llamada.

---

## Inicio rápido

Este inicio rápido del plano de datos asume `data-plane.provider: managed`, acceso a Docker y EULA aceptado.
Para un uso exclusivamente ARM, conserve el proveedor `none` por defecto y no continúe después de crear los recursos de
administración.

### 1 — Crear un servidor

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.Sql/servers/myserver?api-version=2021-11-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "administratorLogin": "sa",
      "administratorLoginPassword": "YourStrong!Passw0rd"
    }
  }'
```

> En el modo administrado, la primera llamada responde de inmediato con `202 Accepted`; la descarga de la imagen y el arranque
> de SQL Server continúan en segundo plano. Siga el encabezado `Location` antes de solicitar las cadenas de conexión.

### 2 — Obtener las cadenas de conexión

```bash
curl -s "http://localhost:4577/devstoreaccount1-sql/servers/myserver/connect"
```

Respuesta:

```json
{
  "server": "myserver",
  "host": "localhost",
  "port": 59743,
  "jdbcUrl": "jdbc:sqlserver://localhost:59743;databaseName=master;user=sa;password=YourStrong!Passw0rd;encrypt=true;trustServerCertificate=true;",
  "connectionString": "Server=tcp:localhost,59743;Initial Catalog=master;...",
  "pyodbc": "DRIVER={ODBC Driver 18 for SQL Server};SERVER=localhost,59743;...",
  "entityFramework": "Server=localhost,59743;Database=master;..."
}
```

### 3 — Crear una base de datos

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.Sql/servers/myserver/databases/mydb?api-version=2021-11-01" \
  -H "Content-Type: application/json" \
  -d '{"location": "eastus", "properties": {}}'
```

### 4 — Conectarse mediante JDBC

```java
String jdbcUrl = "jdbc:sqlserver://localhost:59743;"
               + "databaseName=mydb;user=sa;password=YourStrong!Passw0rd;"
               + "encrypt=true;trustServerCertificate=true;";

try (Connection conn = DriverManager.getConnection(jdbcUrl);
     Statement  stmt = conn.createStatement()) {
    stmt.executeUpdate("CREATE TABLE greet (msg NVARCHAR(100))");
    stmt.executeUpdate("INSERT INTO greet VALUES ('Hello from floci-az!')");
    ResultSet rs = stmt.executeQuery("SELECT msg FROM greet");
    while (rs.next()) System.out.println(rs.getString(1));
}
```

> **Importante:** use `encrypt=true;trustServerCertificate=true` para aceptar el certificado autofirmado
> del contenedor conservando al mismo tiempo las conexiones cifradas con `mssql-jdbc` 12.x.

---

## Conexión con SDK

=== "Java (JDBC)"

    ```java
    // pom.xml
    // <dependency>
    //   <groupId>com.microsoft.sqlserver</groupId>
    //   <artifactId>mssql-jdbc</artifactId>
    //   <version>12.6.1.jre11</version>
    // </dependency>

    String jdbcUrl = "jdbc:sqlserver://localhost:59743;"
                   + "databaseName=mydb;"
                   + "user=sa;"
                   + "password=YourStrong!Passw0rd;"
                   + "encrypt=true;"
                   + "trustServerCertificate=true;";

    try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
        // use conn
    }
    ```

=== "Python (pyodbc)"

    ```python
    import pyodbc

    conn_str = (
        "DRIVER={ODBC Driver 18 for SQL Server};"
        "SERVER=localhost,59743;"
        "DATABASE=mydb;"
        "UID=sa;"
        "PWD=YourStrong!Passw0rd;"
        "TrustServerCertificate=yes;"
        "Encrypt=yes;"
    )

    conn = pyodbc.connect(conn_str)
    cursor = conn.cursor()
    cursor.execute("SELECT @@VERSION")
    print(cursor.fetchone()[0])
    ```

=== "Python (SQLAlchemy)"

    ```python
    from sqlalchemy import create_engine, text

    engine = create_engine(
        "mssql+pyodbc://sa:YourStrong!Passw0rd@localhost:59743/mydb"
        "?driver=ODBC+Driver+18+for+SQL+Server"
        "&TrustServerCertificate=yes"
        "&Encrypt=yes",
        echo=False,
    )

    with engine.connect() as conn:
        result = conn.execute(text("SELECT @@VERSION"))
        print(result.scalar())
    ```

=== ".NET (ADO.NET)"

    ```csharp
    var connStr = "Server=tcp:localhost,59743;"
                + "Initial Catalog=mydb;"
                + "User ID=sa;"
                + "Password=YourStrong!Passw0rd;"
                + "Encrypt=True;"
                + "TrustServerCertificate=True;"
                + "Connection Timeout=30;";

    using var conn = new SqlConnection(connStr);
    conn.Open();
    ```

=== ".NET (EF Core)"

    ```csharp
    // In Program.cs / Startup.cs
    builder.Services.AddDbContext<AppDbContext>(options =>
        options.UseSqlServer(
            "Server=localhost,59743;Database=mydb;"
            + "User Id=sa;Password=YourStrong!Passw0rd;"
            + "Encrypt=True;TrustServerCertificate=True;"));
    ```

---

## Obtención del puerto

El contenedor recibe el puerto asignado dinámicamente por el SO. Puede recuperarlo de:

**La respuesta de `/connect`:**

```bash
PORT=$(curl -s "http://localhost:4577/devstoreaccount1-sql/servers/myserver/connect" \
       | python3 -c "import sys,json; print(json.load(sys.stdin)['port'])")
```

Los recursos ARM omiten deliberadamente el puerto específico del emulador. Use `/connect` para obtener los detalles del
endpoint alcanzable en los modos de plano de datos.

---

## Referencia de la API REST

### Servidores

| Método | Ruta | Descripción |
|---|---|---|
| `PUT` | `.../servers/{name}` | Crear o actualizar un servidor |
| `GET` | `.../servers/{name}` | Obtener las propiedades del servidor |
| `DELETE` | `.../servers/{name}` | Eliminar el servidor y detener su contenedor |
| `GET` | `.../servers` | Listar todos los servidores del grupo de recursos |
| `POST` | `.../checkNameAvailability` | Comprobar si un nombre de servidor está disponible |

### Bases de datos

| Método | Ruta | Descripción |
|---|---|---|
| `PUT` | `.../servers/{name}/databases/{db}` | Crear o actualizar una base de datos |
| `GET` | `.../servers/{name}/databases/{db}` | Obtener las propiedades de la base de datos |
| `DELETE` | `.../servers/{name}/databases/{db}` | Eliminar la base de datos (bloqueado para `master`) |
| `GET` | `.../servers/{name}/databases` | Listar todas las bases de datos |

### Reglas de firewall

| Método | Ruta | Descripción |
|---|---|---|
| `PUT` | `.../servers/{name}/firewallRules/{rule}` | Crear o actualizar una regla de firewall |
| `GET` | `.../servers/{name}/firewallRules/{rule}` | Obtener una regla de firewall |
| `DELETE` | `.../servers/{name}/firewallRules/{rule}` | Eliminar una regla de firewall |
| `GET` | `.../servers/{name}/firewallRules` | Listar todas las reglas de firewall |

### Política de conexión

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `.../servers/{name}/connectionPolicies/default` | Obtener la política de conexión (siempre devuelve `Default`) |

### Conveniencia (solo floci-az)

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/{account}-sql/servers/{name}/connect` | Todas las cadenas de conexión del servidor |
| `GET` | `/{account}-sql/servers/{name}/databases/{db}/connect` | Todas las cadenas de conexión de una base de datos |

---

## Configuración

```yaml
floci-az:
  services:
    sql:
      enabled: true
      data-plane:
        provider: managed                           # none (default) | managed | external (reserved)
      accept-eula: "Y"                              # Required to start containers
      image: "mcr.microsoft.com/mssql/server:2025-latest"
      startup-timeout-seconds: 60
```

Microsoft admite las imágenes de contenedor de Linux de SQL Server solo en hosts Intel y AMD x86-64.
Los hosts ARM64 requieren una imagen alternativa configurada explícitamente o una emulación de CPU no admitida.

| Variable de entorno | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_SQL_ENABLED` | `true` | Habilitar o deshabilitar el servicio SQL |
| `FLOCI_AZ_SERVICES_SQL_DATA_PLANE_PROVIDER` | `none` | Proveedor del plano de datos: `none`, `managed` o `external` reservado |
| `FLOCI_AZ_SERVICES_SQL_MOCKED` | _(sin establecer)_ | Alias obsoleto que se usa solo cuando el proveedor no está configurado: `true` = `none`, `false` = `managed` |
| `FLOCI_AZ_SERVICES_SQL_ACCEPT_EULA` | `N` | Establecer en `Y` para aceptar el EULA de Microsoft SQL Server en modo administrado |
| `FLOCI_AZ_SERVICES_SQL_IMAGE` | `mcr.microsoft.com/mssql/server:2025-latest` | Imagen de Docker para los contenedores de SQL Server |
| `FLOCI_AZ_SERVICES_SQL_STARTUP_TIMEOUT_SECONDS` | `60` | Segundos de espera hasta que el motor de SQL Server esté listo |

---

## Docker Compose

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
      - "4578:4578"        # HTTPS (Cosmos Java SDK)
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock   # required for SQL + Functions
    environment:
      FLOCI_AZ_SERVICES_SQL_DATA_PLANE_PROVIDER: managed
      FLOCI_AZ_SERVICES_SQL_ACCEPT_EULA: "Y"
      # SQL Server containers bind a random port directly to the host.
      # Do NOT add those ports here — floci-az manages them via Docker socket.
```

> **Puertos sidecar:** los contenedores de SQL Server enlazan un puerto de host directamente a través del daemon de Docker;
> estos puertos **no** se publican en el servicio `floci-az`. Con
> `FLOCI_AZ_SERVICES_SQL_DEFAULT_PORT=0` (el valor por defecto), el SO asigna uno por cada servidor. Si lo estableces
> en un puerto específico, el **primer** servidor en iniciarse enlaza exactamente ese puerto; cualquier servidor adicional,
> o un inicio cuando el puerto ya está ocupado, cae en un puerto asignado por el SO con una advertencia en
> el registro. La disponibilidad se evalúa cuando el servidor se inicia, por lo que un puerto que otra cosa tome en
> el mismo instante hace fallar la creación como cualquier otro conflicto de enlace. Lee el puerto real desde los datos de
> conexión del servidor en lugar de asumirlo.

---

## Arquitectura

```
┌──────────────────────────────────────────────────────────────┐
│  Your App                                                    │
│                                                              │
│  ARM REST calls ──────► floci-az :4577 ──► SqlHandler        │
│  (create server,                          (state, routing)   │
│   create database,                                           │
│   get conn strings)                                          │
│                                                              │
│  TDS / JDBC ──────────────────────────────────────────────►  │
│  (SQL queries, DDL)          SQL Server container :59743     │
└──────────────────────────────────────────────────────────────┘
```

El plano de administración (API ARM) siempre pasa por floci-az en el puerto 4577. Con `provider=managed`,
el plano de datos se conecta **directamente** al contenedor de SQL Server en su puerto dinámico. Con
`provider=none`, no existe contenedor ni puerto de plano de datos.