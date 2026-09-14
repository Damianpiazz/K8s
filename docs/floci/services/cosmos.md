# Azure Cosmos DB (SQL API)

Compatible con el SDK `azure-cosmos` (Java, Python, JavaScript, .NET).

## Características

- **Bases de datos** — crear, obtener, listar, eliminar (elimina en cascada todos los contenedores y documentos)
- **Contenedores** — crear, reemplazar, obtener, listar, eliminar; ruta de clave de partición configurable; políticas de indexación personalizadas con índices compuestos (se conservan al crear/reemplazar y se devuelven al leer)
- **Documentos** — crear, obtener, reemplazar, eliminar, listar; upsert mediante el encabezado `x-ms-documentdb-is-upsert`
- **Tiempo de vida (TTL)** — `defaultTtl` del contenedor (se define al crear o reemplazar) con sobrescrituras `ttl` por documento; los documentos caducados desaparecen de lecturas, listados, consultas y lotes
- **Consultas** — motor SQL integrado en el proceso con soporte completo del dialecto SQL de Cosmos DB:
  - `SELECT *`, `SELECT c.field1, c.field2`, `SELECT VALUE c.field`, `SELECT TOP n`
  - `WHERE` con `=`, `!=`, `<>`, `>`, `>=`, `<`, `<=`, `IN`, `BETWEEN`, `NOT`, `AND`, `OR` y `EXISTS` correlacionado sobre arrays
  - La precedencia lógica sigue a Cosmos DB: `NOT` se enlaza antes que `AND`, y luego `OR`; los paréntesis anulan ese orden
  - Funciones de `WHERE`: `IS_DEFINED`, `IS_NULL`, `IS_STRING`, `IS_NUMBER`, `IS_BOOL`, `IS_ARRAY`, `IS_OBJECT`, `CONTAINS`, `STARTSWITH`, `ENDSWITH`, `ARRAY_CONTAINS`
  - `ORDER BY field [ASC|DESC]`, con varios campos: como en Azure, un `ORDER BY` sobre dos o más propiedades requiere un índice compuesto coincidente en el contenedor; de lo contrario, la consulta falla con `400 BadRequest` (error `SC2104`)
  - Paginación `OFFSET n LIMIT m`
  - Agregación `SELECT VALUE COUNT(1)`
  - Parámetros con nombre (`@param`), incluidos valores de array en `ARRAY_CONTAINS(@values, c.field)`;
    ambos argumentos de pertenencia pueden ser expresiones, y `NOT ARRAY_CONTAINS(...)` excluye las coincidencias
- **Propiedades del sistema** — `_rid`, `_self`, `_etag`, `_ts`, `_attachments` se generan automáticamente en cada escritura
- **Claves de partición** — se resuelven a partir del encabezado `x-ms-documentdb-partitionkey` o se extraen del cuerpo del documento usando la ruta configurada del contenedor

Las consultas con `x-ms-documentdb-partitionkey` (incluido `QueryRequestOptions.PartitionKey` de .NET)
se limitan a la partición lógica configurada antes del filtrado SQL, la agregación, la ordenación y la
paginación. Los valores de clave conservan sus tipos JSON; `[null]` y `[{}]` seleccionan las claves nula e
indefinida respectivamente. Omitir el encabezado permite consultas entre particiones. Los ámbitos mal formados
devuelven 400. Las escrituras rechazan los encabezados de partición que no coinciden con el cuerpo del documento,
y los patches no pueden cambiar los valores de la clave de partición. Los lotes transaccionales aplican las mismas
reglas antes de confirmar las escrituras preparadas.

Los tokens de continuación de consulta marcan el último documento de origen y sus valores `ORDER BY`, incluida
una identidad estable para distinguir valores de ordenación iguales. Eliminar una página ya consumida, incluso
mediante lotes transaccionales, no omite los documentos restantes. Esto también se aplica a las proyecciones que
omiten los campos de ordenación o devuelven valores escalares. `TOP` y `OFFSET ... LIMIT` conservan sus límites
totales de consulta entre páginas. Los tokens no tienen estado; no se requiere una sesión de consulta en el
servidor. Los tokens nuevos están vinculados a su texto de consulta, parámetros, cuenta, identidad de contenedor y
ámbito de partición. Reutilizarlos con una consulta o ámbito distintos devuelve `400 BadRequest`.
El orden de los parámetros, el orden de los miembros de los objetos JSON y las representaciones numéricas
equivalentes no cambian el ámbito. El orden de los elementos de los arrays sigue siendo relevante.

