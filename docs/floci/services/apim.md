# API Management

floci-az incluye un **emulador de API Management integrado en el proceso**, pensado para el desarrollo local,
las pruebas de compatibilidad de SDK y los flujos de CI que necesitan recursos ARM con forma de APIM además de una
puerta de enlace ligera. **No** es una implementación completa de la puerta de enlace de Azure APIM: no se inicia
ningún sidecar; todo se ejecuta dentro del proceso.

## Endpoints

| Plano | Ruta |
|---|---|
| Administración (ARM) | `Microsoft.ApiManagement/service/...` |
| Puerta de enlace | `/{account}-apim/{serviceName}/{apiPath...}` — p. ej., `http://localhost:4577/devstoreaccount1-apim/{serviceName}/...` |

## Funciones compatibles

- Recursos de **servicio de administración**: crear, obtener, listar, eliminar.
- Recursos de **API**: crear, obtener, listar, eliminar.
- Recursos de **operación**: crear, obtener, listar, eliminar.
- Recursos de **política** a nivel de servicio, API y operación.
- Recursos de **producto** y vínculos de producto a API.
- Recursos de **suscripción** con aplicación de la clave de suscripción de la puerta de enlace.
- **Valores con nombre**, incluidos los valores con nombre secretos cuyo `properties.value` no se expone en las respuestas ARM.
- **Backends** y `<set-backend-service backend-id="...">`.
- **Importación de JSON OpenAPI** para las APIs (generación de operaciones a partir de `paths`); la reimportación reemplaza las operaciones generadas anteriormente.
- **Enrutamiento de la puerta de enlace** para rutas de API y plantillas de URL de operaciones, con proxy hacia el backend cuando se configura un `serviceUrl` de API o una política de backend.

### Subconjunto de políticas compatible

El motor de políticas admite de forma deliberada un subconjunto acotado:

- `<set-header>` con `exists-action="override" | skip | append | delete`
- `<set-query-parameter>` con `exists-action="override" | skip | delete`
- `<rewrite-uri template="...">`
- `<set-backend-service base-url="...">` y `<set-backend-service backend-id="...">`
- `<return-response>` con `<set-status code="...">` y `<set-body>` anidados
- Interpolación de valores con nombre mediante `{{name}}` en los valores de políticas compatibles

## Configuración

```yaml
floci-az:
  services:
    apim:
      enabled: true
```

| Variable | Valor por defecto | Descripción |
|---|---|---|
| `FLOCI_AZ_SERVICES_APIM_ENABLED` | `true` | Habilitar o deshabilitar el servicio |

## Fuera del alcance

- El lenguaje de políticas completo de Azure (solo se interpreta el subconjunto anterior)
- Portal para desarrolladores, aprobaciones del flujo de trabajo de productos y análisis (analytics)
- Puertas de enlace autohospedadas, despliegue en varias regiones y dominios personalizados
- Políticas de limitación de velocidad/cuota, validación de JWT y almacenamiento en caché