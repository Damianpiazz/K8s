# Fix 23: App-of-Apps de Argo CD — `repoURL` real en los Application y estado pendiente de kube-prometheus-stack

El bootstrap de Argo CD (fix 22) quedó instalado y sano, pero los manifests de
Application del repo declaraban el placeholder
`repoURL: https://github.com/<org>/<repo>.git` tanto en el root
(`cluster/base/argocd/app-of-apps.yaml`) como en los children de
`cluster/base/argocd/applications/`. Al aplicar el root, Argo CD creó los
children desde el contenido de `origin/main` (que aún tenía los placeholders),
por lo que `kube-prometheus-stack` no puede comparar ni sincronizar.

## Error observado

Tras aplicar el root `ecommerce-apps`, el Application `kube-prometheus-stack`
queda en `sync=Unknown` con la condición:

```
ComparisonError: Failed to load target state: failed to generate manifest for
source 1 of 2: rpc error: code = Unknown desc = failed to get git client for
repo https://github.com/<org>/<repo>.git
```

El mismo origen afecta a los otros Application con source git:
`ecommerce-dev`, `ecommerce-prod`, `ecommerce-staging` (`repository not found`),
`external-dns` e `ingress-nginx`. Los Application solo-chart (cert-manager,
external-secrets) quedaron `Synced/Healthy`. El root `ecommerce-apps` quedó
`Synced/Healthy` en la revisión `0e713fa` (HEAD de `origin/main`).

## Por qué ocurre

- El app-of-apps declara `source.path: cluster/base/argocd/applications` con
  `targetRevision: main`: el controller renderiza los children **desde el
  contenido de la rama main remota**, no desde el working tree local.
- Los fixes de R2 (relleno de `repoURL`) estaban solo en el working tree
  (cambios sin commit; el push queda fuera del alcance de R2), así que
  `origin/main` seguía conteniendo los placeholders.
- Con el placeholder, el repo-server no puede obtener un git client para esa
  URL y la generación de manifests falla antes de cualquier sync. El source
  Helm (`prometheus-community`) es válido, pero el multi-source no compara sin
  el segundo source (`ref: values`).

## Fix aplicado

1. **`repoURL` rellenado en los 7 manifests con placeholder** (valor real del
   repo `origin`: `https://github.com/Damianpiazz/K8s.git`, `targetRevision:
   main`):

   - `cluster/base/argocd/app-of-apps.yaml` (root)
   - `cluster/base/argocd/applications/kube-prometheus-stack.yaml`
   - `cluster/base/argocd/applications/ingress-nginx.yaml`
   - `cluster/base/argocd/applications/external-dns.yaml`
   - `cluster/base/argocd/applications/ecommerce-dev.yaml`
   - `cluster/base/argocd/applications/ecommerce-staging.yaml`
   - `cluster/base/argocd/applications/ecommerce-prod.yaml`

   Los sources que usan chart Helm remoto (cert-manager, external-secrets,
   keda, kyverno) se mantienen sin cambios, como corresponde.

2. **Path de `$values` verificado**: el Application de KPS referencia
   `$values/cluster/base/monitoring/kube-prometheus-stack/values.yaml`; el
   archivo existe en el repo (`cluster/base/monitoring/kube-prometheus-stack/
   values.yaml`), por lo que **no requirió corrección**. Ídem
   `cluster/base/ingress-nginx/values.yaml` para ingress-nginx.

3. **Root aplicado**:

   ```bash
   kubectl --kubeconfig $env:USERPROFILE\.kube\floci-ecommerce.yaml apply -f cluster/base/argocd/app-of-apps.yaml
   ```

   Resultado: `ecommerce-apps` creado, `Synced/Healthy` en revisión `0e713fa`,
   y los 10 children creados.

Archivos afectados:

- `cluster/base/argocd/app-of-apps.yaml` (repoURL rellenado)
- `cluster/base/argocd/applications/kube-prometheus-stack.yaml` (repoURL rellenado)
- `cluster/base/argocd/applications/ingress-nginx.yaml` (repoURL rellenado)
- `cluster/base/argocd/applications/external-dns.yaml` (repoURL rellenado)
- `cluster/base/argocd/applications/ecommerce-dev.yaml` (repoURL rellenado)
- `cluster/base/argocd/applications/ecommerce-staging.yaml` (repoURL rellenado)
- `cluster/base/argocd/applications/ecommerce-prod.yaml` (repoURL rellenado)
- `docs/fixes/23-argocd-app-of-apps-kube-prometheus-stack.md` (este documento)

## Pendiente

`kube-prometheus-stack` **no alcanzó `Synced/Healthy`** en la espera acotada
(240 s): el estado observado es `sync=Unknown`, `health=Healthy` (vacío, sin
recursos desplegados) con la `ComparisonError` detallada arriba. El motivo es
que `origin/main` aún contiene los placeholders.

Para destrabar **no hace falta ninguna acción manual sobre el clúster**: al
hacer commit+push de estos cambios a `main`, el root re-sincroniza, re-renderiza
los children con el `repoURL` real y el auto-sync/self-heal despliega KPS en
`observability`.

## Cómo verificar

**Estado actual (esperado hasta el push):**

```bash
kubectl --kubeconfig $env:USERPROFILE\.kube\floci-ecommerce.yaml get app -n argocd -o wide
```

- `ecommerce-apps` (root): `Synced/Healthy` en la revisión `0e713fa`.
- `cert-manager`, `external-secrets`: `Synced/Healthy`.
- `kube-prometheus-stack` y los app con source git: `Unknown` con
  `ComparisonError` (repoURL placeholder en `origin/main`).

**Después del commit+push a `main` (siguiente fase):**

```bash
kubectl --kubeconfig $env:USERPROFILE\.kube\floci-ecommerce.yaml get app -n argocd kube-prometheus-stack -o jsonpath="{.status.sync.status} {.status.health.status}"
```

- Debe devolver `Synced Healthy` (self-heal automático, sin intervención).
- `kubectl ... get pods -n observability -o wide` debe mostrar
  `prometheus-kube-prometheus-prometheus-0` (statefulset),
  `alertmanager-*`, `grafana-*`, `kube-state-metrics-*` y los DaemonSet
  `node-exporter-*` en `Running`.