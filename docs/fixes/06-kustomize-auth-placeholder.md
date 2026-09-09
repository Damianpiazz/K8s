# Fix 06: Kustomize no resuelve `admin-credentials.yaml` en el job de lint

El job `Lint manifests` falló porque `kubectl kustomize build` no pudo
resolver `services/auth/k8s/base/admin-credentials.yaml`: el archivo está
ignorado por `.gitignore` (`**/admin-credentials.yaml`) y, por lo tanto, no
existe en el checkout de CI. Es un placeholder sano (`admin` /
`change-me-admin-password`, sin secretos reales): las credenciales reales de
producción vienen de Azure Key Vault vía `external-secret.yaml`.

## Error observado

Línea exacta en `logs_github/20_Lint manifests.txt` (línea 146):

```text
2026-09-08T03:31:30.3196293Z Error: accumulating resources: accumulation err='accumulating resources from '../../../services/auth/k8s/base': '/home/runner/work/K8s/K8s/services/auth/k8s/base' must resolve to a file': recursed accumulation of path '/home/runner/work/K8s/K8s/services/auth/k8s/base': accumulating resources: accumulation err='accumulating resources from 'admin-credentials.yaml': evalsymlink failure on '/home/runner/work/K8s/K8s/services/auth/k8s/base/admin-credentials.yaml' : lstat /home/runner/work/K8s/K8s/services/auth/k8s/base/admin-credentials.yaml: no such file or directory': must build at directory: not a valid directory: evalsymlink failure on '/home/runner/work/K8s/K8s/services/auth/k8s/base/admin-credentials.yaml' : lstat /home/runner/work/K8s/K8s/services/auth/k8s/base/admin-credentials.yaml: no such file or directory
```

## Por qué ocurre

1. `services/auth/k8s/base/kustomization.yaml` lista
   `admin-credentials.yaml` entre sus `resources`.
2. El archivo sí existe en el repo local, pero `.gitignore` contiene
   `**/admin-credentials.yaml` (con su bloque de comentario), así que **nunca
   se commitea** y no llega al checkout de GitHub Actions.
3. Kustomize resuelve cada resource contra el filesystem; el archivo no existe
   → `must resolve to a file` (la ruta intermedia `base/` "no es un directorio
   válido" porque el resource referenciado no está).
4. El archivo ignorado es un placeholder deliberado
   (`services/auth/k8s/base/admin-credentials.yaml`:
   `username: admin`, `password: change-me-admin-password`), con el comentario
   `⚠️ PLACEHOLDER ONLY — never commit real credentials`. No hay riesgo de
   filtrar secretos al trackearlo. Las credenciales reales de producción se
   inyectan con `external-secret.yaml` (Azure Key Vault).

## Fix aplicado

| Cambio | Archivo | Detalle |
|--------|---------|---------|
| Dejar de ignorar el placeholder | `.gitignore` | Se eliminaron las líneas 64-66: el comentario `# Placeholder credential manifests (...)` y la entrada `**/admin-credentials.yaml`. Se conservan `**/secret.yaml` y `**/secrets.yaml`. |
| Comentario actualizado | `services/auth/k8s/base/kustomization.yaml` | El comentario inline de la línea 15 deja de afirmar que el archivo está gitignoreado; ahora dice: `# placeholder Secret for local/dev — real source: Azure Key Vault via external-secret.yaml`. |
| Contenido del placeholder | `services/auth/k8s/base/admin-credentials.yaml` | Sin cambios (solo `admin` / `change-me-admin-password`, ya existente). |

Tras quitar la entrada del `.gitignore`, el archivo aparece como untracked en
`git status`; es el comportamiento esperado (se trackeará en el próximo
commit).

## Cómo verificar

1. `kubectl kustomize cluster/overlays/dev` (y `staging`, `prod`) debe
   renderizar sin errores de `must resolve to a file`.
2. `python tests/manifests/test-kustomize-build.py` debe pasar con
   `KUSTOMIZE BUILD CHECK PASSED` (itera todos los kustomization del repo,
   incluido `services/auth/k8s/base`).
3. `git status` muestra `services/auth/k8s/base/admin-credentials.yaml` como
   untracked (ya no ignorado).

## Siguiente paso

Ver [README.md](README.md) para el contexto global.