La paginación no ofrece aislamiento de instantáneas ante inserciones concurrentes o cambios en los valores de
ordenación. Las consultas con agregación, `GROUP BY` y `DISTINCT` conservan la paginación existente por
desplazamiento de resultados del emulador; la garantía de marcador de documento se aplica a las consultas sin
agregación ni deduplicación. Los tokens emitidos por versiones anteriores del emulador conservan su paginación
original por desplazamiento hasta completarse. Tras la actualización, inicie una consulta nueva para usar los
marcadores de documento.

### Configuración del planificador de consultas de .NET

La respuesta de la cuenta publicita las capacidades del motor de consultas. Floci-AZ emite 20 claves, mientras que
las cargas de la puerta de enlace de Azure observadas contienen 19. Dos detalles difieren de forma deliberada:

- `sqlAllowLike` es `true` para que el conjunto de capacidades publicitado coincida con lo que puede ejecutar
  el motor de consultas de Floci-AZ. Azure actualmente publicita `false`.
- `sqlDisableOptimizationFlags` no aparece en las cargas de Azure observadas. Floci-AZ emite la clave adicional
  con valor `0` para que su conjunto publicitado siga siendo un superconjunto; ningún código actual lo lee.

Son valores de compatibilidad para la planificación del cliente, no configuración del usuario.

## Endpoint

```
http://localhost:4577/{accountName}-cosmos
```

Cuenta por defecto: `devstoreaccount1`  
Endpoint por defecto: `http://localhost:4577/devstoreaccount1-cosmos`

## Conexión con SDK

=== "Java"

    ```java
    import com.azure.cosmos.CosmosClient;
    import com.azure.cosmos.CosmosClientBuilder;

    CosmosClient client = new CosmosClientBuilder()
            .endpoint("https://localhost:4578/devstoreaccount1-cosmos")
            .key("C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==")
            .gatewayMode()
            .endpointDiscoveryEnabled(false)
            .buildClient();
    ```

=== "Python"

    ```python
    from azure.cosmos import CosmosClient

    client = CosmosClient(
        url="http://localhost:4577/devstoreaccount1-cosmos",
        credential="C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==",
        connection_verify=False,
    )
    ```

=== "JavaScript / TypeScript"

    ```typescript
    import { CosmosClient } from "@azure/cosmos";

    const client = new CosmosClient({
        endpoint: "http://localhost:4577/devstoreaccount1-cosmos",
        key: "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==",
    });
    ```

> [!TIP]
> En el modo de autenticación `dev` (el predeterminado) se acepta cualquier clave: la conocida clave del emulador de Cosmos DB anterior funciona directamente con todos los SDK.

> [!NOTE]
> **Diferencia de puerto según el SDK:** el **SDK de Java** exige TLS en modo de puerta de enlace (gateway) y no puede usar HTTP simple, por lo que se conecta
> a `https://localhost:4578` (HTTPS, certificado autofirmado incluido: no se requiere importación). **Los SDK de Python y Node.js** aceptan
> HTTP simple y se conectan a `http://localhost:4577/...`.

## Referencia de la API

### Bases de datos

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/dbs` | Crear una base de datos |
| `GET` | `/dbs` | Listar todas las bases de datos |
| `GET` | `/dbs/{dbId}` | Obtener una base de datos |
| `DELETE` | `/dbs/{dbId}` | Eliminar una base de datos (en cascada a contenedores y documentos) |

### Contenedores (colecciones)

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/dbs/{dbId}/colls` | Crear un contenedor |
| `GET` | `/dbs/{dbId}/colls` | Listar contenedores |
| `GET` | `/dbs/{dbId}/colls/{collId}` | Obtener un contenedor |
| `PUT` | `/dbs/{dbId}/colls/{collId}` | Reemplazar las propiedades del contenedor (p. ej., la política de indexación o `defaultTtl`; el id y la clave de partición son inmutables) |
| `DELETE` | `/dbs/{dbId}/colls/{collId}` | Eliminar un contenedor (en cascada a los documentos) |

#### Políticas de indexación

Una `indexingPolicy` personalizada (rutas incluidas/excluidas, índices compuestos) suministrada al crear o
reemplazar el contenedor se normaliza como hace Azure (los campos que faltan se completan con valores por defecto
y el `order` de las rutas del índice compuesto toma `ascending` por defecto) y luego se conserva y se devuelve al
leer el contenedor. El `id` y la `partitionKey` de un contenedor son inmutables al reemplazarlo, igual que en Azure.

