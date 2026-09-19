# Fix 27 — Desacoplamiento de entorno (label `env`, tags de imagen, Grafana, runbooks)

## Fecha
2026-09-18

## Error / contexto observado

Cuatro deudas de acoplamiento a un único entorno (todas en el plan de paridad
floci ↔ Azure, Fase 3):

1. **Label `env` falso en la mayoría de los ambientes.** El base hardcodeaba
   `env: production` en el template del Pod de los 18 Deployments de servicios
   y en el Namespace `ecommerce`. En `local`, `dev` y `staging` el valor
   mentía — y las métricas (el ServiceMonitor relabela `env` desde el label
   del Pod vía `__meta_kubernetes_pod_label_env`) quedaban etiquetadas como
   `production` aunque corrieran en otro entorno. El dashboard RED (fix 26)
   ya tenía la variable `$env` lista; sin el label correcto era un no-op.
2. **Overlays Azure renderizando `:latest`.** `dev`, `staging` y `prod` fijaban
   `newTag: latest` en los 18 `images:` del overlay. El flujo GitOps real
   funciona porque el CD reescribe `newTag` al SHA del commit, pero cualquier
   render directo (o un CD que no corra) deja `:latest`, que la política
   Kyverno `disallow-latest-tag` rechaza.
3. **Password de Grafana como placeholder en values.** `values.yaml` traía
   `adminPassword: <grafana-admin-password>` con un TODO de Phase 4. El chart
   de Grafana (8.4.x, embebido por kube-prometheus-stack 61.9.0) soporta
   `grafana.admin.existingSecret` — nadie lo usaba.
4. **Runbooks de alertas apuntando a `example.com`.** Las 4 PrometheusRules
   (`service-down`, `service-high-error-rate`, `service-high-latency`,
   `jvm-memory-pressure`) tenían `runbook: "https://example.com/..."` con un
   `# TODO: replace with your runbook URL`, mientras el repo ya tiene
   `docs/runbooks/` (troubleshooting, scaling-and-recovery,
   deploy-end-to-end, runbook-index).

## Causa raíz

1. **`env` era un valor de base, no de overlay.** La regla del plan dice "todo
   valor que cambia entre entornos vive en el overlay, nunca en base"; el label
   `env` violaba esa regla. La política `require-labels` de Kyverno solo exige
   que el label exista, así que el valor falso pasaba desapercibido.
2. **El contrato de tags dependía 100% del CD.** `cd.yml` (job `set-env`) usa
   `yq` para reescribir `(.images[] | select(.newName == "${ACR_NAME}.azurecr.io/${svc}")) .newTag = "${GITHUB_SHA}"`.
   Si una entrada `images:` falta o su `newName` no matchea, el tag queda
   `latest` en silencio. Los overlays Azure nunca debieron declarar `latest`.
3. **El mecanismo de credenciales estaba postergado.** El comentario en
   `values.yaml` lo dejaba para "Phase 4"; el placeholder viajaba a cualquier
   render del stack. Verificado contra el chart real: `kube-prometheus-stack`
   61.9.0 embebe `grafana` 8.4.x, cuya estructura de admin es anidada
   (`admin.existingSecret`, clave por defecto `admin-password` — lo confirma
   el template `NOTES.txt` del chart:
   `{{ .Values.grafana.admin.passwordKey | default "admin-password" }}`).
4. **Runbooks existían sin wiring.** Los docs de runbooks estaban completos
   pero ninguna alerta los referenciaba; el TODO quedó de la creación de las
   reglas.

## Fix aplicado

### 1) Label `env` por overlay

- **Base**: se eliminó `env: production` de los 18
  `services/*/k8s/base/deployment.yaml` (template labels) y del Namespace
  `ecommerce` (`cluster/base/namespaces/namespaces.yaml`). El selector
  `matchLabels` de los Deployments solo usa `app`, así que no hubo que tocar
  selectores.
