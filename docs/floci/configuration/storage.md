# Modos de almacenamiento

Floci-AZ admite cuatro backends de almacenamiento. Define el valor global por defecto con `FLOCI_AZ_STORAGE_MODE`
y anúlalo por servicio si es necesario.

| Modo | Comportamiento | Ideal para |
|:---:|---|---|
| **`memory`** | Totalmente en RAM. Los datos se pierden cuando el contenedor se detiene. | Velocidad, pruebas efímeras, pipelines de CI. |
| **`persistent`** | Cargado al arrancar, vaciado a disco en el apagado controlado. | Desarrollo local simple con preservación de estado. |
| **`hybrid`** | En memoria con vaciado asíncrono periódico cada 5 s. | Mejor equilibrio entre velocidad y seguridad. |
| **`wal`** | Write-Ahead Log — cada mutación se escribe en disco antes de responder. | Máxima durabilidad. |

## Modo global

```yaml
environment:
  FLOCI_AZ_STORAGE_MODE: hybrid
```

## Anulaciones por servicio

Puedes definir un modo diferente para cada servicio de forma independiente:

```yaml
environment:
  FLOCI_AZ_STORAGE_MODE: memory                    # default for all services
  FLOCI_AZ_STORAGE_SERVICES_BLOB_MODE: wal         # blob writes every mutation to disk
  FLOCI_AZ_STORAGE_SERVICES_QUEUE_MODE: hybrid     # queue flushes every 5 s
  FLOCI_AZ_STORAGE_SERVICES_TABLE_MODE: persistent # table flushes on shutdown
  FLOCI_AZ_STORAGE_SERVICES_APP_CONFIG_MODE: wal   # app config writes every mutation to disk
```

## Directorio de persistencia

Monta un directorio local y apunta `FLOCI_AZ_STORAGE_PERSISTENT_PATH` hacia él:

```yaml
volumes:
  - ./data:/app/data
environment:
  FLOCI_AZ_STORAGE_MODE: hybrid
  FLOCI_AZ_STORAGE_PERSISTENT_PATH: /app/data
```

La ruta por defecto dentro del contenedor es `/app/data`, por lo que el montaje de volumen anterior es
suficiente — solo necesitas `FLOCI_AZ_STORAGE_PERSISTENT_PATH` cuando usas una ruta no por defecto.

## Ajuste de WAL y Hybrid

| Variable | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_STORAGE_WAL_COMPACTION_INTERVAL_MS` | `30000` | Cada cuánto se compacta el log WAL (ms) |
| `FLOCI_AZ_STORAGE_HYBRID_FLUSH_INTERVAL_MS` | `5000` | Cada cuánto el modo híbrido vacía a disco (ms) |