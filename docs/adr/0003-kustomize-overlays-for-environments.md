# ADR-0003: Kustomize overlays para la capa de aplicación; Helm para charts de plataforma

- **Estado**: aceptado
- **Fecha**: 2026-09-07
- **Decisores**: equipo de plataforma

## Contexto

Dos capas de templating coexisten en la plataforma: los **componentes de
plataforma** (ingress-nginx, cert-manager, external-secrets, kyverno, keda,
kube-prometheus-stack) se instalan desde charts Helm upstream con muchos
valores; los **17 servicios** son manifiestos Kubernetes plain que solo
difieren por ambiente con unos pocos parches (réplicas, recursos, tag de
imagen). Usar Helm también para los servicios agregaría boilerplate de charts
a 17 repos sin aportar valor real.

## Decisión

- **Helm** está reservado para los charts de plataforma, instalados a través
  de Applications de Argo CD (`cluster/base/argocd/applications/*.yaml`
  apuntan a repositorios de charts) — la "capa de plataforma" de
  `cluster/base`.
- **Kustomize** es la herramienta para todo lo first-party:
  - `cluster/base` + `cluster/overlays/{dev,staging,prod}` para los recursos
    de plataforma (ClusterIssuer, ClusterSecretStore, env-config, namespaces);
  - `services/<svc>/k8s/base` + `services/<svc>/k8s/overlays/prod` para cada
    servicio (layout uniforme según `services/README.md`).
- Overlays en capas: `dev`/`staging` reusan el `k8s/base` de cada servicio,
  `prod` usa el overlay delgado `k8s/overlays/prod` (parche de réplicas +
  recursos).
- El pipeline de CD muta los overlays de ambiente reescribiendo
  `images[].newTag` via `yq` — un contrato declarativo nativo de kustomize.

## Consecuencias

- Layout uniforme de 17 servicios → los chequeos en
  `tests/manifests/test-service-contract.py` pueden forzar la norma para
  toda la flota desde un solo test.
- El `issuer-default.yaml` por overlay de ambiente permite cambiar entre Let's
  Encrypt staging y prod por ambiente sin parchear el base (documentado en
  `cluster/overlays/README.md`).
- YAML plain significa que el repo es revisable sin conocimiento de
  templating de charts; las llaves nunca aparecen en paths de `resources:`.
- Costo: los overlays por servicio solo existen para prod; dev/staging
  comparten base — una asimetría de tres ambientes deliberada, verificada por
  `test-overlay-wiring.py`.

## Alternativas consideradas

- **Helm también para servicios**: tooling uniforme, pero 17 árboles de
  chart.yaml + values.yaml para mantener y sin beneficio para el delta por
  ambiente tan pequeño; el rewrite de `newTag` del CD también necesitaría
  semántica de `helm upgrade` por servicio.
- **Helmfile para todo**: fuente de drift no-GitOps; documentado solo como
  ruta de comparación en `cluster/base/helmfile.yaml`.
- **kubectl apply directo, sin templating**: duplicaría las diferencias por
  ambiente 3× en 17 servicios — descartado.
