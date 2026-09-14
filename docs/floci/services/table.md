# Table Storage

Compatible con los SDK `azure-data-tables` (Java, Python, Node.js) y las cadenas de conexión
estilo Azurite. Habla el protocolo REST de Azure Table con payloads OData/JSON y autenticación
de clave compartida.

> **Solo HTTP — sin Docker.** Los datos los guarda el [backend de almacenamiento](../configuration/storage.md) configurado
> (`memory` por defecto; `persistent`, `hybrid` o `wal` para durabilidad).

---

## Características

- **Tablas** — Crear, Eliminar, Listar; la creación duplicada devuelve `409 TableAlreadyExists`
- **Entidades** — Insertar, Actualizar (reemplazar/combinar), Upsert, Eliminar, Obtener por
  `PartitionKey` + `RowKey`; una entidad inexistente devuelve la forma Azure `404 ResourceNotFound`
- **Consulta** — expresiones OData `$filter`: igualdad sobre `PartitionKey`/`RowKey`, comparaciones
  numéricas y combinaciones; `$select` proyecta un subconjunto de propiedades
- **Paginación** — los conjuntos de resultados grandes se paginan con tokens de continuación
  (encabezados `x-ms-continuation-Next*`)
- **Concurrencia optimista** — `ETag` / `If-Match` en la actualización y la eliminación; un ETag obsoleto se rechaza
- **Transacciones por lotes** — los conjuntos de cambios multiparte `$batch` se aplican de forma atómica
- **Propiedades del servicio** — `restype=service&comp=properties` devuelve un documento estático
  `<StorageServiceProperties>` (registro y métricas deshabilitados); Set se acepta como no-op

## Endpoint

```
http://localhost:4577/{account}-table/Tables                                   # table operations
http://localhost:4577/{account}-table/{table}(PartitionKey='p',RowKey='r')     # entity operations
http://localhost:4577/{account}-table/$batch                                   # batch transactions
```

## Inicio rápido

=== "Python"

    ```python
    from azure.data.tables import TableServiceClient

    conn = ("DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
            "AccountKey=<devstoreaccount1-key>;"
            "TableEndpoint=http://localhost:4577/devstoreaccount1-table;")
    svc = TableServiceClient.from_connection_string(conn)
    table = svc.create_table("people")
    table.upsert_entity({"PartitionKey": "team", "RowKey": "alice", "role": "eng"})
    for e in table.query_entities("PartitionKey eq 'team'"):
        print(e["RowKey"], e["role"])
    ```

La clave de desarrollo bien conocida de Azurite (`<devstoreaccount1-key>`) se acepta; cualquier nombre de cuenta funciona.

## Configuración

```yaml
floci-az:
  services:
    table:
      enabled: true
  storage:
    services:
      table:
        # mode: persistent     # override the global storage.mode for table only
        flush-interval-ms: 5000
```

| Propiedad | Variable de entorno | Valor por defecto | Descripción |
|---|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_TABLE_ENABLED` | `true` | Habilita el servicio Table Storage |
| `storage.services.table.mode` | `FLOCI_AZ_STORAGE_SERVICES_TABLE_MODE` | *(hereda de `storage.mode`)* | Anulación del backend por servicio (`memory` / `persistent` / `hybrid` / `wal`) |
| `storage.services.table.flush-interval-ms` | `FLOCI_AZ_STORAGE_SERVICES_TABLE_FLUSH_INTERVAL_MS` | `5000` | Intervalo de vaciado a disco en segundo plano solo para el modo `hybrid`; lo ignoran `memory` / `persistent` / `wal` (`wal` compacta según `storage.wal.compaction-interval-ms`) |

## Desviaciones intencionales

- **Las firmas de clave compartida se aceptan pero no se verifican criptográficamente.**
- **La gramática de `$filter` es un subconjunto práctico** — se admiten igualdad, comparación numérica y composición
  booleana básica; las funciones OData completas (`substringof`, `startswith`, aritmética de fechas) no.
- **Sin aplicación de SAS** — los parámetros de consulta SAS se analizan pero no se validan.