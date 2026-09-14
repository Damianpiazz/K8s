# Azure Functions

Floci-AZ emula Azure Functions generando contenedores Docker reales del runtime de Azure
Functions bajo demanda y enviando por proxy las invocaciones HTTP hacia ellos.

## Requisitos

- Demonio de Docker alcanzable en `/var/run/docker.sock` (montado por bind en el contenedor de
  floci-az)
- Acceso a internet en el primer uso para descargar las imágenes del runtime

## Runtimes compatibles

| Runtime | Imagen |
|---|---|
| `node` | `mcr.microsoft.com/azure-functions/node:4` |
| `python` | `mcr.microsoft.com/azure-functions/python:4` |
| `java` | `mcr.microsoft.com/azure-functions/java:4` |
| `dotnet` | `mcr.microsoft.com/azure-functions/dotnet-isolated:4` |

## Endpoint

```
http://localhost:4577/{accountName}-functions
```

Cuenta por defecto: `devstoreaccount1`

---

## API de administración

### Apps

| Método | Ruta | Descripción |
|---|---|---|
| `PUT` | `/admin/apps/{appName}` | Crea o actualiza una function app |
| `GET` | `/admin/apps/{appName}` | Obtiene una function app |
| `GET` | `/admin/apps` | Lista todas las function apps |
| `DELETE` | `/admin/apps/{appName}` | Elimina una function app y todas sus funciones |

**Cuerpo de la petición de creación de la app:**
```json
{
  "runtime": "python",
  "linuxFxVersion": "Python|3.12",
  "environment": {
    "MY_VAR": "hello"
  }
}
```

`linuxFxVersion` es opcional. Cuando se define, floci-az selecciona la imagen Linux de Azure
Functions correspondiente, como `mcr.microsoft.com/azure-functions/python:4-python3.12`.
Las rutas compatibles con ARM `Microsoft.Web/sites` y `Microsoft.Web/sites/{name}/config/web`
aceptan la misma configuración bajo `properties.siteConfig.linuxFxVersion` y
`properties.linuxFxVersion`, respectivamente.

### Functions

| Método | Ruta | Descripción |
|---|---|---|
| `PUT` | `/admin/apps/{appName}/functions/{funcName}` | Despliega una función (carga ZIP) |
| `GET` | `/admin/apps/{appName}/functions/{funcName}` | Obtiene los detalles de la función |
| `GET` | `/admin/apps/{appName}/functions` | Lista las funciones de una app |
| `DELETE` | `/admin/apps/{appName}/functions/{funcName}` | Elimina una función |

**Cuerpo de la petición de despliegue de la función:**
```json
{
  "handler": "index.handler",
  "timeoutSeconds": 60,
  "zipBase64": "<base64-encoded ZIP>"
}
```

Para el modelo Node.js y Python v1, el ZIP debe contener el código de la función en el layout de
Azure Functions v4:
```
host.json
{funcName}/function.json
{funcName}/index.js   (or handler file for the runtime)
```

También se admiten paquetes del modelo Python v2. Un ZIP de Python v2 se identifica por tener
`function_app.py` en su raíz y se extrae en la raíz de la aplicación:
```
host.json
function_app.py
.python_packages/lib/site-packages/   (optional)
```
Se preserva el `host.json` del paquete. Su `extensions.http.routePrefix` controla la ruta interna
del worker; si se omite, el valor por defecto es `/api`. La URL pública de invocación del emulador
sigue siendo `/api/{appName}/{funcName}`.

### Invocación

| Método | Ruta | Descripción |
|---|---|---|
| `GET` / `POST` | `/api/{appName}/{funcName}[?...]` | Invoca una función disparada por HTTP |

---

## Pool de contenedores en caliente

Por defecto, floci-az mantiene los contenedores de funciones en caliente tras el primer uso (pool
LIFO, uno por función). Los contenedores se descartan después de
`FLOCI_AZ_SERVICES_FUNCTIONS_CONTAINER_IDLE_TIMEOUT_SECONDS` segundos de inactividad (por defecto
300 s / 5 minutos).

Para deshabilitar la reutilización en caliente y obtener un contenedor nuevo por invocación:

```yaml
environment:
  FLOCI_AZ_SERVICES_FUNCTIONS_EPHEMERAL: "true"
```

---

## Modo simulado

Defina `mocked: true` (por defecto `false`) para ejecutar el servicio Functions sin Docker:

```yaml
environment:
  FLOCI_AZ_SERVICES_FUNCTIONS_MOCKED: "true"
```

En modo simulado, el plano de administración (crear app, desplegar/listar/obtener/eliminar
función) funciona por completo desde el estado y nunca se lanza un contenedor del runtime.
Como el código del usuario no puede ejecutarse sin el contenedor del runtime, las invocaciones
(`POST api/{app}/{func}`) devuelven un stub sintético con `200` en lugar de ejecutar la
función. Útil para pruebas y entornos de CI sin demonio de Docker.

| Variable de entorno | Por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_FUNCTIONS_MOCKED` | `false` | Modo simulado (solo plano de administración, sin Docker; las invocaciones devuelven un stub sintético 200) |

---

## DNS integrado (Docker-in-Docker)

Cuando floci-az se ejecuta dentro de Docker, inicia un servidor DNS UDP/53 integrado y lo inyecta
en cada contenedor de función generado. Esto permite que los contenedores de funciones resuelvan
hostnames personalizados (configurados mediante `floci-az.hostname` o
`floci-az.dns.extra-suffixes`) hacia la IP de la red Docker de floci-az — útil cuando las funciones
se conectan a Blob Storage usando un endpoint basado en hostname en lugar de una IP cruda.

En el host no se necesita configuración de DNS; el servidor integrado es un no-op.

---

## Docker nativo en Linux — nota sobre el firewall

En Docker nativo de Linux (no Docker Desktop), los contenedores de funciones alcanzan el host
mediante `host.docker.internal` (mapeado automáticamente a `host-gateway`). Si ejecuta UFW con la
política `INPUT DROP` por defecto, las invocaciones expirarán. Solución:

```bash
sudo ufw allow in on docker0
```

Esto no es necesario en Docker Desktop (macOS/Windows) ni cuando floci-az se ejecuta dentro de
Docker.