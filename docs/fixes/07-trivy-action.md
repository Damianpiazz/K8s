# Fix 07: `Unable to resolve action aquasecurity/trivy-action@0.24.0`

Dos jobs fallaron en el arranque (antes de ejecutar ningún paso): las
referencias a `aquasecurity/trivy-action@0.24.0` son inválidas porque el tag
real de esa action es `v0.24.0` (con `v`). La versión `0.24.0` sin `v` no
existe, así que GitHub Actions no puede resolver la action.

## Error observado

Líneas exactas en `logs_github/37_Trivy filesystem scan.txt` (línea 30) y
`logs_github/28_Generate SBOM.txt` (línea 30):

```text
2026-09-08T03:31:27.8842390Z ##[error]Unable to resolve action `aquasecurity/trivy-action@0.24.0`, unable to find version `0.24.0`
```

```text
2026-09-08T03:31:27.1760290Z ##[error]Unable to resolve action `aquasecurity/trivy-action@0.24.0`, unable to find version `0.24.0`
```

## Por qué ocurre

El repositorio `aquasecurity/trivy-action` publica sus versiones con prefijo
`v` (tags `v0.24.0`, `v0.25.0`, etc.). Escribir `@0.24.0` hace que el runner
busque un tag que no existe y falle con `Unable to resolve action` / `unable
to find version`. Es un error de tipeo en las 4 referencias (2 en `ci.yml`,
2 en `security.yml`).

## Fix aplicado

Se reemplazó `aquasecurity/trivy-action@0.24.0` → `aquasecurity/trivy-action@v0.24.0`
en las 4 ocurrencias:

- `.github/workflows/ci.yml` — job `trivy-scan` (paso `Run Trivy`) y job `sbom` (paso `Generate SBOM`)
- `.github/workflows/security.yml` — job `trivy-scan` (paso `Run Trivy`) y job `trivy-sbom` (paso `Generate SBOM`)

## Cómo verificar

1. `grep -rn "trivy-action@" .github/workflows/` debe mostrar **solo**
   `@v0.24.0` (4 coincidencias) y ninguna `@0.24.0`.
2. Re-ejecución de CI/Security: ambos jobs deben arrancar y completar el scan
   Trivy y la generación de SBOM. (Recordar que en PR los jobs son lenient
   por `continue-on-error`, pero en `push` a `main` son fail-closed.)

## Siguiente paso

Ver [README.md](README.md) para el contexto global.