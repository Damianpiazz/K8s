# Fix 30 — Paridad fina: Postgres real (sidecar local + Azure gestionado) y Keycloak en modo producción

- **Fase**: 6 (última) — paridad fina del plan.
- **Fecha**: 2026-09-18.
- **Alcance**: config de cluster, terraform, tests y docs. Sin cambios de código
  Java (ver nota de payment-svc) ni de imágenes.

## Contexto

Tras el fix 29 (Azure desplegable), el clúster quedó desplegable end-to-end pero
**la forma sin el fondo**:

1. Los 4 servicios con datasource (catalog-svc, order-svc, inventory-svc,
   payment-svc) seguían corriendo **H2 en memoria** en TODOS los entornos —
   incluso en Azure: los patches de prod solo tenían comentarios de advertencia,
   nunca el override real del datasource.
2. **Keycloak** corría `kc.sh start-dev` con H2 embebido también en Azure
   (postura de desarrollo en producción).
3. El Key Vault (fix 29) sembraba `db-password` desde un `random_password`
   interno que **NO coincidía** con la password real del Flexible Server
   (`postgres_administrator_password` del tfvars) → cualquier intento de
   conectar con `db-credentials` habría fallado.
4. El overlay local apuntaba al postgres del emulador por `host.docker.internal`
   con **puerto dinámico** (37865) y una **DB compartida** (`ecommerce`) —
   frágil y distinto de Azure (DB por servicio).

## Diagnóstico

- Servicios con DB real (JPA + entidades): **catalog-svc, order-svc,
  inventory-svc**. payment-svc tiene el datasource configurado pero el store es
  un HashMap en memoria (`PaymentService`) — la persistencia es el camino JPA
  futuro (documentado en su application.yml).
- Los 4 servicios traen `h2` Y `postgresql` en el pom → el driver Postgres ya
  está disponible, **no hace falta tocar poms**.
- Mecanismo de precedencia: en el pod, `env` del contenedor > `envFrom` del CM —
  el overlay puede overridear las keys `SPRING_DATASOURCE_*` sin tocar el base.
- El `DB_NAME` compartido del env-config es `ecommerce`; el terraform crea
  databases **por servicio** (`orders`, `payments`, `catalog`) → la URL debe
  fijar la DB por servicio, no usar `${DB_NAME}`.
- Keycloak ≥ 17: `kc.sh start` con `--db=postgres` + `--proxy-headers=xforwarded`
  es la postura correcta tras un ingress que termina TLS; el realm montado se
  importa igual (`--import-realm`).

## Cambios

### Terraform (`infra/terraform`)

- `modules/keyvault/main.tf`: **eliminado** `random_password "db"`; ahora
  `azurerm_key_vault_secret.db_username` (`db-username` =
  `var.postgres_administrator_login`) y `azurerm_key_vault_secret.db_password`
  (`db-password` = `var.postgres_administrator_password`). Única fuente de
  verdad: las mismas variables del módulo databases.
- `modules/keyvault/variables.tf`: nuevas variables
  `postgres_administrator_login` / `postgres_administrator_password`
  (sensitive).
- `envs/{dev,staging,prod}/main.tf`: el módulo `keyvault` recibe el par de
  credenciales desde las variables del entorno.
- `envs/{dev,staging,prod}/terraform.tfvars`: `postgres_databases` gana
  `"inventory"` (datasource de inventory-svc) y `"keycloak"` (DB de auth):
  `["orders", "payments", "catalog", "inventory", "keycloak"]`.

### Base (`cluster/base`)

- ConfigMaps de catalog/order/inventory/payment-svc: **sin** `jdbc:h2`. El H2
  sobrevive solo como fallback documentado en `application.yml` y los demo yml
  del config-service; el datasource lo inyecta el overlay.
- Nuevo `external-secrets/db-credentials.yaml`: ExternalSecret (ns ecommerce)
  que materializa `username` ← KV `db-username` / `password` ← KV
  `db-password`; registrado en `kustomization.yaml`.

### Overlays Azure (`dev`, `staging`, `prod`)

- 4 patches de datasource por overlay → `jdbc:postgresql://${DB_HOST}:${DB_PORT}/<db>`
  (catalog/orders/inventory/payments), driver `org.postgresql.Driver`,
  `SPRING_DATASOURCE_USERNAME/PASSWORD` desde el Secret `db-credentials`
  (secretKeyRef), `SPRING_JPA_HIBERNATE_DDL_AUTO=update`. `${DB_HOST}` /
  `${DB_PORT}` los provee el env-config (FQDN del Flexible Server + 5432).
- Patch `auth-keycloak-start.yaml` por overlay → `kc.sh start --import-realm
  --health-enabled=true --metrics-enabled=true --proxy-headers=xforwarded
  --db=postgres` + envs `KC_HOSTNAME=https://auth.<zone>`,
  `KC_DB_URL=jdbc:postgresql://psql-<env>-ecommerce-<suffix>...:5432/keycloak`
  (FQDN literal: Keycloak no resuelve `${}` de Spring) y
  `KC_DB_USERNAME/KC_DB_PASSWORD` desde `db-credentials`.

### Overlay local (`cluster/overlays/local`)

- **Reemplazo** de los 4 patches inline hacia el postgres del emulador
  (`host.docker.internal:37865`, DB compartida, credenciales `psqladmin`)
  por **sidecar `postgres:16-alpine` POR POD** con la DB propia del servicio
  (127.0.0.1:5432/catalog, /orders, /inventory, /payments) y credenciales desde
  el Secret `db-credentials`. Misma versión mayor que Azure (16) →
  comportamiento local == Azure.
