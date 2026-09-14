# Azure Container Registry

Compatible con el SDK `azure-mgmt-containerregistry`, la CLI `az acr`, `azurerm_container_registry`
de Terraform y cualquier cliente compatible con ARM para el plano de administración, además de
**cualquier cliente Docker estándar** (`docker login` / `push` / `pull`, herramientas OCI) para el plano
de datos.

## Características

- **Ciclo de vida del registro** — crear, obtener, actualizar, eliminar; listar por grupo de recursos y por suscripción
- **Credenciales de administración** — `listCredentials` / `regenerateCredential` (nombre de usuario + dos contraseñas)
- **Disponibilidad de nombre** — `checkNameAvailability`
- **Usos** — `listUsages` (informe de cuota estático)
- **Plano de datos** — un único sidecar `registry:2` compartido que expone la **API HTTP V2 de Docker Registry**
  (`/v2/…`), de modo que las imágenes se suben (push) y se descargan (pull) con el cliente Docker estándar. Todos
  los registros están respaldados por un único contenedor y aislados mediante un prefijo interno de repositorio
  (`{registryName}/{repo}`)

## Endpoint

El plano de administración (ARM) pasa por el puerto `4577`:

```
PUT|GET|PATCH|DELETE  /subscriptions/{s}/resourceGroups/{rg}/providers/Microsoft.ContainerRegistry/registries/{name}
POST                  .../registries/{name}/listCredentials | regenerateCredential | importImage
GET                   .../registries/{name}/listUsages
POST                  /subscriptions/{s}/providers/Microsoft.ContainerRegistry/checkNameAvailability
```

El **plano de datos** lo sirve directamente el sidecar del registro (no pasa por el proxy de `4577`).

> **Desviación de `loginServer` (estilo de ruta).** Como un único registro compartido respalda todos los ACR, el
> nombre del registro pasa del host a la ruta: `loginServer` es `localhost:{port}/{registryName}` de forma nativa
> (o `{container}:5000/{registryName}` cuando floci-az se ejecuta en Docker), **no** `{name}.azurecr.io`.
> Docker lo gestiona de forma transparente: `docker login localhost:{port}` ignora la ruta, y una referencia de
> imagen como `localhost:{port}/{registryName}/app` se interpreta como el registro `localhost:{port}` + el repositorio
> `{registryName}/app`. Docker trata automáticamente `localhost:PORT` como inseguro (HTTP simple), por lo que no se
> necesita configuración del demonio. El plano de datos está activado por defecto (`mocked: false`). En el modo
> **simulado** (sin Docker), `loginServer` es el `{name}.azurecr.io` cosmético para mantener la fidelidad del plano
> de administración.

## Autenticación

El registro compartido subyacente se ejecuta de forma **anónima** (siguiendo el diseño de AWS ECR del emulador
hermano): `docker push`/`pull` funcionan sin iniciar sesión. El plano de administración sigue emitiendo credenciales
de administrador (`listCredentials` / `regenerateCredential`, nombre de usuario = nombre del registro), y
`docker login` con ellas tiene éxito; sin embargo, las credenciales **no se aplican** en el plano de datos. El ACR real
aplica la autenticación básica de usuario administrador y los tokens de AAD; reproducir la autenticación por registro
en un único registro compartido está fuera del alcance.

## Ejemplo (sin simulación)

```bash
# create (az / terraform / raw ARM) → loginServer like localhost:5000/myregistry
docker tag busybox localhost:5000/myregistry/demo/busybox:v1
docker push localhost:5000/myregistry/demo/busybox:v1
curl http://localhost:5000/v2/_catalog     # {"repositories":["myregistry/demo/busybox", ...]}
docker pull localhost:5000/myregistry/demo/busybox:v1
```

## Configuración

```yaml
floci-az:
  services:
    acr:
      enabled: true
      mocked: false             # false (default) = one shared registry:2 for all registries. true = management plane only, no Docker
      default-image: "registry:2"
      base-port: 5000           # host port range start for registry containers
      max-port: 5099            # host port range end
```

| Variable de entorno | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_ACR_ENABLED` | `true` | Habilitar o deshabilitar el servicio |
| `FLOCI_AZ_SERVICES_ACR_MOCKED` | `false` | Modo simulado (solo plano de administración, sin Docker) |
| `FLOCI_AZ_SERVICES_ACR_DEFAULT_IMAGE` | `registry:2` | Imagen del contenedor del registro |
| `FLOCI_AZ_SERVICES_ACR_BASE_PORT` | `5000` | Inicio del rango de puertos host |
| `FLOCI_AZ_SERVICES_ACR_MAX_PORT` | `5099` | Fin del rango de puertos host |

## Fuera del alcance (trabajo futuro)

- Autenticación de tokens de AAD (`/oauth2/token`) y el flujo de tokens de `az acr login`: utilice el usuario administrador + `docker login`.
- Copia real de capas de `importImage` (se acepta como una operación sin efecto con `202`).
- Geo-replicación, webhooks, ACR Tasks, private link, content trust y políticas de retención/cuarentena
  (se aceptan y se devuelven como propiedades estáticas, sin aplicarse).
- Diferencias de comportamiento entre SKU (se aceptan Basic/Standard/Premium; no hay diferencia funcional).