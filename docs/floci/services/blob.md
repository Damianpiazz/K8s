# Blob Storage

Compatible con los SDK de `azure-storage-blob` (Java, Python, Node.js), flujos concretos del SDK Java
`azure-storage-file-datalake`, la CLI de Azure (`az storage blob`) y cadenas de conexión estilo Azurite.
Implementa el protocolo REST de Azure Storage Blob con autenticación Shared Key, respuestas XML de Blob y el
alias de host DFS de Data Lake Storage Gen2.

> **Solo HTTP, sin Docker.** Los datos los almacena el [backend de almacenamiento](../configuration/storage.md)
> configurado (`memory` por defecto; `persistent`, `hybrid` o `wal` para persistencia).

---

## Características

- **Contenedores** — Create, Get properties, Delete, List (`?comp=list`); una creación duplicada devuelve
  `409 ContainerAlreadyExists`
- **Blobs** — Put (subir), Get (descargar), Delete, List dentro de un contenedor; semántica de sobrescritura
- **Blobs en bloques** — carga de bloques por etapas (`?comp=block`) seguida de la confirmación (`?comp=blocklist`)
  para cargas grandes, además de `Put Blob` de una sola petición
- **Alias de endpoint de Data Lake Storage Gen2** — el host `{account}.dfs.core.windows.net` se asigna al
  backend de Blob para que los clientes de rutas del SDK ADLS puedan crear, leer, escribir y eliminar rutas
  a través del mismo almacén de datos local
- **Emisión de claves de delegación de usuario** — `POST ?restype=service&comp=userdelegationkey` devuelve
  XML con la forma de Azure para que el SDK genere flujos SAS de delegación de usuario
- **Aplicación de SAS de delegación de usuario** — valida las firmas SAS de delegación de usuario generadas por
  el SDK, la caducidad, la validez de la clave firmada, los permisos y el ámbito de recursos de
  contenedor/blob/directorio para las operaciones de rutas de Blob y ADLS
- **Operaciones de ruta ADLS Gen2 / Hadoop ABFS 3.3.4** — admite las formas de cable DFS que usa
  `org.apache.hadoop:hadoop-azure:3.3.4`: creación condicional de archivos/directorios, estado y propiedades
  de ruta, append/flush, eliminación recursiva, cambio de nombre de archivos/directorios, metadatos POSIX de
  propietario/grupo/permiso/ACL, `checkAccess` y la API POST de ADLS Path Lease. El flujo predeterminado de
  sobrescritura de creación condicional de Hadoop (`409` -> status/ETag -> `If-Match`) está modelado de forma explícita.
- **Operaciones de sistema de archivos ADLS** — crear/eliminar un sistema de archivos y obtener/definir sus
  propiedades mediante `?resource=filesystem`. Se admite `getAccessControl` en la raíz para que Hadoop pueda
  detectar HNS automáticamente y resolver `getFileStatus("/")` sin forzar `fs.azure.account.hns.enabled=true`.
- **ADLS Path - List** — admite `listPaths` del SDK DataLake de Java y `listStatus` de Hadoop, incluidos los
  listados recursivos/no recursivos, los filtros de directorio, los resultados singleton de archivos exactos,
  la paginación del servidor y el token de continuación `startFrom` HNS en Base64 de Hadoop 3.3.4.
- **Modo AppendBlob de ADLS** — reconoce el modo de creación `blobType=AppendBlob` de Hadoop y aplica posiciones
  de append secuenciales, incluidas las peticiones de append con `flush=true` / `close=true`.
- **Descarga por rango** — `Range: bytes=…` devuelve `206 Partial Content`
- **Descarga condicional** — se respetan `If-Match` / `If-None-Match`; un ETag obsoleto se rechaza
- **Metadatos** — `x-ms-meta-*` se definen al subir y se devuelven en Get, con ida y vuelta exacta
- **Semántica de no encontrado** — cuando falta un blob/contenedor se devuelve la forma de error XML de Azure
  `404 BlobNotFound` / `ContainerNotFound`

## Endpoint

```
http://localhost:4577/{account}/{container}                  # container operations
http://localhost:4577/{account}/{container}/{blob}           # blob operations
http://localhost:4577/{account}/{filesystem}?resource=filesystem&recursive=true  # ADLS listPaths
```

La cuenta también responde en la dirección con estilo de host `{account}.blob.core.windows.net` (y en el alias
de Data Lake Gen2 `{account}.dfs.core.windows.net`, que se asigna al mismo backend de blob) cuando se define el
encabezado `Host`, en consonancia con la forma en que los SDK abordan los endpoints de almacenamiento.

Las respuestas ARM de la cuenta de almacenamiento incluyen los endpoints principales `blob` y `dfs` para que los
clientes del SDK de Data Lake puedan detectar la forma del endpoint Gen2.

## Inicio rápido