Los índices compuestos se aplican a las consultas: un `ORDER BY` sobre dos o más propiedades solo se atiende
cuando el contenedor tiene un índice compuesto cuyas rutas coinciden exactamente con la cláusula `ORDER BY`
(mismas propiedades, misma secuencia, misma longitud) con direcciones de ordenación idénticas o exactamente
invertidas en todas las rutas. Las consultas sin ese índice fallan con `400 BadRequest`
("The order by query does not have a corresponding composite index that it can be served from"),
de modo que el código que se rompería en producción también falla en local.

```json
{
  "id": "messages",
  "partitionKey": { "paths": ["/conversationId"], "kind": "Hash" },
  "indexingPolicy": {
    "indexingMode": "consistent",
    "automatic": true,
    "includedPaths": [{ "path": "/*" }],
    "excludedPaths": [{ "path": "/\"_etag\"/?" }],
    "compositeIndexes": [[
      { "path": "/conversationId", "order": "ascending" },
      { "path": "/sequence", "order": "ascending" }
    ]]
  }
}
```

### Documentos

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/dbs/{dbId}/colls/{collId}/docs` | Crear un documento |
| `GET` | `/dbs/{dbId}/colls/{collId}/docs` | Listar todos los documentos |
| `GET` | `/dbs/{dbId}/colls/{collId}/docs/{docId}` | Obtener un documento |
| `PUT` | `/dbs/{dbId}/colls/{collId}/docs/{docId}` | Reemplazar un documento |
| `DELETE` | `/dbs/{dbId}/colls/{collId}/docs/{docId}` | Eliminar un documento |

### Consultas

`POST /dbs/{dbId}/colls/{collId}/docs` con el encabezado `x-ms-documentdb-isquery: True` (o `Content-Type: application/query+json`).

## Ejemplos de petición / respuesta

### Crear una base de datos

```bash
curl -X POST http://localhost:4577/devstoreaccount1-cosmos/dbs \
  -H "Content-Type: application/json" \
  -d '{"id": "mydb"}'
```

### Crear un contenedor

```bash
curl -X POST http://localhost:4577/devstoreaccount1-cosmos/dbs/mydb/colls \
  -H "Content-Type: application/json" \
  -d '{"id": "items", "partitionKey": {"paths": ["/category"], "kind": "Hash"}}'
```

### Crear un documento

```bash
curl -X POST http://localhost:4577/devstoreaccount1-cosmos/dbs/mydb/colls/items/docs \
  -H "Content-Type: application/json" \
  -H "x-ms-documentdb-partitionkey: [\"electronics\"]" \
  -d '{"id": "laptop-1", "category": "electronics", "name": "Laptop Pro", "price": 1299}'
```

### Consultar documentos

```bash
curl -X POST http://localhost:4577/devstoreaccount1-cosmos/dbs/mydb/colls/items/docs \
  -H "Content-Type: application/query+json" \
  -H "x-ms-documentdb-isquery: True" \
  -H "x-ms-documentdb-query-enablecrosspartition: True" \
  -d '{
    "query": "SELECT * FROM c WHERE c.price > @minPrice ORDER BY c.price DESC",
    "parameters": [{"name": "@minPrice", "value": 500}]
  }'
```

### Consulta con COUNT

```bash
curl -X POST .../docs \
  -H "x-ms-documentdb-isquery: True" \
  -d '{"query": "SELECT VALUE COUNT(1) FROM c WHERE c.category = '\''electronics'\''"}'
