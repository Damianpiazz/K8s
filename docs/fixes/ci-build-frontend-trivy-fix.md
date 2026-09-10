# Fix de CI: Build Frontend + Trivy Filesystem Scan

**Fecha:** 2026-09-10
**Run:** [GitHub Actions #34438707191](https://github.com/Damianpiazz/K8s/actions/runs/34438707191)
**Commit:** `7894d6c`

---

## Resumen

Dos jobs de CI fallaron en el último run: **Build frontend** y **Trivy filesystem scan**. Ambos problemas estaban en el servicio `services/frontend/`. Causas raíz identificadas y corregidas.

---

## Error 1: Build Frontend

### Síntoma

```
ERROR: failed to build: failed to solve: process "/bin/sh -c addgroup -S appgroup && adduser -S appuser -u 1000" did not complete successfully: exit code: 1
adduser: uid '1000' in use
```

### Causa raíz

El Dockerfile (`services/frontend/Dockerfile`, línea 29) creaba un usuario custom `appuser` con UID 1000:

```dockerfile
RUN addgroup -S appgroup && adduser -S appuser -u 1000
```

La imagen base `node:22-alpine` ya viene con un usuario `node` que ocupa el UID 1000. El `adduser` de Alpine se niega a crear un segundo usuario con el mismo UID, causando el fallo del build.

### Fix

Se removió la creación del usuario custom por completo. El stage de runtime ahora usa el usuario `node` built-in (UID 1000) que ya existe en `node:22-alpine`:

```dockerfile
# Antes
RUN addgroup -S appgroup && adduser -S appuser -u 1000
WORKDIR /app
COPY --from=build /workspace/.next/standalone ./
COPY --from=build /workspace/.next/static ./.next/static
COPY --from=build /workspace/public ./public
USER appuser

# Después
WORKDIR /app
COPY --from=build /workspace/.next/standalone ./
COPY --from=build /workspace/.next/static ./.next/static
COPY --from=build /workspace/public ./public
USER node
```

**Por qué es seguro:** el usuario `node` en `node:22-alpine` tiene UID 1000, la misma convención de home directory, y es el usuario no-root oficialmente soportado para contenedores Node.js. Usarlo evita el conflicto de UID y sigue las buenas prácticas de Alpine/Node.js.

---

## Error 2: Trivy Filesystem Scan

### Síntoma

Trivy encontró **17 vulnerabilidades HIGH** (0 CRITICAL) en `services/frontend/pnpm-lock.yaml`, todas con fixes disponibles. El scan sale con código 1 por el flag `--exit-code 1 --severity CRITICAL,HIGH`.

### Paquetes vulnerables

| Paquete | CVE(s) | Instalado | Fix | Tipo |
|---|---|---|---|---|
| brace-expansion | CVE-2026-13149, CVE-2026-14257, CVE-2026-69152 | 5.0.6 | >=5.0.9 | DoS |
| browserslist | CVE-2026-73088, CVE-2026-73089 | 4.28.1 | >=4.28.7 | Prototype pollution / DoS |
| fast-uri | CVE-2026-13676, CVE-2026-16221, CVE-2026-18446, CVE-2026-75899, CVE-2026-75975, CVE-2026-76172 | 3.1.2 | >=3.1.6 | SSRF / policy bypass |
| ip-address | CVE-2026-69192 | 10.2.0 | >=10.3.1 | SSRF |
| js-yaml | CVE-2026-59869, CVE-2026-84375, GHSA-5p4m-2wfm-xmqj | 4.2.0 | >=4.3.2 | DoS |
| nanoid | CVE-2026-67213 | 3.3.16 | >=3.3.18 | DoS |
| sharp | GHSA-rgj7-g3m4-5g8c | 0.35.3 | >=0.35.4 | vuln de libheif |

### Fix

Se corrió `pnpm update` para resolver todas las dependencias transitivas vulnerables a sus versiones parcheadas:

```bash
pnpm update brace-expansion browserslist fast-uri ip-address js-yaml nanoid sharp
```

**Versiones resueltas después del fix:**

| Paquete | Antes | Después |
|---|---|---|
| brace-expansion | 5.0.6 | 5.0.9 |
| browserslist | 4.28.1 | 4.28.9 |
| fast-uri | 3.1.2 | 3.1.7 |
| ip-address | 10.2.0 | 10.7.0 |
| js-yaml | 4.2.0 | 4.3.2 |
| nanoid | 3.3.16 | 3.3.18 |
| sharp | 0.35.3 | 0.35.4 |

Todas son dependencias transitivas (ninguna es directa en `package.json`), así que el update es seguro y backward-compatible.

---

## Archivos cambiados

| Archivo | Cambio |
|---|---|
| `services/frontend/Dockerfile` | Removida la creación de usuario custom, se usa el usuario `node` built-in |
| `services/frontend/pnpm-lock.yaml` | Actualizadas 7 dependencias transitivas vulnerables |

---

## Verificación

- El Dockerfile construye exitosamente con el usuario `node` (sin conflicto de UID)
- `pnpm-lock.yaml` pasa la verificación de política supply-chain
- Los 17 CVEs previamente marcados se resuelven a versiones parcheadas
- Sin cambios de dependencias directas en `package.json`