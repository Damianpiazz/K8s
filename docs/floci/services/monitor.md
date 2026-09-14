# Azure Monitor / Log Analytics

Compatible con los SDK `azure-monitor-ingestion` y `azure-monitor-query`, el
plano de administración ARM de `Microsoft.OperationalInsights` / `Microsoft.Insights` y cualquier cliente HTTP.
Floci-AZ emula la **API de ingesta de registros** (envío de registros personalizados a través de una regla de recopilación de datos) y
la **API de consulta de Log Analytics** (lectura de esos mismos registros con un subconjunto de KQL), todo en proceso.

> **Solo HTTP — sin Docker.** Los workspaces, los endpoints y las reglas de recopilación de datos, la ingesta y las consultas
> se manejan todo en proceso. No hay sidecar.

---

## Características

- **Workspaces de Log Analytics** — `Microsoft.OperationalInsights/workspaces` CreateOrUpdate, Get,
  Delete; se genera un `customerId` (GUID del workspace) cuando no se proporciona y se indexa para la
  resolución de consultas
- **Endpoints de recopilación de datos (DCE)** — `Microsoft.Insights/dataCollectionEndpoints` CreateOrUpdate,
  Get, Delete; el `logsIngestion.endpoint` devuelto apunta de vuelta a la URL base del emulador
- **Reglas de recopilación de datos (DCR)** — `Microsoft.Insights/dataCollectionRules` CreateOrUpdate, Get,
  Delete; se genera un `immutableId` cuando no se proporciona y se usa para enrutar la ingesta
- **API de ingesta de registros** — `POST /dataCollectionRules/{immutableId}/streams/{stream}` acepta un arreglo
  JSON de registros, resuelve el destino de Log Analytics del DCR y almacena cada registro en
  el workspace de destino (`TimeGenerated` se respeta o se establece por defecto a la hora actual)
- **API de consulta de registros** — `POST /v1/workspaces/{workspaceId}/query` ejecuta un subconjunto de KQL sobre los
  registros almacenados y devuelve la forma estándar `{tables:[{name,columns,rows}]}` con tipos de columna inferidos

---

## Endpoints

Las operaciones de administración usan rutas ARM; la ingesta y la consulta usan las rutas del plano de datos de Monitor.

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.OperationalInsights/workspaces/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.OperationalInsights/workspaces/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.OperationalInsights/workspaces/{name}

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Insights/dataCollectionEndpoints/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Insights/dataCollectionEndpoints/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Insights/dataCollectionEndpoints/{name}

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Insights/dataCollectionRules/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Insights/dataCollectionRules/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Insights/dataCollectionRules/{name}

POST   /dataCollectionRules/{immutableId}/streams/{stream}   # data-plane ingestion
POST   /v1/workspaces/{workspaceId}/query                    # data-plane query
```

---

## Inicio rápido

### 1 — Crear un workspace, un DCE y un DCR

```bash
BASE="http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers"
API="?api-version=2023-09-01"

# Workspace — capture the returned properties.customerId for queries
curl -s -X PUT "$BASE/Microsoft.OperationalInsights/workspaces/my-ws$API" \
  -H "Content-Type: application/json" \
  -d '{"location":"eastus","properties":{}}'

# Data Collection Endpoint
curl -s -X PUT "$BASE/Microsoft.Insights/dataCollectionEndpoints/my-dce$API" \
  -H "Content-Type: application/json" \
  -d '{"location":"eastus","properties":{}}'

# Data Collection Rule — destination is the workspace; capture properties.immutableId
curl -s -X PUT "$BASE/Microsoft.Insights/dataCollectionRules/my-dcr$API" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "destinations": {
        "logAnalytics": [{
          "name": "ws",
          "workspaceResourceId": "/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.OperationalInsights/workspaces/my-ws"
        }]
      }
    }
  }'
```

### 2 — Ingresar registros

Envía un arreglo JSON de registros al `immutableId` del DCR y al nombre del flujo:

```bash
curl -s -X POST \
  "http://localhost:4577/dataCollectionRules/<immutableId>/streams/Custom-MyTable_CL?api-version=2023-01-01" \
  -H "Content-Type: application/json" \
  -d '[
    {"TimeGenerated":"2026-06-25T10:00:00Z","Level":"INFO","Message":"hello"},
    {"TimeGenerated":"2026-06-25T10:01:00Z","Level":"ERROR","Message":"boom"}
  ]'
# → 204 No Content
```

### 3 — Consultar registros

```python
from azure.identity import DefaultAzureCredential
from azure.monitor.query import LogsQueryClient

client = LogsQueryClient(DefaultAzureCredential(), endpoint="http://localhost:4577")
response = client.query_workspace(
    workspace_id="<customerId>",
    query="MyTable_CL | where Level == 'ERROR' | project TimeGenerated, Message | take 10",
    timespan=None,
)
for table in response.tables:
    print(table.columns, table.rows)
```

El workspace se puede direccionar por su GUID `customerId` o por el nombre del workspace; el nombre del flujo/tabla
coincide con o sin el prefijo `Custom-` y el sufijo `_CL` que usan los registros personalizados.

---

## Subconjunto de KQL compatible

El motor de consultas implementa un subconjunto pragmático de KQL — suficiente para las aserciones que suelen hacer las
suites de pruebas y los tableros locales:

| Operador | Notas |
|---|---|
| `where <expr>` | comparaciones `==`, `!=`, `>`, `<`, `>=`, `<=` contra columnas de cadena/número/fecha-hora |
| `project <cols>` | proyección de columnas separadas por comas |
| `take N` / `limit N` | límite de filas |
| `timespan` | `timespan` a nivel de solicitud (una duración ISO-8601 como `PT1H`, o un rango de inicio/fin) filtra por `TimeGenerated` antes de ejecutar la consulta |

Los tipos de columna de la respuesta se infieren de los datos (`datetime` para `TimeGenerated`; `bool`,
`long`, `real`; en caso contrario `string`).

---

## Configuración

```yaml
floci-az:
  services:
    monitor:
      enabled: true
```

| Variable de entorno | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_MONITOR_ENABLED` | `true` | Habilitar/deshabilitar el servicio |

Los registros almacenados usan el `StorageBackend` global (`FLOCI_AZ_STORAGE_MODE`), por lo que pueden sobrevivir
a los reinicios en los modos `persistent`/`hybrid`/`wal`.

---

## Notas y limitaciones

- **KQL es un subconjunto.** `summarize`, `extend`, `join`, `order by`, `parse`, las funciones escalares y las
  agregaciones no están implementados — solo `where` / `project` / `take` / `limit` más el filtrado por `timespan`.
- **Las métricas están fuera de alcance.** Solo se emula la superficie de registros (ingesta + consulta de Log Analytics);
  `Microsoft.Insights/metrics`, las alertas, los grupos de acciones y el escalado automático no.
- **La autenticación es permisiva.** Los tokens Bearer se aceptan pero no se validan (modo de desarrollo), igual que el resto
  del emulador.
- **Las transformaciones se ignoran.** Los `dataFlows`/`transformKql` de un DCR se almacenan pero no se aplican;
  los registros se escriben en el workspace tal como se envían.