```

Respuesta: `{"_rid": "...", "_count": 1, "Documents": [2]}`

## Funciones SQL compatibles

Gramática SQL completa de [Azure Cosmos DB](https://learn.microsoft.com/en-us/azure/cosmos-db/nosql/query/overview) implementada dentro del proceso.

### Predicados y comprobaciones de tipo

| Función | Descripción |
|---|---|
| `IS_DEFINED(c.field)` | Verdadero si el campo existe en el documento |
| `IS_NULL(c.field)` | Verdadero si el campo es nulo o no existe |
| `IS_STRING(c.field)` | Verdadero si el valor es una cadena |
| `IS_NUMBER(c.field)` | Verdadero si el valor es un número |
| `IS_INTEGER(c.field)` | Verdadero si el valor es un entero |
| `IS_BOOL(c.field)` | Verdadero si el valor es booleano |
| `IS_ARRAY(c.field)` | Verdadero si el valor es un array |
| `IS_OBJECT(c.field)` | Verdadero si el valor es un objeto |
| `IS_PRIMITIVE(c.field)` | Verdadero si el valor es un escalar (cadena, número, booleano o nulo) |
| `CONTAINS(c.field, 'str' [, true])` | Comprobación de que la cadena contiene; tercer argumento opcional para no distinguir mayúsculas |
| `STARTSWITH(c.field, 'prefix' [, true])` | Comprobación de que la cadena comienza con; tercer argumento opcional para no distinguir mayúsculas |
| `ENDSWITH(c.field, 'suffix' [, true])` | Comprobación de que la cadena termina con; tercer argumento opcional para no distinguir mayúsculas |
| `STRINGEQUALS(c.field, 'val' [, true])` | Igualdad sin distinguir mayúsculas cuando el tercer argumento es true |
| `REGEXMATCH(c.field, 'pattern' [, 'flags'])` | Coincidencia con expresión regular |
| `ARRAY_CONTAINS(c.arr, value)` | Verdadero si el array contiene el valor |
| `LIKE` | Coincidencia de patrones (`%` comodín, `_` un único carácter) |

### Funciones de cadena

| Función | Descripción |
|---|---|
| `LOWER(c.field)` | Convertir a minúsculas |
| `UPPER(c.field)` | Convertir a mayúsculas |
| `LENGTH(c.field)` | Longitud de la cadena |
| `CONCAT(s1, s2, ...)` | Concatenar cadenas |
| `SUBSTRING(s, start, length)` | Extraer una subcadena |
| `TRIM(s)` | Eliminar los espacios iniciales y finales |
| `LTRIM(s)` / `RTRIM(s)` | Eliminar los espacios iniciales / finales |
| `REPLACE(s, old, new)` | Reemplazar las ocurrencias |
| `REVERSE(s)` | Invertir la cadena |
| `INDEX_OF(s, sub)` | Posición de la primera ocurrencia (−1 si no se encuentra) |
| `LEFT(s, n)` / `RIGHT(s, n)` | Extraer los n caracteres más a la izquierda / más a la derecha |
| `TOSTRING(val)` | Convertir el valor en cadena |
| `STRINGJOIN(separator, arr)` | Unir los elementos del array con un separador |
| `STRINGSPLIT(s, delimiter)` | Dividir la cadena en un array |

### Funciones matemáticas

| Función | Descripción |
|---|---|
| `ABS(n)` | Valor absoluto |
| `CEILING(n)` | Entero más pequeño ≥ n |
| `FLOOR(n)` | Entero más grande ≤ n |
| `ROUND(n)` | Redondear al entero más cercano |
| `SQRT(n)` | Raíz cuadrada |
| `POWER(base, exp)` | Potenciación |
| `LOG(n [, base])` | Logaritmo natural o logaritmo en una base |
| `LOG10(n)` | Logaritmo en base 10 |
| `EXP(n)` | e^n |
| `SIGN(n)` | −1, 0 o 1 |
| `TRUNC(n)` | Truncar hacia cero |
| `PI()` | π |
| `RAND()` | Número aleatorio en [0, 1) |

### Funciones de arrays

| Función | Descripción |
|---|---|
| `ARRAY_LENGTH(arr)` | Número de elementos |
| `ARRAY_SLICE(arr, start [, count])` | Extraer una porción |
| `ARRAY_CONCAT(arr1, arr2, ...)` | Concatenar arrays |

### Condicional

| Función | Descripción |
|---|---|
| `IIF(condition, trueVal, falseVal)` | Expresión if-else en línea |

## Upsert

Defina `x-ms-documentdb-is-upsert: True` en `POST /docs` para crear o sobrescribir de forma silenciosa:

```bash
curl -X POST .../docs \
  -H "x-ms-documentdb-is-upsert: True" \
  -d '{"id": "laptop-1", "category": "electronics", "price": 999}'
