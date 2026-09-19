# Fix 29 — Azure desplegable end-to-end (Fase 5)

## Problema

Cinco frentes bloqueaban la validación real de Azure (checklist del fix 28
incompleto):

1. **No había Key Vault** en terraform: ningún módulo de secrets, ninguna
   fuente de credenciales para los ExternalSecrets del base.
2. **external-secrets solo tenía puntas de ejemplo**: `keycloak-admin-credentials`
   y `grafana-admin-credentials` apuntaban al placeholder `kv-<env>-ecommerce`
   y ningún Secret real (SSO, Event Hubs) tenía materialización Azure.
3. **Event Hubs sin integración**: Kafka de Azure exige SASL (`SASL_SSL`,
   JAAS con connection string), y no existía ni Secret de credenciales ni envs
   SASL en los deployments de checkout-svc / notification-svc; el
   `ScaledObject order-worker` del base traía `<event-hubs-ns>` literal.
4. **Realm de Keycloak inválido en Azure**: el realm embebido en la imagen
   (`services/auth/src/realm-export.json`) usa `https://api.<domain>/…` —
   URI inválida → la importación de Keycloak aborta.
5. **Contrato de build del frontend ambiguo**: el plan pedía ARGs de build
   (`NEXT_PUBLIC_MOCK=false`) sin verificar el default real del Dockerfile.

## Arreglo

### 1. Módulo Terraform `keyvault` (nuevo)

- `infra/terraform/modules/keyvault/` — Key Vault `kv-<env>-ecommerce-<suffix>`
  (sku `standard`, `soft_delete_retention_days = 7`, RBAC
  `enable_rbac_authorization = true`).
- Role assignment `Key Vault Secrets User` (data-plane) a la **identidad
  kubelet** del AKS (la que usan los nodos para IMDS) → el ClusterSecretStore
  usa `authType: ManagedIdentity` + `identityId` = client id del kubelet.
- 8 secrets sembrados: `keycloak-admin-username`, `keycloak-admin-password`,
  `grafana-admin-password`, `db-password`, `argocd-oidc-client-secret`,
  `grafana-oauth-client-secret`, `event-hubs-sasl-username` (constante
  `$ConnectionString`), `event-hubs-sasl-password` (connection string del
  namespace, desde `module.streaming.connection_string`).
- Outputs: `keyvault_name`, `keyvault_id`, `keyvault_vault_url`,
  `keyvault_tenant_id`, `secret_names`.
- `aks` module: nuevo output `kubelet_identity_client_id` (para el
  `identityId` del store).
- Wiring + outputs + variables en los 3 envs; `terraform.tfvars` con el bloque
  keyvault (los 2 secrets de SSO con default
  `REPLACE_WITH_A_SECURE_CLIENT_SECRET`); `dev` fija
  `dns_zone_name = "dev.ecommerce.example.com"`.

### 2. ExternalSecrets reales (puntas Azure)

- **`oidc-argocd`** pasa de Secret placeholder a **ExternalSecret**
  (ns `argocd`, `clientSecret ← argocd-oidc-client-secret`).
- **`grafana-oauth-credentials`** (nuevo, ns `observability`, key
  `GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET ← grafana-oauth-client-secret`): el pod
  de Grafana lo inyecta vía `grafana.envValueFrom` (soportado por el subchart
  8.4.x — values.yaml línea ~200, `_pod.tpl` línea ~1074) — **el ConfigMap
  `grafana-oauth-config` ya no lleva la key** (los 4 overlays).
- **`event-hubs-credentials`** (nuevo en el base, ns `ecommerce`, keys
  `sasl-username`/`sasl-password`).
- Local: el Secret `grafana-oauth-credentials` placeholder reemplaza al
  ExternalSecret (que se excluye junto con `event-hubs-credentials` y el
  ClusterSecretStore).
- El ClusterSecretStore pasa a `ManagedIdentity` explícito; el patch por
  overlay fija `tenantId`/`vaultUrl` (`kv-<env>-ecommerce-tppractico01`)/
  `identityId` (los `<...>` se sustituyen post-apply con los outputs).

### 3. SASL Event Hubs