- **Overlays**: cada kustomization (local/dev/staging/prod) suma dos parches
  inline JSON6902:
  - `Deployment` con `labelSelector: app.kubernetes.io/part-of=ecommerce-platform`
    → `add /spec/template/metadata/labels/env` con el valor real;
  - `Namespace ecommerce` → `add /metadata/labels/env` con el mismo valor.
- Valores: `local` → `local`, `dev` → `dev`, `staging` → `staging`,
  `prod` → `production`. El `env-config.yaml` de prod alineó su label a
  `production` (antes `prod`) para una sola convención.
- Los ServiceMonitors ya relabelan `__meta_kubernetes_pod_label_env` → `env`
  (verificado en `observability/servicemonitors/servicemonitors.yaml`): no
  hizo falta tocarlos. Los dashboards ahora filtran por entorno de verdad.

### 2) Estrategia única de imágenes (nunca `:latest` en renders)

- `dev`, `staging`, `prod`: los 18 `newTag: latest` → **SHA largo del HEAD de
  main** (`42c7c2bf266c81f3a5dfdda08319eb336e57a516`), el mismo formato que
  escribe el CD (`${GITHUB_SHA}`). Un render directo queda totalmente
  especificado y Kyverno no lo rechaza; el CD lo reescribe en el próximo push.
- `local`: los `newTag` ya eran SHA cortos reales (imágenes pusheadas al
  registry sidecar). Se mantienen; ahora `scripts/build-push.ps1` los
  **mantiene automáticamente**: al final de cada push local actualiza los 18
  `newTag` del overlay local al SHA corto recién pusheado (modo `-Azure` no
  toca el overlay — el CD gestiona Azure).
- **Contrato documentado**: una sola fuente de verdad de tags por ambiente —
  local → `build-push.ps1` (SHA corto); Azure → `cd.yml` (SHA largo). Los
  comentarios de los 3 overlays Azure + el header del script lo explican.
- Aclaración: el **campo raw** `image: acr.azurecr.io/<svc>:latest` de los
  base NO se tocó — es el default amigable de lectura que el `images:` de
  cada overlay reescribe siempre (los 4 overlays listan los 18 servicios).
  Lo que renderiza un overlay nunca contiene `:latest`.

### 3) Password de Grafana vía external-secrets

- `cluster/base/monitoring/kube-prometheus-stack/values.yaml`:
  `adminPassword: <grafana-admin-password>` → `grafana.admin.existingSecret:
  grafana-admin-credentials` (estructura del subchart grafana 8.4.x; la clave
  leída es `admin-password`, el default de `admin.passwordKey`).
- Nuevo `cluster/base/external-secrets/grafana-admin-credentials.yaml`
  (ExternalSecret, namespace `observability`): lee el secreto de vault
  `grafana-admin-password` del ClusterSecretStore `azure-keyvault` y
  materializa el Secret `grafana-admin-credentials` con la clave
  `admin-password`. Espeja el patrón del Keycloak
  (`services/auth/k8s/base/external-secret.yaml`).
- Overlay **local** (sin Key Vault): borra el ExternalSecret por nombre (como
  los demás deletes de ESO) y agrega un Secret placeholder
  `grafana-admin-credentials` (namespace `observability`, clave
  `admin-password` → `admin`, el default del chart). Documentado como
  placeholder — NUNCA commitear credenciales reales.
- La siembra del secreto `grafana-admin-password` en los Key Vaults de
  dev/staging/prod queda para la Fase 5 (terraform/validación), igual que
  `keycloak-admin-password`.

### 4) Runbooks reales en las alertas

| Alerta | runbook |
|---|---|
| `ServiceDown` | `docs/runbooks/troubleshooting.md` |
| `ServiceHighErrorRate` | `docs/runbooks/troubleshooting.md` |
| `ServiceHighLatency` | `docs/runbooks/scaling-and-recovery.md` |
| `JvmMemoryPressure` | `docs/runbooks/troubleshooting.md` |