- Nuevo Secret placeholder plano `db-credentials.yaml` (local no tiene Key
  Vault) y delete del ExternalSecret `db-credentials` del base.
- `env-config.yaml`: comentario actualizado — las keys `DB_HOST/DB_PORT` quedan
  por compatibilidad, los services con sidecar ya no las consumen.
- `host-sidecar-egress-policy.yaml`: recortada — el egress al host queda SOLO
  para Kafka (notification/checkout, puerto 9093); los 4 services con sidecar
  ya no salen al host por 37865.

### Keycloak local

Sin cambios: `start-dev` + H2 embebido (documentado). floci no tiene postgres
gestionado para auth; el fix 29 ya fijó metrics/health y el realm montado.

### Tests (`tests/manifests/test-kustomize-build.py`)

Nuevo check `check_phase6_parity_fina` (wired en `main()`):

- Contrato común: los 4 deployments llevan `SPRING_DATASOURCE_*` postgres con
  credenciales de `db-credentials`; ningún render contiene `jdbc:h2`; `start-dev`
  ausente en renders Azure.
- Local: sidecar `postgres:16-alpine` por pod con la DB propia + URL
  127.0.0.1; Secret `db-credentials` presente y ExternalSecret excluido; auth
  conserva `start-dev`.
- Azure: sin sidecar, URL `${DB_HOST}:${DB_PORT}/<db>`; `db-credentials` como
  ExternalSecret (sin Secret plano); auth con `kc.sh start` + args completos +
  `KC_HOSTNAME`/`KC_DB_URL` correctos por zone y credenciales de
  `db-credentials`.

## Archivos afectados

| Área | Archivos |
|------|----------|
| Terraform | `infra/terraform/modules/keyvault/{main,variables}.tf`, `infra/terraform/envs/{dev,staging,prod}/{main.tf,terraform.tfvars}` |
| Base | `services/{catalog,order,inventory,payment}-svc/k8s/base/configmap.yaml`, `cluster/base/external-secrets/db-credentials.yaml`, `cluster/base/kustomization.yaml` |
| Azure | `cluster/overlays/{dev,staging,prod}/patches/{catalog,order,inventory,payment}-svc-datasource.yaml`, `.../auth-keycloak-start.yaml`, `.../kustomization.yaml` |
| Local | `cluster/overlays/local/patches/{catalog,order,inventory,payment}-svc-postgres-sidecar.yaml`, `db-credentials.yaml`, `host-sidecar-egress-policy.yaml`, `env-config.yaml`, `kustomization.yaml` |
| Otros | `services/auth/k8s/overlays/prod/patches/deployment-patch.yaml` (comentario de réplicas/HA), `tests/manifests/test-kustomize-build.py`, `docs/fixes/README.md` (fila 30), `docs/PLAN.md` (Fase 6 ✅) |

## Validación (estática, sin clúster)

1. `kubectl kustomize cluster/overlays/{dev,staging,prod,local}` → OK (4/4
   renders limpios).
2. `python tests/manifests/test-kustomize-build.py` → **KUSTOMIZE BUILD CHECK
   PASSED** (43 targets; phase6 checks verdes en los 4 overlays wired).
3. Greps de contrato:
   - `jdbc:h2` → solo en comentarios de convención (0 valores reales); renders:
     0 ocurrencias.
   - `start-dev` → únicamente auth base + overlay local (diseño) y un comentario
     en `services/auth/k8s/overlays/prod/patches/deployment-patch.yaml`
     (actualizado a fix 30).
   - `psqladmin` / `<fuerte-sin-@>` → 0 (credenciales legacy del emulador
     eliminadas).
   - `37865` → solo la key de compatibilidad `DB_PORT` de `env-config.yaml`
      (sin consumidores reales: los 4 servicios con sidecar usan
      `SPRING_DATASOURCE_URL` explícito con precedencia `env` > `envFrom`).
   - `random_password "db"` → 0.
4. `terraform validate` → no ejecutable sin `init` (backend remoto azurerm; la
   validación de HCL es por lectura, igual que fix 29).

## Notas y riesgos (pendientes del usuario)

1. **Validación LIVE (checklist)** — requiere az login + terraform apply (este
   entorno no aplica):
   - Sustituir `<suffix>` (tppractico01) en los env-configs, patches de store,
     `KC_DB_URL` y `postgres_databases` ya ampliado.
   - `postgres_administrator_login/password` REALES en los 3 tfvars (hoy
     `pgadmin` / `<STRONG-PASSWORD>`).
   - Aplicar terraform (crea `inventory` + `keycloak`, siembra db-username/
     db-password en KV) y luego Argo CD para que ESO materialice
     `db-credentials`.
   - Verificar el login de Keycloak (postura productiva, /realms) y un ciclo
     SSO; confirmar que las 4 services arrancan con Hikari contra Postgres
     (logs sin connection refused) y que `ddl-auto=update` creó los schemas.
2. **payment-svc**: el datasource ya apunta a Postgres (el arranque no cae) pero
   el store Sigue siendo `PaymentService` en memoria (sin JPA aún). Implementar
   el repositorio JPA es un cambio de producto fuera de este fix.
3. **HA de Keycloak**: `kc.sh start` sin `--cache-stack=kubernetes` no escala
   real con >1 réplica (cache embebida); quedó en 2 réplicas y documentado en el
   overlay prod de auth. Postgres ya está provisionado para cuando aterrice.
4. **Keycloak 25+**: si la imagen sube a 25+, /metrics y /health migran al
   puerto de management (9000) — migrar ServiceMonitor y probes juntos (ya
   anotado en PLAN Fase 6).