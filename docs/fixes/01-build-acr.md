# Fix 01: Tag de imagen inválido en los 17 jobs de build (ACR_NAME sin configurar)

Los 17 jobs `Build <service>` de `ci.yml` fallaron en la corrida del 2026-09-08
porque el tag de imagen se renderizó como `.azurecr.io/<service>:<sha>`, un
formato de referencia inválido. La causa raíz es que la variable de repositorio
de GitHub `ACR_NAME` no está configurada, por lo que el tag quedó con el
prefijo del registro vacío (lleva un punto inicial, que Docker rechaza).

## Error observado

Línea exacta en `logs_github/21_Build notification-svc.txt` (línea 550):

```text
2026-09-08T03:31:37.2794739Z ERROR: failed to build: invalid tag ".azurecr.io/notification-svc:9dc1b82b891ca4eca6cf2079a349bcb300397df3": invalid reference format
2026-09-08T03:31:37.3980003Z ##[error]buildx failed with: ERROR: failed to build: invalid tag ".azurecr.io/notification-svc:9dc1b82b891ca4eca6cf2079a349bcb300397df3": invalid reference format
```

El mismo error aparece en los otros 16 logs de build (`1_Build`,
`2_Build`, `3_Build`, `8_Build`, `10_Build`, `12_Build`, `14_Build`,
`17_Build`, `18_Build`, `21_Build`, `22_Build`, `24_Build`, `25_Build`,
`26_Build`, `27_Build`, `34_Build`, `35_Build`), siempre con el tag
`.azurecr.io/<servicio>:9dc1b82...`.

## Por qué ocurre

1. `ci.yml` define a nivel de workflow: `env: ACR_NAME: ${{ vars.ACR_NAME }}`.
2. `vars.ACR_NAME` **no existe** como variable de repositorio en GitHub
   Actions para este repo, así que la expresión se resuelve a cadena vacía.
3. El paso `Build image (no push)` construye el tag así:
   `tags: ${{ env.ACR_NAME }}.azurecr.io/${{ matrix.service }}:${{ github.sha }}`.
   Con `ACR_NAME=""` el tag queda `.azurecr.io/<servicio>:<sha>`.
4. Docker valida el nombre de referencia: un nombre de registro no puede
   empezar con `.` (dominio incompleto) → `invalid reference format`.

Los builds de validación de CI no necesitan un registro real: solo comprueban
que el Dockerfile construye. El registro solo importa en el pipeline de CD.

## Fix aplicado

| Cambio | Archivo | Detalle |
|--------|---------|---------|
| Quitar `env: ACR_NAME` | `.github/workflows/ci.yml` | El bloque env de nivel workflow ya no se usa en ningún otro paso de `ci.yml`. |
| Tag independiente del registro | `.github/workflows/ci.yml` | `tags: ${{ matrix.service }}:ci-${{ github.sha }}` — tag local válido sin prefijo de registro. |
| Nuevo input `acr_name` | `.github/workflows/reusable-build.yml` | Input opcional `acr_name` (default `""`) bajo `on.workflow_call.inputs`. |
| Env con fallback a vars | `.github/workflows/reusable-build.yml` | `ACR_NAME: ${{ inputs.acr_name \|\| vars.ACR_NAME }}` — el input explícito tiene precedencia; `vars` como respaldo. |
| Guard de validación | `.github/workflows/reusable-build.yml` | Primer paso del job `build` (inmediatamente después de Checkout): si `ACR_NAME` está vacío, falla con un mensaje claro. |
| Pasar `acr_name` desde CD | `.github/workflows/cd.yml` | En el `with:` del call a `reusable-build.yml`: `acr_name: ${{ vars.ACR_NAME }}` (los keys `service`/`context`/`push` se mantienen). |

El paso `Compute image metadata` y `docker/login-action` de
`reusable-build.yml` ya usaban `${{ env.ACR_NAME }}` y siguen igual: ahora el
guard garantiza que ese valor no sea vacío.

## Cómo verificar

1. `grep -rn "ACR_NAME" .github/workflows/`:
   - `ci.yml` → **sin** coincidencias (env y tag ya no lo usan).
   - `reusable-build.yml` → input `acr_name`, env con `inputs.acr_name || vars.ACR_NAME` y el guard.
   - `cd.yml` → `acr_name: ${{ vars.ACR_NAME }}` en el `with:` del job `build-images`.
2. Re-ejecutar CI: los 17 jobs `Build <service>` deben pasar (el tag
   `ci-<sha>` es un tag local válido, sin push).
3. Para el CD: definir la variable de repositorio `ACR_NAME` (p. ej. `myacr`)
   o pasar el input `acr_name`; sin eso, el guard falla con:
   `ACR_NAME is empty. Set the ACR_NAME repository variable or pass the acr_name input (e.g. 'myacr').`

## Siguiente paso

Documento relacionado: [08-notas.md](08-notas.md) describe el contrato de
`ACR_NAME` y el prefijo `acr.azurecr.io` esperado por los overlays de
Kustomize.