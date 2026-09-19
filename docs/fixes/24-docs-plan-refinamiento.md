# Fix 24 — Purgar planes viejos y reemplazarlos por docs/PLAN.md (canónico)

## Fecha
2026-09-18

## Error / contexto observado
El working tree tenía **borrados sin commitear** (pero aún trackeados en git)
cuatro documentos de plan:
- `docs/plan-ecommerce-k8s.md`
- `docs/floci/PLAN.md`
- `docs/floci/plan-implementacion.md`
- `docs/floci/requirements.txt`

Además, **13 referencias** a esos archivos seguían vivas en READMEs,
kustomizations y comentarios — al hacer `git rm`/commit quedarían links rotos.

## Causa raíz
Los planes anteriores quedaron obsoletos por el nuevo plan de refinamiento de
paridad floci ↔ Azure (monitoreo en local, UI ArgoCD/Grafana por ingress,
desacoplamiento de entorno). El usuario decidió (2026-09-18): **borrar y
repuntar** — el plan nuevo pasa a ser la fuente de verdad.

## Fix aplicado
1. Creado `docs/PLAN.md` — plan canónico con fases 0-6, decisiones tomadas y
   reglas de trabajo (sin commits/PRs sin permiso, fixes indexados).
2. Repunteadas las 13 referencias (10 archivos):
   - `README.md` (×2) → `docs/PLAN.md`
   - `docs/README.md` (×1) → `PLAN.md`
   - `security/README.md` (×1) → `docs/PLAN.md`
   - `security/network-policies/default-deny-all.yaml` (×1) → `docs/PLAN.md`
   - `cluster/overlays/README.md` (×1) → `docs/PLAN.md`
   - `cluster/overlays/local/kustomization.yaml` (×2) → `docs/PLAN.md`
   - `cluster/base/README.md` (×1) → `docs/PLAN.md`
   - `docs/floci/README.md` (×1) → `../PLAN.md`
   - `cluster/base/monitoring/kube-prometheus-stack/values.yaml` (×1) → `docs/PLAN.md`
   - `infra/floci/docker-compose.yml` (×2) → `docs/PLAN.md`
3. Los 4 archivos viejos quedan eliminados del tree (decisión del usuario;
   siguen recuperables en git si hiciera falta).

## Archivos afectados
- `docs/PLAN.md` (nuevo)
- los 10 archivos con referencias repunteadas (ver lista arriba)

## Cómo verificar
```powershell
# Sin referencias muertas:
rg -n "plan-ecommerce-k8s|floci/PLAN|plan-implementacion|floci/requirements" .
# git status debe mostrar los 4 borrados + docs/PLAN.md sin commitear