```

## Tiempo de vida (TTL)

Los contenedores aceptan la propiedad `defaultTtl` de Azure al crearlos y reemplazarlos (`-1`, o un número
positivo de segundos hasta `2147483647`; `0` se rechaza con `400`, igual que en Azure). Los documentos pueden
sobrescribirla con su propia propiedad `ttl`. La caducidad sigue la
[semántica de TTL de Azure](https://learn.microsoft.com/en-us/azure/cosmos-db/time-to-live), medida desde la
última modificación del documento (`_ts`):

| `defaultTtl` del contenedor | `ttl` del documento | Resultado |
|---|---|---|
| ausente | cualquier valor | TTL deshabilitado: nada caduca |
| `-1` | ausente o `-1` | nunca caduca |
| `-1` | `m` | caduca `m` segundos después de `_ts` |
| `n` | ausente | caduca `n` segundos después de `_ts` |
| `n` | `-1` | nunca caduca |
| `n` | `m` | caduca `m` segundos después de `_ts` |

```bash
# Create a container whose documents expire after one hour
curl -X POST http://localhost:4577/devstoreaccount1-cosmos/dbs/mydb/colls \
  -H "Content-Type: application/json" \
  -d '{"id": "audit", "partitionKey": {"paths": ["/tenant"], "kind": "Hash"}, "defaultTtl": 3600}'

# Switch TTL off again (omit defaultTtl on replace)
curl -X PUT http://localhost:4577/devstoreaccount1-cosmos/dbs/mydb/colls/audit \
  -H "Content-Type: application/json" \
  -d '{"id": "audit"}'
```

Los documentos caducados desaparecen de inmediato de las lecturas puntuales, los listados, las consultas y los
lotes transaccionales (y ya no impiden volver a crear el mismo id). La eliminación física es diferida: un documento
caducado se purga cuando una lectura lo encuentra por última vez, lo que refleja el contrato de Azure según el cual
los elementos caducados salen de los resultados de consulta al instante, mientras que el momento de la eliminación
en segundo plano no está especificado.

## Modo de almacenamiento

```yaml
# docker-compose.yml
environment:
  FLOCI_AZ_STORAGE_MODE: memory
  FLOCI_AZ_STORAGE_SERVICES_COSMOS_MODE: wal    # full durability for Cosmos documents
```

## Variables de entorno

| Variable | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_COSMOS_ENABLED` | `true` | Habilitar o deshabilitar Cosmos DB |
| `FLOCI_AZ_SERVICES_COSMOS_MOCKED` | `false` | Conmutador principal: cuando es `true`, no se inician contenedores de motor para ninguna API (equivalente a `engines.startup=disabled`). Las rutas NoSQL/Table integradas en el proceso no se ven afectadas. |

## Motores de múltiples API

La referencia de la API anterior cubre el endpoint **SQL / NoSQL** siempre activo (`{account}-cosmos` y
`{account}-cosmos-nosql`). Floci AZ también emula las demás API de Cosmos DB mediante motores específicos por API.

Todos los motores están **deshabilitados por defecto**: habilite solo las API que usa su aplicación. Cuatro API son
**respaldadas por Docker** (MongoDB, PostgreSQL, Cassandra, Gremlin): inician un contenedor sidecar con la primera
petición. Dos API son **integradas** (NoSQL y Table): dentro del proceso, sin descarga de Docker, arranque instantáneo.

### Motores respaldados por Docker

| Variable                                              | Valor por defecto | Imagen del motor            | Puerto nativo |
|-------------------------------------------------------|---------|-----------------------------|-------------|
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_MONGODB_ENABLED`    | `false` | `mongo:7`                   | `27017`     |
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_POSTGRESQL_ENABLED` | `false` | `citusdata/citus`           | `5432`      |
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_CASSANDRA_ENABLED`  | `false` | `scylladb/scylla:6.2`       | `9042`      |
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_GREMLIN_ENABLED`    | `false` | `tinkerpop/gremlin-server`  | `8182`      |

Puede sobrescribir la imagen Docker o el puerto host de cualquier motor respaldado por Docker:

| Variable                                              | Descripción                       |
|-------------------------------------------------------|-----------------------------------|
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_MONGODB_IMAGE`      | Sobrescribir la imagen de MongoDB |
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_MONGODB_PORT`       | Sobrescribir el puerto host de MongoDB |
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_STARTUP`            | `on-demand` (por defecto) o `eager` |

**Ejemplo de docker-compose.yml: habilite MongoDB y PostgreSQL:**

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
      - "27017:27017"   # MongoDB (Cosmos MongoDB API)
      - "5432:5432"     # PostgreSQL (Cosmos PostgreSQL API)
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_AZ_SERVICES_COSMOS_ENGINES_MONGODB_ENABLED: "true"
      FLOCI_AZ_SERVICES_COSMOS_ENGINES_POSTGRESQL_ENABLED: "true"
```

