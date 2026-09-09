# Corrección de la ejecución de CI del 2026-09-08 (commit 9dc1b82)

Esta carpeta documenta el análisis y la corrección de la ejecución de CI del
2026-09-08 sobre el commit `9dc1b82` (HEAD): **34 de 38 jobs fallaron**, todos
con origen en **7 causas raíz distintas**. Los 4 jobs que pasaron fueron
`Test config-service`, `Test discovery-service`, `Test auth` (sin pom.xml, sus
pasos Maven quedaron omitidos) y `Secret scanning`.

Cada documento de esta carpeta es autocontenido: describe el error observado
(con la línea exacta extraída de `logs_github/*.txt`), la causa raíz, el fix
aplicado, los archivos afectados y cómo verificar la corrección.

## Resumen

| # | Jobs afectados | Error observado | Causa raíz | Fix | Archivo(s) del fix |
|---|----------------|-----------------|------------|-----|--------------------|
| 1 | 17 jobs `Build <service>` | `invalid tag ".azurecr.io/<service>:<sha>": invalid reference format` | La variable de repositorio `ACR_NAME` no está configurada en GitHub; el tag se renderizó con prefijo vacío (`.azurecr.io/...`) | Tag de validación independiente del registro (`<service>:ci-<sha>`) + input `acr_name` explícito en el workflow reutilizable con guard | [01-build-acr.md](01-build-acr.md) |
| 2 | 10 jobs `Test <service>` (search, catalog, returns, cart, notification, checkout, recommendation, shipping, order, payment) | `APPLICATION FAILED TO START ... No spring.config.import property has been defined` | `src/test/resources/application.yml` sombrea al `application.yml` de main en el classpath de test; el `spring.config.import` nunca llega al entorno de test | Deshabilitar el import-check en la config **solo de test** (`spring.cloud.config.import-check.enabled: false`) | [02-config-import.md](02-config-import.md) |
| 3 | 2 jobs `Test` (analytics-svc, inventory-svc) | `Non-parseable POM ... entity reference names can not start with character ' '` | `&` sin escapar en `<description>` (línea 18) de ambos pom.xml | Reemplazar `&` por `&amp;` | [03-pom-xml.md](03-pom-xml.md) |
| 4 | 1 job `Test api-gateway` | `RequestLoggingFilter.java:[35,55] incompatible types: HttpStatusCode cannot be converted to HttpStatus` | Spring Framework 6 / Boot 3: `ServerHttpResponse.getStatusCode()` devuelve `HttpStatusCode` | Declarar la variable como `HttpStatusCode` y ajustar el import | [04-api-gateway-httpstatuscode.md](04-api-gateway-httpstatuscode.md) |
| 5 | 1 job `Test bff-web` | `HomeResponse.java:[15,32] invalid accessor method in record` | El accessor autogenerado `degraded()` del record colisiona con el factory estático `HomeResponse.degraded()` | Renombrar el factory a `degradedResponse()` y actualizar el único call site | [05-bff-web-record-accessor.md](05-bff-web-record-accessor.md) |
| 6 | 1 job `Lint manifests` | `accumulating resources from '../../../services/auth/k8s/base' must resolve to a file` | `admin-credentials.yaml` está ignorado por `.gitignore` (`**/admin-credentials.yaml`) y no existe en el checkout de CI; es un placeholder seguro sin secretos reales | Dejar de ignorar el placeholder y trackearlo (el comentario del kustomization se actualiza) | [06-kustomize-auth-placeholder.md](06-kustomize-auth-placeholder.md) |
| 7 | 2 jobs (`Trivy filesystem scan`, `Generate SBOM`) | `Unable to resolve action aquasecurity/trivy-action@0.24.0` | El tag real de la action es `v0.24.0` (con `v`); `@0.24.0` no existe | Usar `@v0.24.0` en las 4 referencias | [07-trivy-action.md](07-trivy-action.md) |

El documento [08-notas.md](08-notas.md) recoge todo lo que **no** es un error:
jobs que pasaron, advertencias informativas (deprecación de Node.js 20) y los
contratos pendientes para el CD (`ACR_NAME` y prefijo `acr.azurecr.io`).

## Cómo se validó esta corrección

Verificación local (sin red, sin Maven/Java):

1. **YAML**: todos los workflows y los 10 `application.yml` de test se parsean
   con `yaml.safe_load` (`YAML OK`).
2. **XML**: ambos pom.xml se parsean con `xml.etree.ElementTree` (`XML OK`).
3. **Kustomize**: `kubectl kustomize cluster/overlays/{dev,staging,prod}`
   renderiza sin errores del tipo `must resolve to a file`; además la suite
   `python tests/manifests/test-kustomize-build.py` pasa íntegra
   (`KUSTOMIZE BUILD CHECK PASSED`).
4. **Grep de contratos**: `trivy-action@` solo aparece como `@v0.24.0`;
   `ACR_NAME` ya no aparece en `ci.yml`; `reusable-build.yml` expone el input
   `acr_name` con guard; `cd.yml` lo pasa explícitamente.
5. **Java**: los dos fixes (api-gateway y bff-web) se validaron por lectura
   cuidadosa de tipos, imports y call sites.

> **Nota sobre Maven/Java**: este entorno local no tiene instalados Maven ni
> Java (no hay `mvn`, `java` ni wrappers `mvnw`). Los tests de Spring que
> dependen de compilación se validarán en la **próxima ejecución de CI**, que
> es la verificación final de los fixes 2 a 5.

## Siguiente paso

Revisar el diff completo (docs + código), hacer commit y push para disparar la
nueva ejecución de CI y confirmar que los 38 jobs pasan.