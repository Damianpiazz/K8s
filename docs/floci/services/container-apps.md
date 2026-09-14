# Azure Container Apps

Compatible con los clientes de Azure Resource Manager que usan `Microsoft.App/managedEnvironments` y `Microsoft.App/containerApps`.

El modo real ejecuta cada réplica de revisión activa como contenedores Docker. El modo simulado mantiene el estado completo de ARM y de las revisiones sin iniciar Docker.

## Características

- Crear, obtener, actualizar, eliminar y listar Managed Environment
- Crear, obtener, actualizar, eliminar y listar Container App
- Plantillas con versionado y modos de revisión activa Single y Multiple
- Listar, obtener, activar, desactivar y reiniciar revisiones
- Comandos de contenedor, argumentos, variables de entorno y referencias a secretos
- Entrada HTTP externa e interna reenviada a los contenedores de revisiones en ejecución
- Tráfico de revisión ponderado y selección de réplicas round-robin con conmutación por error de réplicas no saludables
- Validación de `minReplicas` y `maxReplicas`; las réplicas locales arrancan en `minReplicas`
- Las apps con escala a cero inician una réplica con la primera petición de entrada cuando `maxReplicas` lo permite
- Modo simulado para pruebas sin Docker

Las reglas de escala más allá de las réplicas mínimas/máximas se conservan en las respuestas ARM pero no se evalúan en local.
La entrada interna solo acepta pares de transporte cuya IP de origen pertenezca a una subred IPAM de Docker usada por una réplica de Container App en ejecución. Los encabezados reenviados no pueden convertir a un llamador público en interno.

## Endpoints ARM

```text
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/managedEnvironments/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/managedEnvironments/{name}
PATCH  /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/managedEnvironments/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/managedEnvironments/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/managedEnvironments
GET    /subscriptions/{sub}/providers/Microsoft.App/managedEnvironments
POST   .../managedEnvironments/{name}/checkNameAvailability

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/containerApps/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/containerApps/{name}
PATCH  /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/containerApps/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/containerApps/{name}
POST   .../containerApps/{name}/listSecrets
GET    .../containerApps/{name}/revisions
GET    .../containerApps/{name}/revisions/{revision}
POST   .../containerApps/{name}/revisions/{revision}/activate
POST   .../containerApps/{name}/revisions/{revision}/deactivate
POST   .../containerApps/{name}/revisions/{revision}/restart
```

## Ejemplo

Crear un entorno:

```bash
curl -X PUT 'http://localhost:4577/subscriptions/dev/resourceGroups/apps/providers/Microsoft.App/managedEnvironments/local?api-version=2025-07-01' \
  -H 'Content-Type: application/json' \
  -d '{"location":"eastus","properties":{}}'
```

Crear una app accesible desde el exterior:

```bash
curl -X PUT 'http://localhost:4577/subscriptions/dev/resourceGroups/apps/providers/Microsoft.App/containerApps/hello?api-version=2025-07-01' \
  -H 'Content-Type: application/json' \
  -d '{
    "location":"eastus",
    "properties":{
      "environmentId":"/subscriptions/dev/resourceGroups/apps/providers/Microsoft.App/managedEnvironments/local",
      "configuration":{
        "activeRevisionsMode":"Single",
        "secrets":[{"name":"token","value":"local-secret"}],
        "ingress":{"external":true,"targetPort":80}
      },
      "template":{
        "revisionSuffix":"v1",
        "containers":[{
          "name":"web",
          "image":"nginx:alpine",
          "env":[{"name":"TOKEN","secretRef":"token"}]
        }],
        "scale":{"minReplicas":1,"maxReplicas":3}
      }
    }
  }'
```

La respuesta devuelve un valor único global en `properties.configuration.ingress.fqdn`. Enrute ese nombre de host hacia floci-az y, a continuación, llámelo a través del puerto 4577:

```bash
FQDN=$(curl -s 'http://localhost:4577/subscriptions/dev/resourceGroups/apps/providers/Microsoft.App/containerApps/hello?api-version=2025-07-01' | jq -r '.properties.configuration.ingress.fqdn')
curl -H "Host: $FQDN" http://localhost:4577/
```

## Configuración

```yaml
floci-az:
  services:
    container-apps:
      enabled: true
      mocked: true
      dns-suffix: azurecontainerapps.io
      ingress-timeout-seconds: 60
```

| Variable de entorno | Valor por defecto | Descripción |
|---|---:|---|
| `FLOCI_AZ_SERVICES_CONTAINER_APPS_ENABLED` | `true` | Habilita el enrutado de `Microsoft.App` |
| `FLOCI_AZ_SERVICES_CONTAINER_APPS_MOCKED` | `true` | Mantiene el estado de ARM sin contenedores Docker |
| `FLOCI_AZ_SERVICES_CONTAINER_APPS_DNS_SUFFIX` | `azurecontainerapps.io` | Sufijo devuelto en los FQDN de entornos y apps |
| `FLOCI_AZ_SERVICES_CONTAINER_APPS_INGRESS_TIMEOUT_SECONDS` | `60` | Tiempo de espera de conexión/petición del backend |

Defina `mocked: false` para habilitar el modo real, que requiere acceso al demonio Docker. Los contenedores
de la plantilla en una misma réplica comparten el espacio de nombres de red del contenedor líder, de modo que los
sidecars pueden comunicarse por `localhost`. El líder recibe el enlace dinámico de puerto host para el puerto de
destino compartido de la entrada. Una réplica solo se vuelve saludable después de que ese puerto acepte conexiones TCP.
Las peticiones siguen entrando en floci-az por el puerto 4577.