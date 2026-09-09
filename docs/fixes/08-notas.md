# Fix 08: Notas — todo lo que NO fue un error en la corrida del 2026-09-08

Este documento recoge los hallazgos que no son errores, para que la próxima
revisión no los confunda con fallos nuevos. Complementa los fixes 01 a 07.

## Jobs que pasaron

| Job | Log | Evidencia |
|-----|-----|-----------|
| `Test config-service` | `11_Test config-service.txt` | `[INFO] BUILD SUCCESS` |
| `Test discovery-service` | `7_Test discovery-service.txt` | `[INFO] BUILD SUCCESS` |
| `Test auth` | `31_Test auth.txt` | Sin errores; `auth` no tiene `pom.xml`, así que los pasos Maven quedaron omitidos por el guard `hashFiles` (el job pasa de forma vacía) |
| `Secret scanning` | `29_Secret scanning.txt` | Gitleaks 8.24.3 corrió sin reportar leaks (`no ##[error]`) |

## Advertencias informativas (no bloquean)

**Deprecación de Node.js 20.** GitHub Actions está migrando a Node.js 24 como
runtime por defecto y emite avisos para las actions que aún apuntan a Node 20.
En los logs aparecen estas 4 actions:

- `actions/checkout@v4`
- `azure/setup-helm@v4`
- `gitleaks/gitleaks-action@v2`
- `imranismail/setup-kustomize@v2`

Ejemplo (`logs_github/20_Lint manifests.txt`, línea 164):

```text
##[warning]Node.js 20 is deprecated. The following actions target Node.js 20 but are being forced to run on Node.js 24: actions/checkout@v4, azure/setup-helm@v4, imranismail/setup-kustomize@v2.
```

También se observa `##[warning]setup-java v4 is deprecated ... migrate to
actions/setup-java@v5` en varios logs de test. **Nada de esto es un error**
hoy: las actions siguen funcionando forzadas a Node 24. Planificar la
migración a las versiones v5/v3 correspondientes es deuda técnica
informativa, no parte de este fix.

## Contrato de `ACR_NAME` para el CD

Tras el fix 01, el pipeline de CD (y el workflow reutilizable) requieren que
el nombre del registro ACR esté disponible; sin él, el guard falla a
propósito con un mensaje claro:

1. **Variable de repositorio de GitHub**: crear `ACR_NAME` (Settings →
   Secrets and variables → Actions → Variables), p. ej. `ACR_NAME=myacr`.
2. El CD lo usa para: nombre del registry en `docker/login-action`, imagen
   `Compute image metadata` (`${ACR_NAME}.azurecr.io/<svc>:<sha>`) y el bump
   de tags en `set-env` (`IMAGE="${ACR_NAME}.azurecr.io/${svc}"`).
3. Alternativa para llamadas manuales al workflow reutilizable: pasar el
   input `acr_name` directamente (tiene precedencia sobre `vars.ACR_NAME`).

## Prefijo esperado por los overlays de Kustomize

Los `cluster/overlays/*/kustomization.yaml` (dev, staging, prod) declaran las
imágenes con el prefijo `acr.azurecr.io`, p. ej.:

```yaml
images:
  - name: acr.azurecr.io/auth
```

Ese prefijo coincide exactamente con `${ACR_NAME}.azurecr.io` cuando
`ACR_NAME=acr`. Es decir: el valor natural para la variable de repositorio es
`acr` (o el nombre real del registro si los overlays se actualizan en
consecuencia). El job `set-env` del CD hace el bumpeo de `newTag` sobre esas
entradas `newName`, por lo que el prefijo debe coincidir para que el patch
aplique.

## Validación local disponible

Los fixes 01, 03, 06 y 07 se verifican localmente (YAML/XML/Kustomize/grep).
Los fixes 02, 04 y 05 requieren Maven/Java (compilación y tests de Spring) y
se verifican en la próxima ejecución de CI; ver
[README.md](README.md#cómo-se-validó-esta-corrección).