=== "Python"

    ```python
    from azure.storage.blob import BlobServiceClient

    conn = ("DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
            "AccountKey=<devstoreaccount1-key>;"
            "BlobEndpoint=http://localhost:4577/devstoreaccount1;")
    svc = BlobServiceClient.from_connection_string(conn)
    container = svc.create_container("my-container")
    container.upload_blob("hello.txt", b"hello world")
    print(container.download_blob("hello.txt").readall())
    ```

=== "Azure CLI"

    ```bash
    az storage container create --name my-container \
      --connection-string "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=...;BlobEndpoint=http://localhost:4577/devstoreaccount1;"
    ```

Se acepta la conocida clave de desarrollo de Azurite (`<devstoreaccount1-key>`); cualquier nombre de cuenta funciona.

## Configuración

```yaml
floci-az:
  services:
    blob:
      enabled: true
  storage:
    services:
      blob:
        # mode: wal            # override the global storage.mode for blob only
        flush-interval-ms: 5000
```

| Propiedad | Variable de entorno | Valor por defecto | Descripción |
|---|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_BLOB_ENABLED` | `true` | Habilita el servicio Blob Storage |
| `storage.services.blob.mode` | `FLOCI_AZ_STORAGE_SERVICES_BLOB_MODE` | *(hereda `storage.mode`)* | Sobrescritura del backend por servicio (`memory` / `persistent` / `hybrid` / `wal`) |
| `storage.services.blob.flush-interval-ms` | `FLOCI_AZ_STORAGE_SERVICES_BLOB_FLUSH_INTERVAL_MS` | `5000` | Intervalo de volcado a disco en segundo plano solo para el modo `hybrid`; lo ignoran `memory` / `persistent` / `wal` (`wal` compacta según `storage.wal.compaction-interval-ms`) |

## Desviaciones intencionales

- **Las firmas Shared Key se aceptan pero no se verifican criptográficamente** — el emulador es un
  objetivo de desarrollo local; se honra cualquier encabezado `Authorization` bien formado (o la clave de Azurite).
- **El comportamiento de las operaciones grandes de ADLS está simplificado** — el cambio de nombre de
  directorios y la eliminación recursiva se completan de forma atómica en el backend local, sin reproducir el
  procesamiento por lotes de varias peticiones del lado del servidor de Azure. Hadoop observa una operación
  completada sin token de continuación, lo que es compatible con el protocolo para el llamador. No se modela la
  matriz completa de transferencia de concesiones origen/destino y condiciones de tiempo de Azure.
- **El control de acceso de ADLS es compatible con metadatos, no un motor completo de autorización POSIX** — el
  propietario, el grupo, los permisos y las ACL circulan de ida y vuelta por el protocolo de cable Hadoop/DFS, y
  `checkAccess` valida la existencia de la ruta y la forma de la petición. No deniega peticiones según ACL POSIX
  emuladas; la autorización de autenticación/SAS sigue siendo el límite de control de acceso del emulador.
- **La semántica de cierre/eventos de ADLS es solo de almacenamiento** — se acepta `close=true` y los datos
  confirmados son visibles de inmediato, pero no se emulan los efectos secundarios de Azure Event Grid/notificaciones de cambio.
- **La aplicación de SAS admite SAS de servicio de clave compartida y SAS de delegación de usuario.** Las firmas
  de SAS de servicio usan `floci-az.auth.storage-account-keys`, un mapa de nombres de cuenta a claves base64.
  La entrada por defecto `devstoreaccount1` es la clave estándar de Azurite; sobrescríbala cuando los clientes
  usen una clave distinta. Se rechazan las cuentas desconocidas y las firmas no válidas, incluso en modo de
  desarrollo, así como los tokens caducados y las operaciones fuera de los permisos concedidos. Esto es
  independiente del comportamiento permisivo del encabezado `Authorization` de Shared Key descrito arriba.
  El SAS de servicio de clave compartida admite el ámbito de directorio (`sr=d`). El SAS de servicio de directorio
  requiere `sdd` y la versión 2020-02-10 o posterior. Se validan los tokens SAS de delegación de usuario generados
  por el SDK para los recursos de contenedor (`sr=c`), blob (`sr=b`) y directorio ADLS (`sr=d`).
  El SAS de cuenta, las políticas de acceso almacenadas, las restricciones de IP/protocolo y la matriz completa
  de características de SAS no están modelados por completo. Las claves de delegación de usuario están protegidas
  por un secreto local al proceso, por lo que los tokens SAS emitidos por un proceso del emulador anterior no son
  válidos tras un reinicio.
- **Las instantáneas, el control de versiones y las capas (tiering) no están modelados.** Las concesiones de blob
  y las concesiones de ruta ADLS comparten el estado de concesión en memoria del emulador y admiten
  acquire/renew/change/release/break. El estado de concesión es deliberadamente local al proceso y se pierde cuando
  el emulador se reinicia.
- **`x-ms-server-encrypted: true` se reporta aunque no se realiza ningún cifrado** — los datos de blob los
  almacena tal cual el backend de almacenamiento configurado. El encabezado refleja el
  `x-ms-request-server-encrypted` que ya se devuelve al subir y existe por compatibilidad con el SDK.