- Patches **solo en overlays Azure** (`patches/checkout-svc-sasl.yaml` y
  `patches/notification-svc-sasl.yaml`) que agregan:
  `EVENT_HUBS_SASL_USERNAME`/`EVENT_HUBS_SASL_PASSWORD` (secretKeyRef) +
  `SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG`
  (`PlainLoginModule required username="${EVENT_HUBS_SASL_USERNAME}" password="${EVENT_HUBS_SASL_PASSWORD}";`)
  + `SPRING_KAFKA_PROPERTIES_SASL_MECHANISM=PLAIN` (relaxed binding;
  Spring Boot resuelve los `${}` contra las env vars del pod).
- Env-config Azure: `KAFKA_BOOTSTRAP=eh-<env>-ecommerce-<suffix>.servicebus.windows.net:9093`,
  `KAFKA_SECURITY_PROTOCOL=SASL_SSL`,
  `CHECKOUT_EVENTS_KAFKA_ENABLED=true`, `NOTIFICATIONS_KAFKA_ENABLED=true`;
  FQDNs de Postgres/Redis alineados con los módulos (suffix incluido).
- KEDA: el `ScaledObject order-worker` **sigue siendo un ejemplo decorativo**
  (no existe el deployment order-worker); solo se parchean sus `brokers` con
  el endpoint real por overlay. Local lo excluye (envs `$EVENT_HUBS_SASL_*`
  inexistentes).

### 4. Realm por overlay

- `auth-realm-configmap.yaml` por entorno (dev/staging/prod) con hosts reales
  del zone (`*.dev.ecommerce.example.com`, `*.staging.ecommerce.example.com`,
  `ecommerce.example.com`): clients `ecommerce-web` (PKCE, public),
  `ecommerce-api`, `argocd`, `grafana` — redirectUris/webOrigins válidos.
- `patches/deployment-auth-realm-patch.yaml` monta el ConfigMap sobre
  `/opt/keycloak/data/import/realm-export.json` (subPath) **sin tocar
  command/args** (el base ya trae `--health/--metrics-enabled`).
- Contrato realm/secret: `argocd` y `grafana` arrancan con el MISMO
  placeholder que las variables de terraform y que el KV → la cadena
  realm ↔ KV ↔ Secret materializado funciona out of the box; rotar = cambiar
  el placeholder en el realm + las variables (`argocd_oidc_client_secret`,
  `grafana_oauth_client_secret`).

### 5. Frontend: contrato verificado (sin cambio de código)

- El Dockerfile ya tiene default `NEXT_PUBLIC_MOCK=false` y `reusable-build.yml`
  **no pasa build args** → el CI ya hornea `false`. Contrato:
  - `pnpm dev` local → `.env.example` (`MOCK=true`) para desarrollo.
  - Build standalone (local y Azure) → `false` (el server desactiva las
    rewrites; /api resuelve same-origin vía ingress).

### 6. Hosts reales en ingresses y Argo CD

- Los 5 ingresses (argocd, grafana, auth, api-gateway, frontend) parchean
  `host` + `tls.hosts[0]` por overlay (JSON6902), igual que local con
  `*.localhost`.
- `argocd-cm` y `grafana-oauth-config` usan URLs reales del zone; Argo CD y
  Grafana resuelven el token server-side contra el issuer público (TLS Let's
  Encrypt real → sin `insecure.skip.verify`).

**NOTA de validación**: la cadena de SSO conserva el placeholder
`REPLACE_WITH_A_SECURE_CLIENT_SECRET` en el realm del ConfigMap y en las
variables de terraform por contrato — no es un leak; los Secrets materializados
son ExternalSecrets (sin valores planos) y el ConfigMap de Grafana ya no
contiene la key.

## Archivos

- `infra/terraform/modules/keyvault/{variables,main,outputs}.tf` (nuevo),
  `infra/terraform/modules/aks/main.tf`, `infra/terraform/envs/{dev,staging,prod}/{main,outputs,variables}.tf` + `terraform.tfvars`.
