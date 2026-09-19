# PLAN — Refinamiento de paridad floci ↔ Azure (2026-09-18)

Plan canónico de refinamiento del monorepo. Reemplaza a los planes anteriores
(`docs/plan-ecommerce-k8s.md`, `docs/floci/PLAN.md`,
`docs/floci/plan-implementacion.md`), eliminados del working tree por decisión
del usuario (2026-09-18).

## Objetivo

Que toda la plataforma (API Gateway, Keycloak, Kafka/Event Hubs, Redis,
PostgreSQL, 18 servicios, frontend) funcione **igual** en el emulador local
**floci** y en **Azure** (dev / staging / production), con:

1. Monitoreo Prometheus + Grafana funcional en ambos lados (paneles RED y de
   plataforma que reflejen el estado real del sistema).
2. UI de ArgoCD accesible en local y en Azure, en todos los entornos, con
   acceso apto para producción (ingress + TLS + SSO Keycloak).
3. Cero acoplamiento a un entorno: la misma base GitOps sirve a los cuatro
   overlays (`local`, `dev`, `staging`, `prod`).

## Decisiones tomadas (2026-09-18)

| Decisión | Valor |
|---|---|
| Docs de plan viejos | Borrar y repuntar referencias a este documento |
| Panel RED | Reescribir contra métricas Micrometer reales y agregar al repo |
| Paridad floci | GitOps total: floci corre ArgoCD + sus Applications como Azure |
| Acceso a UIs | Ingeniería apta para producción: ingress + TLS + SSO Keycloak (ArgoCD/Grafana) |
| Validación Azure | El usuario puede validar terraform/AKS en la Fase 5 |
| Commits/PRs | Solo con permiso explícito del usuario |
| Fixes | Cada corrección documentada en `docs/fixes/NN-*.md` |

## Fases

### Fase 0 — Higiene de docs (✅, verificación runtime pendiente en k3s local; 2026-09-18)
- Eliminar planes viejos del árbol, repuntar las 13 referencias del repo.
- Crear este plan como fuente de verdad.
- Indexar en `docs/fixes/` (fix 24).

### Fase 1 — Monitoreo en floci (✅, verificación runtime pendiente en k3s local; 2026-09-18)
- Restaurar kube-prometheus-stack + ServiceMonitors + PrometheusRules en
  `cluster/overlays/local` (hoy el overlay los borra).
- Habilitar métricas de Keycloak (`--metrics-enabled`), revisar ServiceMonitor
  de `auth` (token/endpoint) para que no dispare `ServiceDown`.
- Verificar scrapes y dashboards en el k3s local.
- (fix 25) El overlay local ya renderiza la Application kube-prometheus-stack
  + 18 ServiceMonitors + 5 PrometheusRules, y Keycloak expone /metrics en el
  puerto HTTP principal (8080) con `--metrics-enabled=true`; la suite de
  manifests verifica el wiring SM → Service → Deployment; falta verificar
  scrapes/dashboards en el k3s.

### Fase 2 — Panel RED (✅, 2026-09-18)
- Dashboard RED (`observability/dashboards/red-metrics.json`, uid
  `red-metrics-dashboard`) reescrito contra las familias reales de Micrometer
  (`http_server_requests_seconds_count/sum/bucket`, labels `job`/`uri`/`method`,
  `status`) y provisionado vía sidecar igual que los demás dashboards.
- Filas Rate / Errors / Duration / System State con thresholds (error 1%/5%,
  latencia 0.5s/1s) y variables `$namespace`/`$app`/`$uri`/`$method`/`$env`;
  la variable `env` activa el filtrado real por entorno tras la Fase 3 (hoy
  default `.*` = no-op). Usable ya en todos los entornos.

### Fase 3 — Desacoplamiento de entorno (✅, 2026-09-18)
- Label `env` correcto por overlay: la base ya NO hardcodea `env: production`;
  cada overlay firma el valor real en pods + Namespace ecommerce (fix 27,
  `patches` inline → `local|dev|staging|production`).
- Estrategia única de imágenes: ningún overlay renderiza `:latest` — local
  gestiona tags con `scripts/build-push.ps1` (SHA corto) y dev/staging/prod
  pinnean el SHA de `main` que el CD reescribe (contrato `cd.yml`).
- Password de Grafana vía external-secrets / Key Vault: `values.yaml` usa
  `grafana.admin.existingSecret` → Secret `grafana-admin-credentials`
  (ExternalSecret en dev/staging/prod; placeholder local).
- Runbooks reales en las alertas (URLs al repo, ya no `example.com`).

### Fase 4 — UIs (ingress + TLS + SSO) y GitOps total en floci (✅, 2026-09-18)
- Ingress de ArgoCD y Grafana por ambiente: `*.localhost` en floci,
  `argocd.<domain>` / `grafana.<domain>` en Azure (el CD sustituye la zona en
  Fase 5), TLS por issuer (selfsigned local, LE staging/prod) → Secrets
  `argocd-tls` / `grafana-tls` / `auth-tls`.
