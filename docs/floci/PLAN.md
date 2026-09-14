# Plan de implementación — Floci-AZ local y Azure real

Plan de implementación del stack de microservicios e-commerce sobre **Floci-AZ** con doble
destino: levantar todo el sistema **localmente** emulando cómo sería en Azure, y poder
desplegar el **mismo código** en **Azure real** sin cambios de arquitectura.

> Este plan orquesta dos caminos que comparten la misma infraestructura-as-código
> (`infra/terraform/` y `cluster/`):
>
> - **Camino A — Local (Floci-AZ)**: emulación completa de Azure en tu máquina, con Terraform
>   `azurerm` contra el emulador y Kubernetes real (k3s) aprovisionado por floci.
> - **Camino B — Nube real (Azure)**: el mismo proyecto de Terraform aplicado a una
>   suscripción real (`envs/dev`, `envs/staging`, `envs/prod`).
>
> El detalle táctico del Camino A está en [`plan-implementacion.md`](plan-implementacion.md);
> aquí se resumen sus fases para navegar el plan completo con las referencias a los docs de
> floci. El Camino B amplía lo que documenta [`../infra/terraform/README.md`](../../infra/terraform/README.md)
> con las decisiones de despliegue del clúster.

---

## 1. Objetivo y alcance

### 1.1 Objetivo

Entregar un procedimiento reproducible que, partiendo de un repo limpio y Docker Desktop en
marcha, permita ejecutar el stack completo de microservicios en cualquiera de los dos
destinos y validarlo de punta a punta:

| Destino | Descripción | Costo |
| --- | --- | --- |
| **A — Local (Floci-AZ)** | Todo en tu máquina: emulador de Azure + registry + k3s + bases de datos + 18 imágenes | Gratis (recursos CPU/RAM/Docker) |
| **B — Azure real** | Infraestructura real en tu suscripción, mínima para costar casi nada (dev) | Pocos euros/día en `dev` |

La clave de diseño es que **un solo proyecto de Terraform y un solo árbol de Kustomize**
sirven a ambos destinos; lo que cambia es el *environment* y la configuración del
proveedor/clúster, no el código.

### 1.2 Qué cubre este plan

- Levantar floci-az (native image, `<100 ms` de arranque) con TLS, storage híbrido y los
  servicios necesarios ([`getting-started/installation.md`](getting-started/installation.md)).
- Aprovisionar con Terraform (`azurerm ~> 4.0`) los equivalentes de AKS, ACR, PostgreSQL,
  Redis, Event Hubs y Key Vault — contra floci en local, contra Azure real en nube.
- Construir y publicar las **18 imágenes** (16 microservicios Java + `auth` + `frontend`)
  en el registry del destino con tag por UID y cero `:latest`.
- Desplegar el clúster con Argo CD + Kustomize, activar los switches latentes (Redis real,
  Kafka real, Key Vault) y validar el flujo de checkout end-to-end.
- Limpiar el entorno local sin dejar estado residual.

### 1.3 Qué se descarta (intencional)