- `cluster/base/external-secrets/event-hubs-credentials.yaml` (nuevo),
  `cluster/base/external-secrets/cluster-secret-store.yaml`,
  `cluster/base/kustomization.yaml`,
  `cluster/base/monitoring/kube-prometheus-stack/values.yaml`.
- `cluster/overlays/{dev,staging,prod}/`: `oidc-argocd-secret.yaml` (→ ES),
  `grafana-oauth-secret.yaml` (nuevo), `auth-realm-configmap.yaml` (nuevo),
  `patches/{deployment-auth-realm-patch,checkout-svc-sasl,notification-svc-sasl,secret-store-patch}.yaml`,
  `grafana-oauth-config.yaml`, `argocd-cm.yaml`, `env-config.yaml`,
  `kustomization.yaml`.
- `cluster/overlays/local/`: `grafana-oauth-credentials.yaml` (nuevo),
  `grafana-oauth-config.yaml`, `kustomization.yaml`.

## Validación local (estática)

1. `kubectl kustomize cluster/overlays/{local,dev,staging,prod}` → OK (4/4).
2. `python tests/manifests/test-kustomize-build.py` → 43/43.
3. Grep de contratos sobre los renders:
   - `GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET` presente en el Secret
     `grafana-oauth-credentials` (local y ES Azure), ausente en los ConfigMaps.
   - `REPLACE_WITH_A_SECURE_CLIENT_SECRET` solo en realm/ES placeholders
     esperados (excepción documentada), no en Secrets planos de trabajo.
   - `<domain>` ausente en los renders Azure (excluye realm local).
   - SASL envs presentes en checkout/notification de Azure; ausentes en local.
4. Terraform: sin `.terraform`/init en dev/staging/prod (backend remoto azurerm)
   → `validate` no pudo correr; revisión estática del HCL en su lugar.

## Validación del usuario (LIVE, requiere suscripción)

```powershell
# 1) Login + suscripción
az login --tenant <tenant-id>
az account set --subscription <subscription-id>

# 2) Estado del repo (tiene que estar commiteado/pusheado para ArgoCD)
git status  # solo archivos del fix 29
git push

# 3) Terraform por entorno (orden recomendado: dev → staging → prod)
terraform -chdir=infra/terraform/envs/dev init
terraform -chdir=infra/terraform/envs/dev plan
terraform -chdir=infra/terraform/envs/dev apply -auto-approve

# 4) Sustituir los <...> del store una vez por entorno (los placeholders
#    rompen el sync del ExternalSecret hasta el primer apply de ArgoCD)
terraform -chdir=infra/terraform/envs/dev output keyvault_tenant_id
terraform -chdir=infra/terraform/envs/dev output keyvault_vault_url
terraform -chdir=infra/terraform/envs/dev output eso_identity_client_id

# 5) Verificación en el cluster (dev como ejemplo)
kubectl -n external-secrets get clustersecretstore azure-keyvault -o yaml
kubectl -n ecommerce get externalsecret event-hubs-credentials      # SecretReady
kubectl -n ecommerce get secret event-hubs-credentials -o jsonpath='{.data.sasl-username}' | base64 -d
kubectl -n argocd get externalsecret oidc-argocd                    # SecretReady
kubectl -n observability get externalsecret grafana-oauth-credentials
kubectl -n ecommerce get configmap auth-realm-dev -o jsonpath='{.data.realm-export\.json}' | Select-String 'redirectUris'

# 6) Ciclo SSO real (browser)
#   - https://argocd.<zone>  → "Log in via Keycloak" (demo/demo123) → UI admin
#   - https://grafana.<zone> → Login with Keycloak → Admin (grupo ecommerce-admins)
#   - login directo https://auth.<zone>/realms/ecommerce  (demo/demo123)

# 7) Kafka real (opcional): listener de prueba en Event Hubs + checkout
#    con CHECKOUT_EVENTS_KAFKA_ENABLED=true → evento en el topic.
```

## Estado

✅ Implementado y validado estáticamente (4 renders + suite). Validación LIVE
pendiente de la ejecución del usuario; el sufijo `<suffix>` en los
env-configs se ajusta al `tfvars` real de cada entorno (hoy `tppractico01`).