URL: `https://github.com/Damianpiazz/K8s/blob/main/docs/runbooks/<archivo>.md`
+ comentario con el path en el repo, para que el mantenedor los mueva juntos.
`runbook-index.md` documenta la convención.

## Archivos afectados

- Base: `services/*/k8s/base/deployment.yaml` (×18, sin `env`), `cluster/base/namespaces/namespaces.yaml`, `cluster/base/kyverno/policies/require-labels.yaml` (comentario), `cluster/base/README.md` (tabla de placeholders)
- Overlays Azure: `cluster/overlays/{dev,staging,prod}/kustomization.yaml` (patches `env` + `newTag` SHA), `cluster/overlays/prod/env-config.yaml` (label `production`)
- Overlay local: `cluster/overlays/local/kustomization.yaml` (patch `env`, resource del Secret, delete del ExternalSecret), `cluster/overlays/local/grafana-admin-credentials.yaml` (nuevo)
- Grafana: `cluster/base/monitoring/kube-prometheus-stack/values.yaml`, `cluster/base/external-secrets/grafana-admin-credentials.yaml` (nuevo), `cluster/base/kustomization.yaml`
- Scripts: `scripts/build-push.ps1` (mantenimiento del `newTag` local)
- Alertas: `observability/alerts/{service-down,service-high-error-rate,service-high-latency,jvm-memory-pressure}.yaml`
- Docs: `docs/PLAN.md` (Fase 3 → ✅), `docs/fixes/README.md` (fila 27), `docs/runbooks/runbook-index.md`, `cluster/overlays/README.md` (secciones obsoletas reescritas)
- Tests: `tests/manifests/test-kustomize-build.py` (assertions de env/images + contratos estáticos)

## Cómo verificar

```powershell
# Suite completa (44 targets + wiring + env/images + contratos estáticos):
python tests/manifests/test-kustomize-build.py
#   -> KUSTOMIZE BUILD CHECK PASSED

# env por overlay (18 pods + Namespace ecommerce + ConfigMap env-config):
kubectl kustomize cluster/overlays/dev | Select-String "env: dev"      # 20
kubectl kustomize cluster/overlays/staging | Select-String "env: staging"   # 20
kubectl kustomize cluster/overlays/prod | Select-String "env: production"   # 20
kubectl kustomize cluster/overlays/local | Select-String "env: local"       # 21

# Ningún render con :latest (solo los patterns de la ClusterPolicy Kyverno):
kubectl kustomize cluster/overlays/{dev,staging,prod,local} |
  Select-String "image: .*:latest"    # -> 0 (dev/staging/prod muestran solo '!*:latest' de la policy)

# Grafana: placeholder fuera de values, ExternalSecret en Azure, Secret en local:
rg -n "<grafana-admin-password>" cluster/                              # -> sin resultados
kubectl kustomize cluster/overlays/dev | Select-String -Context 0,2 "name: grafana-admin-credentials"
kubectl kustomize cluster/overlays/local | Select-String -Context 0,2 "grafana-admin-credentials"

# Runbooks: sin example.com en alertas:
rg -n "example.com" observability/alerts/                              # -> sin resultados

# Contrato de tags: dev/staging/prod renderizan el SHA pinneado:
kubectl kustomize cluster/overlays/dev | Select-String "acr.azurecr.io/auth:"  # :42c7c2bf...
```

## Notas

- `build-push.ps1` reescribe los `newTag` del overlay local con el SHA corto
  pusheado (solo modo local). Validar con un push real + `git diff` del
  overlay local en el próximo ciclo floci.
- El registry `acr.azurecr.io` sigue siendo placeholder (auditoría de
  `cluster/overlays/README.md`): el `newName` real del ACR es
  `<env>ecommerce<suffix>.azurecr.io` según `infra/terraform/modules/registry`
  (output `acr_login_server`); se reemplaza UNA vez por ambiente.
- `env: production` seguirá apareciendo en renders de **prod** (correcto) y en
  `cluster/overlays/README.md` como doc histórica reescrita.