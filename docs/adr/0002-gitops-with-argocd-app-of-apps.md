# ADR-0002: GitOps con Argo CD — patrón app-of-apps

- **Estado**: aceptado
- **Fecha**: 2026-09-07
- **Decisores**: equipo de plataforma

## Contexto

El repo debe ser la única fuente de verdad para la plataforma y los 17
servicios. El CI/CD ya estaba construido en la Fase 1 con una variable
`GITOPS` que selecciona entre un sync de Argo CD (`GITOPS == 'argocd'`) y un
fallback directo con `kubectl apply` — la elección del controlador GitOps
tenía que coincidir con ese pipeline y poder demostrarse en una clase
magistral.

## Decisión

- **Argo CD** como controlador GitOps, con el patrón **app-of-apps**:
  `cluster/base/argocd/app-of-apps.yaml` (Application raíz `ecommerce-apps`)
  reconcilia los 7 Application hijos bajo
  `cluster/base/argocd/applications/` (ingress-nginx, cert-manager,
  external-dns, external-secrets, kyverno, keda, kube-prometheus-stack).
- Cada Application ejecuta `automated: { prune: true, selfHeal: true }` — las
  diferencias se corrigen automáticamente, las eliminaciones en Git se propagan.
- El orden de bootstrap es explícito y está documentado en
  `cluster/base/README.md` (instalar Argo CD → aplicar las Applications →
  aplicar la kustomization restante a nivel de clúster).
- El pipeline de CD (`cd.yml`) sincroniza `ecommerce-<env>` después de
  pushear las imágenes, manteniendo a Argo CD como agente de despliegue.

## Consecuencias

- Git es la única fuente de verdad: rollback = revertir un commit (ver
  `docs/runbooks/scaling-and-recovery.md`).
- La UI le da al profesor un loop de reconciliación visible para la demo
  (`bootstrap-argocd.ps1` imprime la contraseña inicial de admin +
  port-forward).
- App-of-apps agrega una preocupación de orden de bootstrap (los charts
  deben instalar sus CRDs antes de que los recursos a nivel de clúster los
  referencien) — documentado en `cluster/base/README.md` con el síntoma
  `no matches for kind: ClusterPolicy`.
- Argo CD no se gestiona a sí mismo (se instala una vez en el bootstrap) —
  aceptado.

## Alternativas consideradas

- **Flux CD**: igualmente correcto en GitOps, pero el pipeline de la Fase 1
  ya estaba construido alrededor de Argo CD (`argoproj/argo-cd-action`), y el
  repo de referencia usa Argo CD — la consistencia gana.
- **Solo kubectl apply (sin controlador)**: más simple pero pierde la
  corrección de drift y la demo de la UI; se mantiene solo como fallback
  `GITOPS != 'argocd'` en `cd.yml` y como `scripts/deploy/apply-overlay.ps1`.
- **helmfile**: documentado en `cluster/base/helmfile.yaml` solo como ruta de
  comparación no-GitOps — deliberadamente no es el flujo principal (mezclar
  ambos para el mismo componente genera conflictos, p. ej. external-dns).