Mismo criterio que [`plan-implementacion.md`](plan-implementacion.md#13-qué-se-descarta-intencional-y-por-qué):
alta disponibilidad/DR, políticas de red granulares, RBAC de Entra ID real, DNS público y
endurecimiento de producción. En `staging`/`prod` (Camino B) se aplica la configuración que
ya traen esas carpetas; el plan se enfoca en `dev` como destino real.

---

## 2. Cómo funciona Floci-AZ (de los docs)

Floci-AZ es un emulador de Azure **de plano de control y datos** en una sola imagen nativa.
Puntos que fundamentan todo el plan:

- **Un único puerto HTTP (`4577`)** para todos los servicios REST; el **`4578` (HTTPS)** es
  solo para el SDK de Java de Cosmos DB. El enrutamiento es por ruta de URL
  (`/{account}`, `/{account}-queue`, `/{account}-keyvault`, rutas ARM
  `/subscriptions/...`). Ver [`configuration/ports.md`](configuration/ports.md).
- **TLS obligatorio para el proveedor de Terraform**: el SDK de Azure exige HTTPS para el
  plano de control, así que floci se inicia con `FLOCI_AZ_TLS_ENABLED=true` y expone un
  certificado auto-firmado en `GET /_floci/tls-cert` que hay que confiar en el store local.
  Ver [`terraform.md`](terraform.md).
- **Los servicios pesados son contenedores sidecar reales** creados por floci a través del
  socket de Docker, no proxies: AKS → `rancher/k3s` (puerto 6443–7443), Event Hubs →
  `redpanda` (Kafka, 9093) y `activemq-artemis` (AMQP, 5672), Cosmos PostgreSQL → `postgres`
  (5432). Ver [`services/aks.md`](services/aks.md) y [`configuration/docker-compose.md`](configuration/docker-compose.md).
- **ACR emulado** sobre un registry compartido `registry:2` → loginServer tipo
  `localhost:5000/myregistry`. Ver [`services/acr.md`](services/acr.md).
- **Modos de almacenamiento** `memory` / `persistent` / `hybrid` / `wal`; el plan usa
  `hybrid` (flush asíncrono cada 5 s) con persistencia en `./data`. Ver
  [`configuration/storage.md`](configuration/storage.md).
- **`FLOCI_AZ_AUTH_MODE=dev`** acepta cualquier credencial; no es necesario firmar peticiones.
  Las SDKs y la CLI se configuran con la **cadena de conexión de desarrollo estándar**
  (`devstoreaccount1`). Ver [`getting-started/azure-setup.md`](getting-started/azure-setup.md).
- **Compatibilidad**: SDKs oficiales de Azure, CLI (`az` / wrapper `azfloci`) y Terraform
  `azurerm`. Ver [`README.md`](README.md).

> **Nota**: cada ajuste `floci-az.*` del `application.yml` tiene su variable de entorno
> `FLOCI_AZ_*` (`.` → `_`, mayúsculas). Ver [`configuration/advanced/application-yml.md`](configuration/advanced/application-yml.md).

---

## 3. Arquitectura dual-target

```
                    ┌───────────────────────────────────────────────┐
                    │           Código único (repositorio)           │
                    │   infra/terraform (módulos × envs)            │
                    │   cluster/ (base + overlays)                  │
                    │   services/ (18 imágenes)                     │
                    └───────────────┬───────────────────────────────┘
                                    │ provider azurerm + kubeconfig
                    ┌───────────────▼───────────────┬──────────────────────────────┐
        CAMINO A (local)                          CAMINO B (nube real)
  floci-az 4577 TLS                 │      az login → suscripción real
  ──────────────────                │      ──────────────────────────────
  Terraform → floci (ARM)           │      Terraform → Azure (ARM real)
  envs/local  (a crear)             │      envs/dev · staging · prod
  k3s real (AKS emulado)            │      AKS real
  registry:2 :5000 (ACR emulado)    │      ACR real
  Postgres real / valkey / redpanda │      Azure PostgreSQL / Redis
                                     │      Event Hubs (Kafka-compatible)
  Key Vault emulado + secretos      │      Key Vault real
  overlay: cluster/overlays/local   │      overlay: cluster/overlays/{env}
                    └───────────────────────────────┴──────────────────────────────┘
```

**Decisiones de diseño** (todas las piezas nuevas se anotan como "(a crear)" y se detallan en
el Camino A):

| Pieza | Local (A) | Nube (B) |
| --- | --- | --- |
| `terraform` provider | `azurerm` `environment=stack` + `metadata_host=localhost:4577`, credenciales fake | `azurerm` → Azure real + `az login` |
| Workspace Terraform | `infra/terraform/envs/local` (a crear) | `envs/dev` (existente) |
| Estado de Terraform | Local `terraform.tfstate` (sin `backend.tf`) | Azure Storage remoto (`backend.tf`) |
| Registry | `localhost:5000/localecommercefloci01` | ACR `tfecommerce01` (o el suffix del env) |
| Kubernetes | k3s real provisto por floci | AKS (tier Free en dev) |
| Overlay Kustomize | `cluster/overlays/local` (a crear) | `cluster/overlays/dev` (existente) |
| Secrets | Key Vault emulado (o fallback) | Azure Key Vault + ESO |
| DNS/ingress | `*.127.0.0.1.nip.io` | Ingress `external-dns` (opcional) |

---

## 4. Mapa de equivalencias Azure → Floci-AZ → local

Tabla de mapeo completa (síntesis de `plan-implementacion.md` §3 — ver
[`plan-implementacion.md#3-mapa-de-mapeo-azure--floci--local`](plan-implementacion.md#3-mapa-de-mapeo-azure--floci--local)).

| Recurso Azure (prod) | Emulador floci | Real local (Camino A) | Nube real (Camino B) |
| --- | --- | --- | --- |
| AKS | `managedClusters` ARM + k3s sidecar | `rancher/k3s:latest`, API 6443–7443 | AKS Free tier (dev) |
| ACR | `registries` ARM → `registry:2` | `localhost:5000/{registry}`, admin on | ACR Basic, admin on (dev) |
| PostgreSQL | `servers/{name}` (control) + datos | `postgres:17-alpine`, 3 DBs: `orders`, `payments`, `catalog` | Azure PostgreSQL Flexible `B_Standard_B1s`, DBs idénticas |
| Redis | no emulado de datos → contenedor real | `valkey/valkey:8-alpine`, 6379 sin TLS | Azure Cache for Redis Basic |
| Event Hubs | `namespaces` + sidecar | `redpanda:9093` (Kafka), `artemis:5672` (AMQP), topics `order-events`, `payment-events` | Event Hubs Standard (Kafka-compatible) |
| Key Vault | emulado en 4577 `/{account}-keyvault/` | emulado, secretos sembrados vía PUT | Key Vault real + ESO |
| Storage (Blob/Table/Queue) | emulado `/{account}*` con `devstoreaccount1` | emulado (solo si algún servicio lo usa) | Storage account real |

> **Desvíos locales documentados** (ver
> [`plan-implementacion.md#33-desvíos-necesarios-resumen-detalle-en-cada-fase`](plan-implementacion.md#33-desvíos-necesarios-resumen-detalle-en-cada-fase)):
> sslmode/`enable_non_ssl_port` para Redis/Postgres, mirror insecure de registries en k3s,
> credenciales fake de ARM, y el bootstrap de Event Hubs por variables de entorno en vez del
> módulo de Terraform.

---

## 5. Requisitos previos

Comunes a ambos caminos:

- Docker Desktop en marcha (`docker info` OK). El engine puede estar apagado: arrancar y
  esperar al daemon (**Fase 0.1** de `plan-implementacion.md`).
- Windows (PowerShell) o Linux; los comandos de este plan mezclan `.ps1` (recomendado) y
  `.sh` equivalentes.
- Java/Maven toolchain para buildar los 16 servicios Java (+ `auth`), y Node para `frontend`.
- Kubectl + Kustomize + Helm (ver alternativas winget/choco en **Fase 0.3**).

Solo Camino A (local):

- Trust del certificado TLS de floci en `CurrentUser\Root` (**crítico** para Terraform;
  **Fase 0.4** de `plan-implementacion.md`).

Solo Camino B (nube):

- Azure CLI (`az` >= 2.50) logueado y una suscripción con permisos para crear recursos.
- Decidir el suffix único `dev` (p. ej. `tppractico01`) y el storage account del estado.

---

## 6. Camino A — Local con Floci-AZ

Ejecuta las **Fases 0–6 de [`plan-implementacion.md`](plan-implementacion.md)** en orden.
Resumen de navegación y decisiones clave:

### Fase 0 — Prerrequisitos
- Docker Desktop encendido; toolchain Java/Maven/Node; Helm.
- **Trust TLS**: `Import-Certificate "$env:TEMP\floci-az.crt" -CertStoreLocation 'Cert:\CurrentUser\Root'` y abrir terminal nueva.

### Fase 1 — Levantar Floci-AZ
- **Compose local (a crear): `docs/floci/docker-compose.yml`** — imagen `floci/floci-az:latest`,
  puertos `4577` y `4578`, TLS habilitado, storage `hybrid` con `./data:/app/data`, rango
  `6443–6450` publicado, socket de Docker montado, `FLOCI_AZ_SERVICES_EVENT_HUB_ENABLED=true`
  para los topics.
- Verificar `GET http://localhost:4577/health` → `200` y la ruta TLS
  `GET https://localhost:4577/metadata/endpoints`.
- Ver [`configuration/docker-compose.md`](configuration/docker-compose.md) y
  [`terraform.md`](terraform.md#1--iniciar-floci-az-con-tls-habilitado).

### Fase 2 — Terraform contra el emulador
- **Workspace (a crear): `infra/terraform/envs/local`** — reusa los módulos
  `modules/{networking,registry,aks,databases,streaming,dns}`; **sin `backend.tf`** (estado
  local); provider `azurerm` con `environment = "stack"`, `metadata_host = "localhost:4577"`,
  `skip_provider_registration = true` y credenciales fake (`00000000-...`, no validadas en
  auth `dev`); AKS sin Entra RBAC, un solo pool, sin role assignment; ACR admin on;
  PostgreSQL público con firewall `0.0.0.0`; Redis `enable_non_ssl_port`; DNS off; suffix
  `localdemo01` (o similar).
- `terraform init` + `terraform plan` + `terraform apply` idempotente.
- **Verificación**: nodo k3s `Ready` vía kubeconfig (`listClusterAdminCredential`),
  `psql` y `redis-cli PING`, 3 DBs creadas, secretos en el Key Vault emulado.
- Event Hubs: topics `order-events`/`payment-events` vía env del compose (**desvío 2**).
- Ver [`services/aks.md`](services/aks.md), [`services/acr.md`](services/acr.md),
  [`services/event-hub.md`](services/event-hub.md), [`services/key-vault.md`](services/key-vault.md).

### Fase 3 — Imágenes y registry (ACR emulado)
- LoginServer del terraform → `localhost:5000/localecommercefloci01`.
- Build + push de las 18 imágenes con **tag UID** (SHA corto), **cero `:latest`** (política
  Kyverno `disallow-latest-tag`).
- **Mirror insecure** en k3s para que el clúster resuelva el registry (desvío 6).
- Impacto en `tests/manifests/test-service-contract.py` con `CONTRACT_IMAGE_PREFIX`.

### Fase 4 — Despliegue al clúster emulado
- **Overlay (a crear): `cluster/overlays/local`** sobre `cluster/base` (Argo CD, ESO,
  Kyverno, ingress-nginx, cert-manager, KEDA, monitoring, namespaces).
- Orden: Argo CD primero (bootstrap manual), luego el resto del base; después la aplicación
  con **Spring Cloud primero** (config-service, discovery-service).
- **Switches latentes**: activar Redis real (valkey), Kafka real (redpanda) y Key Vault
  emulado en los servicios; ESO → Key Vault emulado con fallback documentado si no es viable.
- PSA `restricted` + Kyverno activos.

### Fase 5 — Verificación end-to-end
- Plataforma sana (pods Ready), BFF sirviendo catálogo desde Postgres emulado real.
- **Checkout completo**: checkout-svc → order-svc → payment-svc → evento Kafka →
  consumer notification-svc.
- Frontend en `www.127.0.0.1.nip.io` sin tocar `/etc/hosts`.
- **Prueba negativa**: un manifest `:latest` debe ser rechazado por Kyverno.

### Fase 6 — Opcionales de paridad y limpieza
- Opcionales: `az` CLI smoke tests, paridad de config server.
- **Limpieza**: `docker compose down` (sin `-v`), `/_admin/reset` → `204` entre demos, y
  borrar `docs/floci/data/`.

---

## 7. Camino B — Nube real (Azure)

Sigue el quick start de [`infra/terraform/README.md`](../../infra/terraform/README.md) y
amplíalo con las fases de despliegue del clúster. Se trabaja sobre `envs/dev` (el entorno
más barato con el que se puede validar todo).

### Fase B0 — Bootstrap de cuenta y estado
```powershell
az login
az account set --subscription "<your-subscription-id>"

# Storage account + container del estado remoto (una sola vez por env):
az group create --name tfstate-rg --location westeurope
az storage account create -n tfecommerce01 -g tfstate-rg --sku Standard_LRS --allow-blob-public-access false
az storage container create -n tfstate --account-name tfecommerce01 --auth-mode login
```

### Fase B1 — Terraform (envs/dev)
```powershell
cd infra/terraform/envs/dev
terraform init `
  -backend-config="storage_account_name=tfecommerce01" `
  -backend-config="container_name=tfstate" `
  -backend-config="key=dev/ecommerce.terraform.tfstate" `
  -backend-config="access_key=<storage-account-access-key>"
```
- Completa `terraform.tfvars`: suffix único, `postgres_administrator_login/password` (sin
  `'@'`), y `admin_group_object_ids` o `enable_aad = false` para cuentas locales mientras
  aprendes. Nunca commitees `*.tfvars`.
- Revisa y aplica: `terraform plan` → `terraform apply`.
- Verificación: AKS creado, ACR con admin on (dev), 3 DBs PostgreSQL, Redis Basic, Event
  Hubs con `order-events` y `payment-events`, Key Vault.

> **Nota**: en `dev`, PostgreSQL/Redis son públicos con firewall por conveniencia. Para
> staging/prod el módulo ya cambia a privados.

### Fase B2 — Imágenes al ACR
```powershell
az acr login --name tfecommerce01
docker tag <image> tfecommerce01.azurecr.io/localecommercefloci01:<uid>
docker push tfecommerce01.azurecr.io/localecommercefloci01:<uid>
```
- Idéntico criterio de tagging que el Camino A: tag por UID, cero `:latest`.

### Fase B3 — Despliegue del clúster (overlay dev)
```powershell
az aks get-credentials -g rg-ecommerce-dev -n aksecommerce-dev-tppractico01
kubectl config set-context --current --namespace=argocd
scripts/deploy/bootstrap-argocd.ps1          # instala Argo CD + crea la app ecommerce-dev
scripts/deploy/sync-argocd-app.ps1 -app ecommerce-dev
kubectl get application dev -n argocd        # → Synced / Healthy
```
- El overlay `dev` ya incluye `env-config.yaml`, `issuer-default.yaml` y el patch del
  `secret-store`; completa el ClusterSecretStore con los secretos reales del Key Vault
  (ESO) antes de sincronizar.

### Fase B4 — Validación y costos
- Mismo e2e que en local: catálogo → checkout → orden → pago → evento → notificación.
- Valida el ingress (o via `kubectl port-forward`).
- **Costos**: solo `dev` (AKS Free, B1s) son pocos euros/día; detén el node pool o ejecuta
  `terraform destroy` desde la misma carpeta cuando no haya laboratorio. Ver
  [`infra/terraform/README.md`](../../infra/terraform/README.md#6-notas-de-costo).

---

## 8. Criterios de "listo"

Referencia completa por fase: [`plan-implementacion.md#7-criterios-de-listo`](plan-implementacion.md#7-criterios-de-listo).
Resumen por destino:

**Camino A (local):**
- `GET /health` → 200 y `/_admin/reset` → 204.
- `terraform apply` idempotente; nodo k3s `Ready`; `psql … SELECT 1` y `redis-cli PING` OK;
  3 DBs; secretos en KV; topics `order-events`/`payment-events`.
- 18 imágenes en `localhost:5000/localecommercefloci01` con tag UID, cero `:latest`; mirror
  insecure funcional; contract tests verdes.
- 100% pods `Running`/`Ready`; switches latentes activos; ESO resuelto o fallback
  documentado.
- Checkout e2e con notificación consumida; frontend en `www.127.0.0.1.nip.io`.
- Limpieza sin estado residual.

**Camino B (nube):**
- `terraform apply` exitoso e idempotente (estado remoto); recursos creados verificables vía
  `az` (AKS Running, ACR, PG, Redis, Event Hubs, KV).
- Kubeconfig de AKS recuperado; overlay `dev` `Synced`/`Healthy` en Argo CD.
- Mismo e2e de checkout; sin credenciales commiteadas; `destroy` documentado y ejecutable.

---

## 9. Riesgos y mitigaciones

| # | Riesgo | Mitigación | Camino |
| --- | --- | --- | --- |
| R1 | TLS del emulador no confiado → provider de Terraform falla | Trust del cert en `CurrentUser\Root` y terminal nueva (Fase 0.4) | A |
| R2 | `:latest` en imágenes | Tag UID por build + Kyverno `disallow-latest-tag` (Enforce) | A·B |
| R3 | k3s no resuelve `localhost:5000` | Mirror insecure de registries en k3s (desvío 6) | A |
| R4 | ESO no opera contra el Key Vault emulado | Evaluación temprana (Fase 4.4.1) + fallback de secretos documentado | A |
| R5 | Estado residual del emulador entre demos | `/_admin/reset`, `docker compose down` sin `-v`, borrar `docs/floci/data/` | A |
| R6 | JSON de ARM estricto (validación de fechas/IDs) | Fijar `api_version` y shape de body en los desvíos ARM | A |
| R7 | Typos en `*.tfvars` (passwords rechazados, suffix duplicado) | Checklist de `terraform.tfvars` + `dns_prefix` único con suffix | B |
| R8 | Backend no encontrado en `terraform init` | Crear storage account + container ANTES del init | B |
| R9 | Factura de Azure por recursos olvidados | Node pool a 0 o `terraform destroy`; costos ~pocos euros/día en dev | B |
| R10 | `dns_prefix` globalmente en uso | Incluir el suffix por estudiante (`aksecommerce-dev-<suffix>`) | B |

---

## 10. Roadmap recomendado

| Paso | Destino | Entregable |
| --- | --- | --- |
| 1 | A | Emulador arriba con TLS + storage híbrido (Fase 0–1) |
| 2 | A | `envs/local` de Terraform aplicado y verificado (Fase 2) |
| 3 | A | 18 imágenes publicadas + registry/mirror (Fase 3) |
| 4 | A | `overlays/local` desplegando la app con switches latentes (Fase 4) |
| 5 | A | Checkout e2e + pruebas negativas (Fase 5) |
| 6 | B | `envs/dev` aplicado en Azure + estado remoto (Fases B0–B1) |
| 7 | B | Imágenes en ACR + overlay dev + Argo CD (Fases B2–B3) |
| 8 | B | Validación e2e y `terraform destroy` (Fase B4) |

> Empieza por el **Camino A**: valida el flujo completo gratis y sin fricción, y reutiliza
> los mismos pasos (build, tagging, overlay) en el Camino B — solo cambia el destino.

---

## 11. Fuentes y documentación relacionada

- [`README.md`](README.md) — qué es Floci-AZ y servicios compatibles.
- [`index.md`](index.md) — índice de documentación.
- [`terraform.md`](terraform.md) — TLS + configuración del provider `azurerm`.
- [`getting-started/installation.md`](getting-started/installation.md) — imagen, etiquetas, build.
- [`getting-started/azure-setup.md`](getting-started/azure-setup.md) — cadenas de conexión y CLI.
- [`configuration/ports.md`](configuration/ports.md) — puertos y sidecars.
- [`configuration/storage.md`](configuration/storage.md) — modos de almacenamiento.
- [`configuration/docker-compose.md`](configuration/docker-compose.md) — compose y variables.
- [`configuration/advanced/application-yml.md`](configuration/advanced/application-yml.md) — aplicación Quarkus.
- Servicios: [`services/aks.md`](services/aks.md) · [`services/acr.md`](services/acr.md) ·
  [`services/postgresql.md`](services/postgresql.md) · [`services/redis.md`](services/redis.md) ·
  [`services/event-hub.md`](services/event-hub.md) · [`services/key-vault.md`](services/key-vault.md).
- [`plan-implementacion.md`](plan-implementacion.md) — plan táctico detallado del Camino A (fases, desvíos, checklist, fuentes y "por verificar").
- [`../infra/terraform/README.md`](../../infra/terraform/README.md) — quick start de Terraform (Azure real) y notas de costo/seguridad.
- `scripts/deploy/` — `bootstrap-argocd.ps1/.sh` y `sync-argocd-app.ps1/.sh` para el despliegue GitOps.