- SSO OIDC de ArgoCD y Grafana contra Keycloak: clients `argocd`/`grafana` +
  grupo `ecommerce-admins` en el realm; `argocd-cm`/`argocd-rbac-cm`/
  `oidc-argocd-secret`/`grafana-oauth-config` por overlay; `admin` como
  fallback local (fix 28).
- floci GitOps total: el overlay local importa las 6 Applications de
  operadores + 4 ClusterPolicies + 18 HPA (fix 13 revertido) y suma la
  Application `ecommerce-local` (sync automático desde `origin/main`, requiere
  push); suplemento standalone `argocd-extra` para hostAliases de
  argocd-server. Falta verificar el ciclo real en el k3s (checklist LIVE en
  el fix 28).

### Fase 5 — Azure desplegable end-to-end ✅
- ✅ Módulo Terraform de Key Vault: RBAC data-plane para la identidad kubelet
  (ManagedIdentity + IMDS), 8 secrets sembrados, outputs
  (`keyvault_vault_url` / `keyvault_tenant_id` / `eso_identity_client_id`) +
  output `kubelet_identity_client_id` en el módulo aks.
- ✅ external-secrets puntas reales por ambiente: `oidc-argocd`,
  `grafana-oauth-credentials`, `event-hubs-credentials` (sasl-username/
  sasl-password); SASL de Event Hubs (`SASL_SSL` + JAAS) en checkout-svc y
  notification-svc vía patches Azure; env-config con FQDNs reales
  (`psql-`/`redis-`/`eh-<env>-ecommerce-<suffix>`).
- ✅ Realm de Keycloak con URIs válidas por overlay (auth-realm-configmap +
  deployment patch; contrato realm/secret con placeholder compartido para
  rotación; local excluye el ExternalSecret event-hubs-credentials).
- ✅ Contrato del frontend verificado: default del Dockerfile
  `NEXT_PUBLIC_MOCK=false` (local y Azure, standalone sin rewrites);
  `.env.example` (`MOCK=true`) solo para `pnpm dev`; CI sin build args.
- 🔲 Validación con el usuario (az login / terraform apply / sustitución de
  outputs en los store patches / sync ArgoCD / ciclo SSO) — checklist LIVE en
  el fix 29; ajuste del `<suffix>` de los env-configs al tfvars real.

### Fase 6 — Paridad fina ✅
- ✅ Datasource postgres REAL en los 4 servicios con DB (catalog/order/
  inventory/payment): el base ya NO lleva `jdbc:h2` (los ConfigMaps quedan
  limpios; el H2 vive solo como fallback documentado en application.yml y los
  demo yml del config-service) y cada overlay inyecta la URL por `env`
  (precedencia `env` > `envFrom`): local → sidecar `postgres:16-alpine` POR
  POD (127.0.0.1:5432/DB propia, reemplaza el postgres del emulador con su
  puerto dinámico 37865) y Azure → Flexible Server vía `${DB_HOST}:${DB_PORT}`
  (misma versión mayor 16 en ambos lados).
- ✅ Credenciales single-source: el Key Vault siembra `db-username`/
  `db-password` desde las MISMAS tfvars del módulo databases
  (`postgres_administrator_login/password`); el ExternalSecret base
  `db-credentials` lo entrega (Secret placeholder plano en local); se eliminó
  el `random_password "db"` que NO coincidía con el servidor real.
- ✅ Keycloak en postura de producción en los overlays Azure (`kc.sh start` +
  `--db=postgres` + `--proxy-headers=xforwarded` + `KC_DB_URL/USERNAME/
  PASSWORD` contra el server compartido, DB `keycloak` agregada a
  `postgres_databases`); local conserva `start-dev` + H2 (documentado, sin
  postgres gestionado en floci).
- ✅ DB `inventory` + `keycloak` agregadas a `postgres_databases` de los 3
  tfvars; NetworkPolicy egress local recortada al Kafka del host (los services
  con sidecar ya no salen al host); suite de manifests con
  `check_phase6_parity_fina` (renders 4/4 + 43 targets verdes).
- ⚠️ payment-svc: el datasource ya apunta a Postgres, pero el store real sigue
  siendo el HashMap de `PaymentService` (sin JPA aún) — la persistencia aterriza
  con la implementación JPA del repositorio (pendiente de producto, no es
  config).
- 🔲 Validación con el usuario: checklist LIVE del fix 30 (az login / terraform
  apply / sustitución de `<suffix>` / sync ArgoCD / ciclo SSO); la validación
  estática (renders, suite, greps) quedó ✅ en este documento.

## Reglas de trabajo

- **Sin commits ni PRs** sin permiso explícito.
- Cada fix se documenta en `docs/fixes/NN-*.md` y se indexa en
  `docs/fixes/README.md`.
- Nada de configuraciones acopladas a entorno: todo valor que cambia entre
  entornos vive en el overlay, nunca en base.
- El estado de cada fase se actualiza aquí y se verifica con una revisión de
  contexto fresco antes de avanzar.