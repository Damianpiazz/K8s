# Azure Container Instances (ACI)

Compatible con el SDK `azure-mgmt-containerinstance`, la CLI `az container`, `azurerm_container_group`
de Terraform y cualquier cliente compatible con ARM.

> **Modo simulado (por defecto): no se requiere Docker.** Los grupos de contenedores se emulan
> como recursos ARM de plano de control: se aprovisionan al instante con una IP sintética y
> reportan una vista de instancia `Running`.
>
> **Modo respaldado por contenedores** (planificado, PR 2) respaldará cada grupo de contenedores
> con contenedores Docker reales: los miembros del grupo comparten un espacio de nombres de red
> (`localhost` entre contenedores, como en el ACI real), los puertos publicados se asignan al host
> y `logs` devuelve la salida real del contenedor. Hasta que esté disponible,
> `FLOCI_AZ_SERVICES_ACI_MOCKED=false` se acepta pero registra un aviso de inicio y se comporta
> exactamente como el modo simulado.

---

## Características

- **Ciclo de vida** — CreateOrUpdate, Get, Delete, List (por suscripción y por grupo de recursos), UpdateTags
- **Acciones** — `start`, `stop`, `restart` con las formas LRO exactas de la especificación (sondeo mediante encabezado `Location`)
- **Registros de contenedor** — `GET .../containers/{name}/logs` (vacío en modo simulado)
- **instanceView** — estado del grupo más `currentState`/`restartCount` por contenedor en GET individuales
  (las respuestas de lista lo omiten, en consonancia con el modelo de lista de la especificación)
- **Lecturas seguras para Terraform** — `ports` del contenedor y `resources.requests` siempre están presentes
  (el servidor aplica los valores por defecto `cpu: 1.0`, `memoryInGB: 1.5` cuando se omiten, en consonancia
  con el comportamiento de la CLI `az`), y las mayúsculas de las enumeraciones se normalizan a los valores
  canónicos de Azure (`Linux`, `Always`, `TCP`, `Public`)
- **Higiene de secretos** — las variables de entorno `secureValue`, las contraseñas de `imageRegistryCredentials`
  y el contenido de los volúmenes de secretos se aceptan pero nunca se devuelven en las respuestas
- **Índice de recursos** — los grupos aparecen en `GET .../resourceGroups/{rg}/resources`, de modo que
  `terraform destroy` los ve antes de eliminar un grupo de recursos

---

## Endpoints

Todas las operaciones usan rutas ARM:

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerInstance/containerGroups/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerInstance/containerGroups/{name}
PATCH  /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerInstance/containerGroups/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerInstance/containerGroups/{name}
POST   .../containerGroups/{name}/{start|stop|restart}
GET    .../containerGroups/{name}/containers/{container}/logs?tail=&timestamps=
GET    .../containerGroups/{name}/outboundNetworkDependenciesEndpoints
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerInstance/containerGroups
GET    /subscriptions/{sub}/providers/Microsoft.ContainerInstance/containerGroups
GET    /subscriptions/{sub}/providers/Microsoft.ContainerInstance/locations/{loc}/{cachedImages|capabilities|usages}
```

---

## Inicio rápido

### 1 — Crear un grupo de contenedores

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ContainerInstance/containerGroups/my-app?api-version=2023-05-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "containers": [
        {
          "name": "web",
          "properties": {
            "image": "hashicorp/http-echo:latest",
            "command": ["/http-echo", "-text=hello"],
            "ports": [{"port": 5678}],
            "resources": {"requests": {"cpu": 0.5, "memoryInGB": 0.5}}
          }
        }
      ],
      "osType": "Linux",
      "ipAddress": {"type": "Public", "ports": [{"port": 5678}], "dnsNameLabel": "my-app"}
    }
  }'
```

El grupo se devuelve con `properties.provisioningState = "Succeeded"`, una IP y
`fqdn = "my-app.eastus.azurecontainer.io"`.

### 2 — Leer registros y estado

```bash
BASE="http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ContainerInstance/containerGroups/my-app"
curl -s "$BASE?api-version=2023-05-01"                              # full resource + instanceView
curl -s "$BASE/containers/web/logs?api-version=2023-05-01"          # {"content": "..."}
```

### 3 — Acciones

```bash
curl -si -X POST "$BASE/stop?api-version=2023-05-01"      # 204, synchronous
curl -si -X POST "$BASE/start?api-version=2023-05-01"     # 202 + Location (poll -> Succeeded)
curl -si -X POST "$BASE/restart?api-version=2023-05-01"   # 204 + Location
```

---

## Configuración

```yaml
floci-az:
  services:
    aci:
      enabled: true
      mocked: true              # true = no Docker, pure ARM state. false = container-backed (PR 2)
      base-port: 7500           # host-port range for published group ports
      max-port: 7599
```

| Variable de entorno | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_ACI_ENABLED` | `true` | Habilitar o deshabilitar el servicio |
| `FLOCI_AZ_SERVICES_ACI_MOCKED` | `true` | Modo simulado (sin Docker). `false` está reservado para el modo respaldado por contenedores (PR 2) y, por ahora, se comporta como `true` con un aviso de inicio |
| `FLOCI_AZ_SERVICES_ACI_BASE_PORT` | `7500` | Inicio del rango de puertos host para los puertos publicados (solo en el modo respaldado por contenedores) |
| `FLOCI_AZ_SERVICES_ACI_MAX_PORT` | `7599` | Fin del rango de puertos host (solo en el modo respaldado por contenedores) |

---

## Notas y limitaciones

- `exec` y `attach` devuelven un **501** honesto: en Azure real entregan un websocket activo,
  que el emulador no proporciona.
- Los volúmenes `azureFile` y `gitRepo` se rechazan con un **400**; los volúmenes `emptyDir` y
  `secret` son compatibles.
- Las sondas de liveness/readiness, `identity`, `diagnostics`, `dnsConfig`, `subnetIds`, los recursos
  de GPU y las SKU confidenciales o de interrupción (spot) se almacenan y se devuelven, pero no se aplican.
- `containerGroupProfiles` y `ngroups` (incorporados en 2025-09-01) no están implementados.
- `ipAddress.fqdn` es cosmético: nada resuelve `*.azurecontainer.io` en local.
- El modo simulado reporta todos los contenedores como `Running` sin ejecutar nada; utilice el modo
  respaldado por contenedores (PR 2) para obtener el estado real.