**Cómo funciona:** cuando envía la primera petición a `/{account}-cosmos-mongo/`, floci-az descarga `mongo:7`
e inicia el contenedor. Las peticiones posteriores van directamente al puerto nativo del contenedor
(`localhost:27017`). El endpoint `/connect` devuelve la cadena de conexión:

```bash
curl http://localhost:4577/devstoreaccount1-cosmos-mongo/connect
# → {"api":"MONGODB","host":"localhost","port":27017,"connectionString":"mongodb://localhost:27017/","status":"running"}
```

> No publique los puertos de los motores (`27017`, `5432`, etc.) en el servicio `floci-az`. Los motores se inician como
> contenedores hermanos por el demonio Docker del host, por lo que enlazan los puertos directamente en el host.

### Motores integrados: API NoSQL y API Table (sin Docker)

Ambos motores se ejecutan por completo dentro de floci-az: sin descarga de Docker, sin tiempo de arranque de contenedor. Los datos viven en memoria;
reiniciar floci-az los borra.

| Variable                                              | Valor por defecto | Backend                                            |
|-------------------------------------------------------|---------|----------------------------------------------------|
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_NOSQL_ENABLED`      | `false` | Motor SQL integrado en el proceso: dialecto SQL completo de Cosmos DB |
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_TABLE_ENABLED`      | `false` | Motor OData en memoria (ConcurrentHashMap)         |

**Motor NoSQL** — activar este endpoint habilita el mismo motor SQL integrado que ya da servicio a
`/{account}-cosmos`. El endpoint `/connect` devuelve `https://localhost:4577` como URL de conexión
(el SDK de Java requiere TLS; habilite `FLOCI_AZ_TLS_ENABLED=true` y obtenga el certificado en tiempo de ejecución desde `GET /_floci/tls-cert`).

**Motor Table** — operaciones compatibles: crear/eliminar tabla · insertar/obtener/reemplazar/combinar/eliminar entidad ·
OData `$filter` · `$top` · `$select`. Operadores OData: `eq`, `ne`, `gt`, `ge`, `lt`, `le`, `and`, `or`, `not`.

```bash
# Enable the Table engine
export FLOCI_AZ_SERVICES_COSMOS_ENGINES_TABLE_ENABLED=true

# Trigger engine activation and retrieve connection string
curl http://localhost:4577/devstoreaccount1-cosmos-table/connect
# → {"api":"TABLE","status":"running","connectionString":"DefaultEndpointsProtocol=http;...","notes":"..."}
```

Use el `host` y el `port` de la respuesta de `/connect` para construir el endpoint y, a continuación, conéctese
con `AzureNamedKeyCredential`, el **patrón oficial de Cosmos DB for Table**
([guía de inicio rápido](https://learn.microsoft.com/en-us/azure/cosmos-db/table/quickstart-java)):

=== "Java"

    ```java
    // official Cosmos DB for Table SDK pattern
    String endpoint = "http://" + host + ":" + port + "/devstoreaccount1-cosmos-table";

    TableServiceClient client = new TableServiceClientBuilder()
        .endpoint(endpoint)
        .credential(new AzureNamedKeyCredential(
            "devstoreaccount1",
            "<devstoreaccount1-key>"))
        .buildClient();
    ```

=== "Python"

    ```python
    from azure.data.tables import TableServiceClient
    from azure.core.credentials import AzureNamedKeyCredential

    credential = AzureNamedKeyCredential("devstoreaccount1",
        "<devstoreaccount1-key>")
    client = TableServiceClient(
        endpoint=f"http://{host}:{port}/devstoreaccount1-cosmos-table",
        credential=credential)
    ```

El campo `connectionString` de la respuesta de `/connect` también está disponible para los clientes del SDK que
prefieren el formato de cadena de conexión de Azure Storage.

## Limitaciones conocidas

- **Los procedimientos almacenados, los desencadenadores y las UDF** no se ejecutan.
- **JOIN** con arrays anidados no es compatible.
- **Change feed** no se emula.
- **La búsqueda de texto completo, la búsqueda vectorial y las consultas geoespaciales** no son compatibles.
- **La gobernanza de rendimiento RU/s y la replicación en varias regiones** están fuera del alcance.
- Las rutas de clave de partición deben ser un único campo de nivel superior (p. ej., `/category`), no rutas anidadas.