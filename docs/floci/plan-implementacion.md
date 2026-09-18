# Plan de implementación — Emulación local de Azure con Floci-AZ

> **Estado**: documento de planificación (Fase 0). Solo describe; ninguna
> ejecución fue realizada. Todo comando marcado como «ejecutar» forma parte de
> la ejecución futura del plan, no de este documento.
>
> **Convención**: prosa en español neutro-profesional; comandos, código y
> tablas en inglés donde es natural. Cada afirmación clave cita su fuente en
> [§8](#8-fuentes-y-referencias).

---

## 1. Objetivo y alcance

### 1.1 Objetivo

Replicar **localmente** el despliegue de producción de la plataforma
e-commerce — lo más cerca posible de la realidad — usando **Floci-AZ** como
emulador de Azure. El resultado operativo es un entorno donde un
estudiante/operador puede:

- aprovisionar la infraestructura con **Terraform** (`hashicorp/azurerm`) contra
  el emulador, reutilizando los módulos reales de `infra/terraform/`;
- desplegar la plataforma completa (18 imágenes) sobre un **clúster k3s real**
  (el «AKS emulado»), con Kustomize;
- activar los switches latentes para que el comportamiento de datos sea el de
  producción (Redis real, Kafka real, Key Vault emulado);
- recorrer el flujo end-to-end: catálogo → checkout → orden → pago → evento
  Kafka → notificación.

### 1.2 Qué se emula (paridad con prod)

| Superficie Azure de prod | Emulación local |
|---|---|
| AKS (cluster + node pools) | `rancher/k3s` real por clúster, API en 6443-7443, kubeconfig con CA real (`kubectl` conecta directo) |
| ACR | `registry:2` compartido (data plane real en `localhost:5000/{registry}`) |
| Azure Database for PostgreSQL Flexible | `postgres:17-alpine` real por servidor (DBs `orders`, `payments`, `catalog`) |
| Azure Cache for Redis | `valkey/valkey:8-alpine` real (6379, sin TLS) |
| Event Hubs (endpoint Kafka) | Redpanda (Kafka 9093) + Artemis (AMQP 5672), opt-in por env |
| Azure Key Vault | Servicio Key Vault emulado (`/{account}-keyvault`, HTTPS 4577) |
| External Secrets → Key Vault | ClusterSecretStore apuntando al emulador **(evaluar, ver Fase 4)** |

### 1.3 Qué se descarta (intencional) y por qué

| Superficie | Motivo |
|---|---|
| Azure DNS público (external-dns → zona pública) | Floci no emula `Microsoft.Network/dnsZones` públicas (solo Private DNS Zones verificada en compat-tests); el acceso local se resuelve con `nip.io` o `hosts`. |
| Azure Monitor | La observabilidad del repo es kube-prometheus-stack + OTel in-cluster, sin participación de Azure Monitor (ADR-0008). |
| RBAC Entra ID sobre AKS (`enable_aad`) | Floci emula Entra OIDC para tokens, pero el azurerm envía el bloque `azureActiveDirectory` al ARM emulado; el k3s real no puede validar grupos. Se usa `enable_aad=false` (cuentas locales) — el mismo modo que el repo ya documenta para aprendizaje. |
| Backend de estado remoto (`tfstate` en Azure Storage) | El emulador no sirve de backend; se usa estado local (`envs/local`), alternativa ya documentada en `infra/terraform/README.md`. |
| Azure SQL / Cosmos / Functions / Blob / Queue / Table | No forman parte de la superficie de la plataforma (solo PostgreSQL/Redis/EventHubs/KeyVault/ACR/AKS). Se deshabilitan los servicios no usados para ahorrar recursos. |
| Entra ID como IdP de la app | El auth real de la plataforma es **Keycloak in-cluster** (ADR-0005); Entra emulado queda como opcional de paridad (Fase 6). |

### 1.4 Principios

- **No inventar comportamiento**: todo lo del emulador está verificado contra
  las docs del repo clonado o marcado **«por verificar»** con la ruta exacta.
- **Fidelidad > atajo**: se prefiere la ruta que ejercita el mismo código
  (Terraform real, Kustomize real, mismos env vars) aunque sea más trabajo.
- **Desvíos documentados**: cada diferencia con prod se registra en [§3.3](#33-desvíos-necesarios).

---

## 2. Decisión de topología

### 2.1 Opción A (RECOMENDADA) — Floci full-stack

El emulador provee **todo** el plano de datos gestionado: el clúster AKS
emulado (k3s REAL), el ACR emulado, PostgreSQL emulado, Redis emulado y Event
Hubs emulado. Terraform `azurerm` apunta a Floci y provisiona el entorno
replicando `infra/terraform/` lo más fielmente posible; la plataforma se
despliega con Kustomize sobre el clúster emulado.

**Justificación técnica**:

1. **Se prueba el IaC real.** El mismo `azurerm` que corre contra Azure corre
   contra el emulador (compat-tests CI-verificados de Floci:
   `compatibility-tests/compat-terraform/`). Es la única opción que valida los
   módulos de `infra/terraform/` sin gastar Azure.
2. **Misma ruta de datos.** Los consumidores (servicios) hablan los mismos
   wire protocols: JDBC a PostgreSQL, RESP a Redis, Kafka a Event Hubs. Si una
   versión futura cambia el proveedor de datos, la emulación detecta el
   problema localmente.
3. **Misma forma de operar.** `kubectl` con kubeconfig real, `docker push` a un
   loginServer, `terraform destroy` para limpiar: los hábitos operativos son
   transferibles a Azure.
4. **Costo de recursos aceptable**: Floci es un binario nativo Quarkus
   (arranque en milisegundos, memoria baja); los sidecars son los estándar
   (postgres-alpine, valkey-alpine, registry:2, k3s).

### 2.2 Opción B (fallback ligero) — kind/minikube + datos in-cluster

Clúster local kind/minikube + las alternativas self-hosted de `data/`
(CloudNativePG, Bitnami Redis, Strimzi Kafka) + Floci solo para los servicios
donde aporta (Key Vault, Entra, ACR).

**Cuándo usar B**: si Floci no puede correr en Docker Desktop (p. ej. el
contenedor k3s privilegiado falla en WSL2 — ver [§6 riesgo R2](#r2-k3s-privilegiado-en-docker-desktop-wsl2)),
o para smoke tests rápidos donde Terraform no es el foco.

### 2.3 Matriz de comparación

| Criterio | Opción A (Floci full-stack) | Opción B (kind + data/) |
|---|---|---|
| **Fidelidad del control plane** | Alta — Terraform azurerm contra ARM emulado (plan/apply/destroy) | Baja — sin ARM; se despliega data/ directo con helm/kubectl |
| **Fidelidad del data plane** | Alta — mismos wire protocols con contenedores reales | Alta — CNPG/Redis/Strimzi también son reales |
| **Fidelidad de operación** | Alta — kubeconfig real, loginServer path-style, destroy por terraform | Media — cambia registry (local), sin terraform |
| **Qué tests se mantienen** | `tests/manifests/*` (con ajuste de prefix en Fase 3), contract test, kustomize build | Igual, pero no se ejercita el módulo terraform ni el flujo SDK→emulador |
| **Esfuerzo** | Medio-alto (6 fases) | Bajo (1-2 días) |
| **Riesgo de bloqueo** | k3s privilegiado en Docker Desktop; algunos recursos ARM no emulados | Muy bajo (tecnología estándar local) |
| **Costo** | Solo recursos locales | Solo recursos locales |

### 2.4 Criterio de degradación A → B

- Si la **Fase 2** no logra un clúster k3s `Succeeded` en modo real (error del
  sidecar privilegiado), degradar a **B** manteniendo Floci solo para
  KeyVault/ACR si aportan.
- Si la **Fase 4** se bloquea en ESO→KeyVault emulado (riesgo R5), el fallback
  NO es degradar a B sino usar **secrets planos** dentro de A (ver Fase 4.4).

---

## 3. Mapa de mapeo Azure → Floci → local

### 3.1 Tabla de recursos

| # | Recurso Azure (prod) | Equivalente Floci (endpoint) | Consumidores en el repo | Desvío |
|---|---|---|---|---|
| 1 | Resource group + VNet + subnet + NSG | ARM `Microsoft.Resources`/`Microsoft.Network` en `localhost:4577/subscriptions/...` | `infra/terraform/modules/networking` | Ninguno (compat-test provisiona VNet/subnet/NIC/NSG/LB) |
| 2 | AKS cluster + node pool | ARM `Microsoft.ContainerService/managedClusters` → contenedor `rancher/k3s` real; API en 6443 | `modules/aks`, `cluster/`, `kubectl` | `enable_aad=false`; `acr_id=null` (no hay `Microsoft.Authorization`) |
| 3 | ACR | ARM en 4577 + data plane `localhost:5000/{registryName}` | `modules/registry`; imágenes `acr.azurecr.io/<svc>` en k8s | **loginServer path-style** (desvío central, Fase 3) |
| 4 | PostgreSQL Flexible + DBs `orders/payments/catalog` + firewall | ARM `Microsoft.DBforPostgreSQL/flexibleServers` → `postgres:17-alpine`; DBs **metadata-only** | `modules/databases`; `DB_HOST/DB_PORT/DB_NAME` en `ecommerce-env-config` | DBs reales a crear con `psql` (Fase 2.6); `sslmode=disable` |
| 5 | Azure Cache for Redis | ARM `Microsoft.Cache/redis` → `valkey:8-alpine` 6379 (password = access key) | `modules/databases`; cart-svc + gateway rate limiter | Sin TLS en data plane (solo 6379) |
| 6 | Event Hubs namespace + `order-events`/`payment-events` | API nativa `PUT /{account}-eventhub/namespaces/{ns}` + Redpanda Kafka 9093 | `modules/streaming`; notification-svc consumer | **No terraformable** (sin ruta ARM `Microsoft.EventHub`) → bootstrap manual (Fase 2.5) |
| 7 | Azure Key Vault (`db-password`, `keycloak-admin-*`, SASL) | `/{account}-keyvault` en 4577 (HTTPS) | external-secrets `ClusterSecretStore` (base + patches) | ESO→emulador requiere cert root + evaluación (Fase 4.4) |
| 8 | Entra ID (opcional, no usado por la app) | `/{tenant}/oauth2/v2.0/token`, OIDC RS256 + JWKS | — (auth real = Keycloak) | Opcional Fase 6 |
| 9 | Azure DNS pública (external-dns) | — no emulado | `cluster/base/external-dns/` | Descartado; `nip.io` o `hosts` (Fase 4) |

### 3.2 Endpoints relevantes del emulador (todos sobre localhost:4577 salvo nota)

| Servicio | Ruta | Fuente |
|---|---|---|
| Health | `GET /health` o `GET /_floci/health` | `docs/configuration/docker-compose.md` |
| Reset | `POST /_admin/reset` → 204 | código `core/AdminResetTest.java` |
| Cert TLS | `GET /_floci/tls-cert` | `docs/terraform.md` |
| AKS (ARM) | `/subscriptions/{s}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}` + `POST .../listClusterAdminCredential` | `docs/services/aks.md` |
| ACR (ARM) | `/subscriptions/.../Microsoft.ContainerRegistry/registries/{name}`; data plane `localhost:5000` (rango 5000-5099) | `docs/services/acr.md` |
| PostgreSQL (ARM) | `/subscriptions/.../Microsoft.DBforPostgreSQL/flexibleServers/{name}`; conveniencia `GET /{account}-postgres/flexibleServers/{name}/connect` | `docs/services/postgresql.md` |
| Redis (ARM) | `/subscriptions/.../Microsoft.Cache/redis/{name}` + `POST .../listKeys` | `docs/services/redis.md` |
| Event Hubs (nativo) | `PUT/GET/DELETE /{account}-eventhub/namespaces/{ns}`; `GET .../namespaces/{ns}/connection`; `GET /{account}-eventhub/health` | `docs/services/event-hub.md` |
| Key Vault | `/{account}-keyvault/secrets/...` (`PUT/GET/DELETE`, versionado) | `docs/services/key-vault.md` |
| Entra | `/{tenant}/oauth2/v2.0/token`, `/{tenant}/.well-known/openid-configuration`, `/discovery/v2.0/keys` | `docs/services/entra.md` |

### 3.3 Desvíos necesarios (resumen, detalle en cada fase)

1. **loginServer path-style** del ACR emulado (`localhost:5000/{registry}` en
   vez de `{name}.azurecr.io`) → ajuste del prefix de imágenes en el overlay
   local + adaptación del contract test (Fase 3.4).
2. **Event Hubs no terraformable** (sin ARM `Microsoft.EventHub`) → namespace y
   topics se crean por la API nativa; el workspace local excluye
   `modules/streaming` (Fase 2.5).
3. **Role assignment ACR→AKS no emulado** (`Microsoft.Authorization` ausente)
   → `acr_id = null`; el pull funciona porque el registry emulado es anónimo
   (Fase 2.4).
4. **DBs PostgreSQL metadata-only** → `CREATE DATABASE orders/payments/catalog`
   manual post-terraform (Fase 2.6).
5. **Redis sin TLS** → `REDIS_PORT=6379` y `spring.data.redis.ssl.enabled=false`
   en el overlay local (el overlay de prod usa 6380 TLS).
6. **Kafka sin SASL** → `KAFKA_SECURITY_PROTOCOL=PLAINTEXT` y bootstrap
   `host.docker.internal:9093` (prod usa `SASL_SSL :9093`).
7. **Keycloak**: la imagen `auth` se pushea al ACR emulado igual que en prod;
   el realm `ecommerce` se importa en build/init (sin cambios de ruta).
8. **DNS**: hosts `nip.io` en el ingress en lugar de zona pública.

---

## Fase 0 — Prerrequisitos

**Objetivo**: dejar la máquina lista: Docker corriendo, toolchain verificada,
certificado TLS del emulador confiado, helm disponible (o plan B sin helm).

### 0.1 Docker Desktop (engine apagado en el estado actual)

```powershell
# Arrancar Docker Desktop (Windows)
Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"

# Esperar al daemon (hasta ~2 min) y verificar
$i = 0
while (-not (docker info *> $null) -and $i -lt 60) { Start-Sleep 2; $i++ }
docker version --format 'Server: {{.Server.Version}}'
docker compose version          # $_: Docker Compose version v5.0.1 (presente)
wsl -l -v                       # docker-desktop distro en WSL2 (backend esperado)
```

**Verificación objetiva**: `docker info` responde y `docker version` muestra
versión de servidor (no solo cliente). Si `wsl -l -v` no muestra
`docker-desktop` con `Running`, revisar Docker Desktop → Settings → *Use the WSL
2 based engine* (fuente: estado de la máquina, no del repo).

### 0.2 Toolchain Java/Maven y Node

- **Java**: JDK presente en `E:\.jdks\corretto-21.0.10` pero **no en PATH**. Los
  servicios son Java 17 (Spring Boot 3.3.x, `services/README.md`); Corretto 21
  compila con `--release 17` sin problemas.

```powershell
$env:JAVA_HOME = "E:\.jdks\corretto-21.0.10"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
# Maven 3.9.12 está bajo ~/.m2/wrapper/dists; los servicios buildan con `mvn` plain.
# Verificar el wrapper de un servicio:
mvn -v
```

- **Node** disponible para el frontend (`npx tsc --noEmit`).
- Nota: no hace falta az CLI. Es **opcional** (Fase 6) para probar `az` contra
  el emulador (los compat-tests de Floci lo soportan); [más abajo](#04-toolchain-opcional).

### 0.3 Instalar helm (con alternativas)

Floci no lo necesita. El repo lo usa para charts de plataforma (ingress-nginx,
kube-prometheus-stack) y para `data/` (Opción B). La ruta primaria de este plan
**no requiere helm local** (Argo CD renderiza charts server-side; y el modo
directo usa `kubectl apply -k` — ver Fase 4.3). Igualmente se instala por si el
operador degrada a B o quiere helmfile.

```powershell
# Opción 1 — winget (recomendada)
winget install Helm.Helm

# Opción 2 — chocolatey
# choco install kubernetes-helm

# Opción 3 — descarga directa (sin package manager)
#   https://get.helm.sh/helm-v3.16.x-windows-amd64.zip  → descomprimir y agregar al PATH

helm version
```

### 0.4 Trust del certificado TLS (CRÍTICO para Terraform)

**Por qué**: el provider `azurerm` descubre el cloud por HTTPS
(`GET https://localhost:4577/metadata/endpoints`). Floci sirve HTTP+HTTPS en el
mismo puerto 4577 vía proxy sniffing de protocolo, con cert self-signed
generado al arranque y servido en `/_floci/tls-cert`. Sin trust, terraform
falla con `x509: certificate signed by unknown authority` (fuente:
`floci-az/docs/terraform.md`).

**Secuencia exacta** (requiere el emulador corriendo → ejecutar tras Fase 1,
o re-ejecutar si el cert cambió):

```powershell
# 1. Descargar el PEM generado en runtime
curl.exe -s http://localhost:4577/_floci/tls-cert -o "$env:TEMP\floci-az.crt"

# 2. Importar al store raíz del usuario (sin elevación; Go (terraform) lee
#    CurrentUser\Root + LocalMachine\Root en Windows)
Import-Certificate -FilePath "$env:TEMP\floci-az.crt" -CertStoreLocation 'Cert:\CurrentUser\Root'

#    (Alternativa elevada si algún proceso sistema necesita confiar:
#    Import-Certificate -FilePath "$env:TEMP\floci-az.crt" -CertStoreLocation 'Cert:\LocalMachine\Root')

# 3. Abrir una NUEVA terminal para todo lo siguiente (terraform y los SDKs
#    cachean el store al arrancar)
```

> El cert se regenera cuando cambian `FLOCI_AZ_HOSTNAME` o `FLOCI_AZ_BASE_URL`
> (fuente: `floci-az/README.md`). Si el emulador se levanta con otro hostname,
> reimportar. El PEM queda persistido en `data/tls/` (volumen `/app/data`).

**Verificación objetiva**:

```powershell
# HTTPS responde y el cert es válido para la máquina
curl.exe -s https://localhost:4577/_floci/tls-cert | Select-Object -First 3
# (vuelve el PEM porque el endpoint en sí no exige verificación de cliente,
#  pero el handshake TLS ya validó la cadena contra el store)
```

### 0.5 Toolchain opcional

```powershell
# az CLI (opcional — para smoke tests de CLI contra el emulador, Fase 6)
winget install Microsoft.AzureCLI
```

**Riesgos de la fase**: ver [§6 R1 (daemon apagado)](#r1-daemon-de-docker-apagado),
R8 (puertos ocupados).

---

## Fase 1 — Levantar Floci-AZ

**Objetivo**: emulador en `localhost:4577` con TLS, storage híbrido persistente,
AKS/ACR/Postgres/Redis/Event Hubs listos para arrancar sidecars, y red docker
preparada.

### 1.1 Compose local (`infra/floci/docker-compose.yml`)

Crear este archivo (contenido propuesto, basado en `docker-compose.yml` del
repo clonado + ajustes de este plan):

```yaml
services:
  floci-az:
    image: floci/floci-az:latest        # verificar tag: :latest puede no ser 0.12.0 (por verificar)
    ports:
      - "4577:4577"                     # management HTTP+HTTPS (protocol-sniffing)
      - "6443-6450:6443-6450"           # rango de API servers k3s (AKS) — aks.md
      # NOTA: los sidecars (k3s, postgres, valkey, registry, redpanda, artemis)
      # bindean puertos del host DIRECTAMENTE via el daemon; NO publicarlos acá
      # (ports.md). ACR=5000, Redis=6379, Kafka=9093, AMQP=5672, Postgres=5432 fijo opcional.
    volumes:
      - ./data:/app/data                # persistencia (storage) + cert TLS + firma Entra
      - /var/run/docker.sock:/var/run/docker.sock   # REQUERIDO: sidecars reales
    environment:
      FLOCI_AZ_STORAGE_MODE: hybrid                  # memory|persistent|hybrid|wal
      FLOCI_AZ_TLS_ENABLED: "true"                   # req. para azurerm (terraform.md)
      FLOCI_AZ_HOSTNAME: floci-az
      FLOCI_AZ_SERVICES_DOCKER_NETWORK: floci_az_default
      # Event Hubs: Kafka endpoint + topics de la plataforma
      FLOCI_AZ_SERVICES_EVENT_HUB_ENABLED: "true"
      FLOCI_AZ_SERVICES_EVENT_HUB_KAFKA_ENABLED: "true"
      FLOCI_AZ_SERVICES_EVENT_HUB_ENTITIES: "order-events:3,payment-events:3"
      # consumer groups: FLOCI_AZ_SERVICES_EVENT_HUB_CONSUMER_GROUPS="\$Default,notification-svc"
      #   (nombre de env por verificar — alternativa: PUT namespaces con consumerGroups, Fase 2.5)
      # PostgreSQL con puerto determinístico (evita leer /connect cada vez):
      # FLOCI_AZ_SERVICES_POSTGRES_DEFAULT_PORT: "5432"   (solo si 5432 está libre)
      # Ahorro de recursos: deshabilitar servicios que la plataforma NO usa
      FLOCI_AZ_SERVICES_FUNCTIONS_ENABLED: "false"
      FLOCI_AZ_SERVICES_COSMOS_ENABLED: "false"
      FLOCI_AZ_SERVICES_SQL_ENABLED: "false"
      FLOCI_AZ_SERVICES_SERVICE_BUS_ENABLED: "false"
      FLOCI_AZ_SERVICES_APP_CONFIG_ENABLED: "false"
    networks:
      floci_az_default:
        aliases:
          - floci-az

networks:
  floci_az_default:
    name: floci_az_default
```

Decisiones del compose (con fuente):

- **`FLOCI_AZ_STORAGE_MODE: hybrid`**: recomendado por las docs
  (`docs/configuration/storage.md`): memoria + flush async cada 5 s. El estado
  del emulador sobrevive reinicios del contenedor gracias al volumen `./data`
  (`FLOCI_AZ_STORAGE_PERSISTENT_PATH` default `/app/data`).
- **TLS on** y **socket montado**: condición para terraform y para los
  sidecars reales (AKS/ACR/PostgreSQL/Redis/Event Hubs).
- **Solo los servicios de la plataforma**: se deshabilitan Functions, Cosmos,
  SQL, Service Bus y App Config (que el emulador enciende por default) para
  reducir huella; el socket sigue siendo necesario por AKS/ACR/Postgres/Redis/
  EventHubs (todos con `mocked=false` por default y **no** se tocan sus
  `*_MOCKED`).
- **Puerto 5432 fijo** (opcional): `postgresql.md` documenta
  `FLOCI_AZ_SERVICES_POSTGRES_DEFAULT_PORT` para fijar el primer servidor; si
  está ocupado, cada servidor toma puerto aleatorio y hay que leerlo de
  `GET /{account}-postgres/flexibleServers/{name}/connect`.

### 1.2 Arrancar y verificar

```powershell
cd infra/floci
docker compose up -d

# Esperar health (el binario nativo arranca en ms; primer pull de imagen puede tardar)
$i = 0
while ($i -lt 90) {
  try { $h = curl.exe -s http://localhost:4577/health; if ($h) { break } } catch {}
  Start-Sleep 2; $i++
}
curl.exe -s http://localhost:4577/health          # 200 JSON con el estado de servicios
curl.exe -s -X POST http://localhost:4577/_admin/reset   # 204 (aislamiento de tests)
curl.exe -s http://localhost:4577/_floci/tls-cert -o "$env:TEMP\floci-az.crt"   # pem
```

**Verificación objetiva**: `GET /health` responde 200; `POST /_admin/reset`
responde 204; el cert PEM se descarga (luego se importa con Fase 0.4).

### 1.3 Subir el stack de servicios necesarios

Con `mocked=false` (default) los sidecars arrancan **bajo demanda** al crear
recursos (Fase 2): el primer `azurerm_kubernetes_cluster` levanta el k3s, el
primer `azurerm_container_registry` el `registry:2`, etc. No hay que
«pre-arrancarlos» manualmente. La única excepción es Event Hubs, cuyo namespace
default arranca en boot si `EVENT_HUB_ENABLED=true` (Fase 2.5 para topics).

> Si un sidecar falla por puerto ocupado, el error aparece en los logs del
> recurso correspondiente (p. ej. `redis: bind 6379`) — ver [§6 R8](#r8-conflictos-de-puertos).

**Riesgos de la fase**: R1 (daemon), R8 (puertos), R9 (primer pull lento de
`rancher/k3s`, `postgres:17-alpine`, etc.).

---

## Fase 2 — Terraform contra el emulador

**Objetivo**: aprovisionar AKS (k3s real), ACR, PostgreSQL + DBs, Redis, Event
Hubs (bootstrap nativo) y Key Vault con secretos, replicando `infra/terraform/`
lo más fielmente posible, usando el provider `azurerm` en modo `stack`.

### 2.1 Estructura del workspace — decisión de diseño

**Propuesta: nuevo root module `infra/terraform/envs/local`** (no un directorio
`infra/floci/` separado).

Justificación:

- Los módulos se referencian por ruta relativa `source = "../../modules/<x>"`;
  un directorio fuera de `infra/terraform/` (p. ej. `infra/floci/`) rompería esa
  convención o exigiría duplicar/parametrizar módulos.
- Espeja el patrón existente (`envs/dev|staging|prod`), con su propio
  `terraform.tfvars`; `infra/terraform/README.md` ya documenta la alternativa de
  **estado local** (renombrar/borrar `backend.tf`) — exactamente lo que usa este
  workspace.
- El `.gitignore` del repo ya cubre `.terraform/`, `*.tfstate` y
  `.terraform.lock.hcl`; se agrega `*.tfvars` local (ver §5).

**Qué se reusa tal cual** (sin cambios): `modules/networking`,
`modules/registry`, `modules/aks` (parametrizado), `modules/databases`
(parametrizado), `modules/dns` (no-op con `dns_zone_name=""`).

**Qué se parametriza en `envs/local/terraform.tfvars`**:

```hcl
environment = "local"
location    = "eastus"            # cualquier región; el emulador no la valida
suffix      = "floci01"           # único del operador

# provider → emulador (se definen en variables.tf/provider.tf del workspace)
# subscription_id / tenant_id / client_id / client_secret: fake (00000000-...)

# AKS: sin Entra RBAC, un solo pool, sin role assignment (desvío 3)
enable_aad             = false
admin_group_object_ids = []
enable_services_pool   = false
aks_autoscaling_enabled = false
node_count_min         = 1
node_count_max         = 1
acr_id                 = null        # sin Microsoft.Authorization (desvío 3)

# ACR: admin on para push local
acr_sku                   = "Basic"
acr_admin_enabled         = true
acr_public_network_access_enabled = true

# PostgreSQL: público + firewall 0.0.0.0 (dev, igual que envs/dev)
postgres_sku                      = "B_Standard_B1s"
postgres_public_network_access_enabled = true
postgres_allowed_ip_ranges        = ["0.0.0.0/0"]
postgres_administrator_login      = "psqladmin"
postgres_administrator_password   = "<fuerte-sin-@>"   # nunca commitear

# Redis: `enable_non_ssl_port` — ver nota 2.4
redis_sku     = "Basic"
redis_capacity = 1

# DNS: off
dns_zone_name = ""
```

**Provider block** del workspace (`envs/local/provider.tf`, basado en
`compatibility-tests/compat-terraform/provider.tf`):

```hcl
terraform {
  required_version = ">= 1.5.0"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
  }
}

provider "azurerm" {
  features {}
  skip_provider_registration = true
  use_cli                    = false

  environment   = "stack"            # cloud custom descrito por metadata_host
  metadata_host = "localhost:4577"   # floci sirve /metadata/endpoints por HTTPS acá

  subscription_id = "00000000-0000-0000-0000-000000000001"
  tenant_id       = "00000000-0000-0000-0000-000000000002"
  client_id       = "00000000-0000-0000-0000-000000000003"
  client_secret   = "fake-secret"    # dev auth mode: no se validan credenciales
}
```

**Cambio menor propuesto a `modules/databases/main.tf`** (un PR aparte, no tocar
en esta fase): hoy fija `enable_non_ssl_port = false` (línea 164). Floci sirve
Redis **solo** en el puerto no-SSL (`redis.md`). Opciones: (a) probar primero
con el módulo tal cual — Floci expone `port:6379` igual y es el único data
plane, (b) si falla, agregar variable `redis_enable_non_ssl_port` (default
`false`, `local=true`). Documentar el resultado.

### 2.2 Init, plan y apply

```powershell
cd infra/terraform/envs/local

# SIN backend.tf → estado local (terraform.tfstate)
terraform init
terraform plan -out=plan.tfplan
terraform apply plan.tfplan
```

**Qué esperar** (fuente: docs de Floci): creación del RG/VNet/NSG inmediata;
ACR rápido; **AKS 30-90 s** (primer arranque del k3s, `provisioningState` pasa
de `Creating` a `Succeeded` — `docs/services/aks.md`); PostgreSQL y Redis arrancan
sus contenedores en el primer PUT (primer pull de `postgres:17-alpine` /
`valkey:8-alpine` puede tardar); Key Vault inmediato (ARM).

### 2.3 Verificación del aprovisionamiento

```powershell
terraform state list                       # ~15+ recursos
terraform output

# kubeconfig real del AKS emulado (listClusterAdminCredential → k3s real)
terraform output -raw kubeconfig | Set-Content -Encoding ascii "$env:USERPROFILE\.kube\floci-ecommerce.yaml"
$env:KUBECONFIG = "$env:USERPROFILE\.kube\floci-ecommerce.yaml"
kubectl get nodes                          # nodo del k3s real, estado Ready

# PostgreSQL emulado — connection strings reales
$conn = curl.exe -s "http://localhost:4577/devstoreaccount1-postgres/flexibleServers/<server>/connect"
# Redis — PING con la primary key
$key = terraform output -raw redis_primary_access_key
redis-cli -h localhost -p 6379 -a $key ping   # PONG
```

**Verificación objetiva**: `terraform apply` idempotente (segundo plan sin
diff), `kubectl get nodes` con Ready, `psql ... -c "SELECT 1"` OK.

### 2.4 Desvíos ARM documentados

- **`azurerm_role_assignment` (AcrPull)**: `Microsoft.Authorization` NO está
  emulado (ausente en compat-tests y en el source de Floci — verificado por
  grep de `Microsoft.EventHub`/`Microsoft.Authorization`). El workspace pasa
  `acr_id = null` y el pull funciona porque el data plane del ACR emulado es
  **anónimo** (`acr.md`).
- **`azurerm_kubernetes_cluster`**: campos como `oidc_issuer_enabled`,
  `azure_active_directory_role_based_access_control`, `network_profile` se
  mandan al ARM emulado; el emulador los acepta como metadata y el k3s real
  arranca igual con `enable_aad=false`. **Por verificar**: si el provider
  exige en el read campos que el emulador no devuelve (p. ej.
  `kubelet_identity`), aplicar el trabajo de compat-test upstream
  (`compatibility-tests/compat-terraform/main.tf` no incluye AKS — si el
  workspace local falla en AKS, abrir issue upstream y reportar en §8
  «por verificar»).

### 2.5 Event Hubs — bootstrap nativo (desvío 2)

El provider `azurerm_eventhub_namespace` habla ARM `Microsoft.EventHub`, ruta
que Floci **no** enruta (verificado en `EventHubHandler.java`: solo paths
`/{account}-eventhub/namespaces/...`). Por lo tanto:

1. El workspace `envs/local` **NO** invoca `modules/streaming` (comentado, con
   nota explicativa).
2. El namespace/topics se crean con la API nativa del emulador:

```powershell
# Namespace por defecto (ya arranca si EVENT_HUB_ENABLED=true) + topics via env
# (ver compose Fase 1). Si se prefiere crearlo explícito:
curl.exe -s -X PUT "http://localhost:4577/devstoreaccount1-eventhub/namespaces/ecommerce-eh" `
  -H "Content-Type: application/json" `
  -d '{"entities":"order-events:3,payment-events:3","consumerGroups":"$Default,notification-svc"}'

# Verificar
curl.exe -s "http://localhost:4577/devstoreaccount1-eventhub/health"
curl.exe -s "http://localhost:4577/devstoreaccount1-eventhub/namespaces"
```

3. Kafka endpoint: `localhost:9093` (Redpanda, activado con
   `FLOCI_AZ_SERVICES_EVENT_HUB_KAFKA_ENABLED=true`). **Por verificar**: modo de
   auth del sidecar Redpanda (dev: sin auth, protocol `PLAINTEXT`).

### 2.6 PostgreSQL — crear las DBs reales (desvío 4)

El ARM emulado crea las databases **solo como metadata** (`postgresql.md`); el
`CREATE DATABASE` real lo hace la tooling de migración. Para esta fase:

```powershell
# Leer FQDN/puerto reales del server emulado (si no se fijó 5432)
$conn = curl.exe -s "http://localhost:4577/devstoreaccount1-postgres/flexibleServers/psql-local-ecommerce-<suffix>/connect"
# → host/port/jdbcUrl (sslmode=disable — desvío local documentado en postgresql.md)

# Crear las 3 DBs (una por servicio, igual que el módulo terraform)
psql "host=localhost port=<port> user=psqladmin password=<pw> dbname=postgres sslmode=disable" `
  -c "CREATE DATABASE orders;" -c "CREATE DATABASE payments;" -c "CREATE DATABASE catalog;"
```

> Nota: los servicios arrancan con `ddl-auto: create-drop` en H2 por defecto
> (`catalog-svc/src/main/resources/application.yml`); al conectar a Postgres real
> se documenta usar `validate` + migraciones en una fase posterior. Para la
> demo, Hibernate puede crear el esquema en el arranque (comportamiento a
> confirmar por servicio — **por verificar**).

### 2.7 Key Vault emulado — sembrar secretos

```powershell
# PUT secretos (versionado automático por el emulador)
curl.exe -s -X PUT "https://localhost:4577/devstoreaccount1-keyvault/secrets/db-password?api-version=7.4" `
  -H "Content-Type: application/json" -d '{"value":"<mismo-password-que-postgres>"}'
curl.exe -s -X PUT "https://localhost:4577/devstoreaccount1-keyvault/secrets/keycloak-admin-username?api-version=7.4" -d '{"value":"admin"}'
curl.exe -s -X PUT "https://localhost:4577/devstoreaccount1-keyvault/secrets/keycloak-admin-password?api-version=7.4" -d '{"value":"<admin-pw>"}'
curl.exe -s "https://localhost:4577/devstoreaccount1-keyvault/secrets?api-version=7.4"   # listar
```

(El SDK fuerza HTTPS; con el cert importado en Fase 0.4, curl valida. El
emulador acepta versiones de API recientes — `docs/services/key-vault.md`.)

**Riesgos de la fase**: R4 (AKS no aplica — por verificar), R7 (timeouts),
R10 (lentitud primer pull), R2 (si k3s falla → degradar a Opción B).

---

## Fase 3 — Imágenes y registry (ACR emulado)

**Objetivo**: buildear y pushear las 18 imágenes al ACR emulado con tags por
SHA (NUNCA `:latest` flotante — policy Kyverno `disallow-latest-tag` en
`cluster/base/kyverno/policies/disallow-latest-tag.yaml`, modo Enforce).

### 3.1 Resultado del terraform: loginServer

El data plane del ACR emulado se sirve del sidecar `registry:2`:
**loginServer = `localhost:5000/{registryName}`** (path-style; Docker ya trata
`localhost:PORT` como insecure/plain-HTTP, no hace falta config del daemon —
`acr.md`). Con `suffix=floci01` el nombre es `localecommercefloci01`
(`registry_name = replace("${env}-ecommerce-${suffix}", "-", "")`).

### 3.2 Build + push (PowerShell)

```powershell
$sha  = git rev-parse --short HEAD          # tag reproducible, nunca :latest
$reg  = "localhost:5000"
$acr  = "localecommercefloci01"             # = terraform output -raw registry_name
$svcs = @("config-service","discovery-service","api-gateway","auth","catalog-svc",
          "cart-svc","checkout-svc","order-svc","payment-svc","notification-svc",
          "search-svc","recommendation-svc","inventory-svc","shipping-svc",
          "returns-svc","analytics-svc","bff-web","frontend")

foreach ($svc in $svcs) {
  docker build -t "$reg/$acr/$svc`:$sha" "services/$svc"
  if ($?) { docker push "$reg/$acr/$svc`:$sha" }
}

# Verificación
curl.exe -s http://localhost:5000/v2/_catalog        # lista los repos (acr.md)
```

Notas:

- `auth` (Keycloak 24) builda desde `services/auth/Dockerfile`; si el
  Dockerfile es thin y el realm `ecommerce` se importa en runtime, verificar
  que la imagen incluya el realm o el mount (detalle del servicio, no del
  plan).
- Los builds usan el JDK/Maven de Fase 0.2 (`mvn` per-service, multi-stage en
  el Dockerfile → el build de Maven corre dentro del contenedor build; si se
  prefiere build local: `mvn -B package --file services/<svc>/pom.xml`).

### 3.3 k3s → registry: mirror insecure (desvío 6, lado cluster)

Dentro del clúster k3s (un contenedor), `localhost:5000` NO es el host. Los
pods deben referenciar imágenes con un host que el kubelet (containerd) pueda
resolver **y** tratar como insecure (HTTP):

```powershell
# 1. Descubrir el nombre del contenedor k3s (floci-az-aks-<instanceId>)
docker ps --filter "name=floci-az-aks" --format "{{.Names}}"

# 2. Escribir el mirror de registries de k3s (ruta estándar de k3s; por verificar
#    que el contenedor de Floci la use sin modificaciones)
$k3s = (docker ps --filter "name=floci-az-aks" --format "{{.Names}}")
docker exec -u root $k3s sh -c 'mkdir -p /etc/rancher/k3s && cat > /etc/rancher/k3s/registries.yaml <<EOF
mirrors:
  "host.docker.internal:5000":
    endpoint:
      - "http://host.docker.internal:5000"
EOF'
docker restart $k3s    # containerd relee registries.yaml al reiniciar
```

> `host.docker.internal` lo resuelve Docker Desktop dentro de cada contenedor
> hacia el host (validado en WSL2 — por verificar con el k3s específico de
> Floci). Alternativa: usar el `loginServer` que devuelve el emulador cuando
> corre en Docker (forma `{container}:5000/{registry}` — `acr.md`), con su
> mirror correspondiente. **Decisión a confirmar en ejecución**: el prefix de
> imágenes del overlay (Fase 4) debe coincidir con el mirror configurado.

### 3.4 Impacto en `tests/manifests/test-service-contract.py` — decisión

El test exige `name == newName == "acr.azurecr.io/<svc>"` y que las imágenes de
los `deployment.yaml` del base empiecen con `acr.azurecr.io/<svc>:` (líneas
79-80 y 107 del test). Con el ACR path-style eso es imposible de cumplir para
el overlay local sin tocar los bases.

**Recomendación técnica: adaptar el test con una variable de entorno**:

- `CONTRACT_IMAGE_PREFIX` (default `acr.azurecr.io`) usada en las dos
  aserciones de prefijo de imagen.
- El overlay **local** setea `CONTRACT_IMAGE_PREFIX=localhost:5000/localecommercefloci01`
  cuando corre el test; CI sigue con el default y no cambia nada.

Justificación:

1. El **propósito** del test es el layout uniforme, los securityContext
   (PSA restricted), los probes de health y el contrato del ServiceMonitor —
   el host del registry es configuración de ambiente, no esencia del contrato.
2. Un registry path-style (`localhost:5000/{registry}/{repo}`) es la forma
   **canónica que Docker impone** para registries locales; forzar el patrón
   `.azurecr.io` en local negaría el test entero sin ganar nada.
3. La policy Kyverno `disallow-latest-tag` sigue cumpliéndose: los tags son por
   SHA; la policy no valida el host del registry, solo el tag.

Alternativa (fallback) si no se quiere tocar el test: **aceptar el desvío
documentado** y correr el test solo contra los bases en CI (sin el overlay
local), registrándolo en el checklist de Fase 5 como excepción explícita. La
recomendación es la primera opción porque mantiene la protección activa.

---

## Fase 4 — Despliegue al clúster emulado

**Objetivo**: aplicar la plataforma sobre el k3s emulado con Kustomize,
instalar los componentes de plataforma (Kyverno, PSA, ingress-nginx,
cert-manager, ESO) y activar los switches latentes para paridad real.

### 4.1 Overlay propuesto: `cluster/overlays/local`

Espejo de `overlays/dev` (misma estructura `kustomization.yaml` +
`env-config.yaml` + `issuer-*.yaml` + `patches/`) con estas diferencias:

| Pieza | dev (Azure) | local (emulador) |
|---|---|---|
| `images:` en kustomization | `acr.azurecr.io/<svc>` + `newTag: latest` (CD reescribe) | `newName: host.docker.internal:5000/localecommercefloci01/<svc>` + `newTag: <sha>` |
| `env-config.yaml` | FQDNs Azure | `DB_HOST=host.docker.internal`, `DB_PORT=5432` (o puerto real), `REDIS_HOST=host.docker.internal`, `REDIS_PORT=6379`, `KAFKA_BOOTSTRAP=host.docker.internal:9093`, `KAFKA_SECURITY_PROTOCOL=PLAINTEXT` |
| `issuer-default.yaml` | Let's Encrypt staging | **ClusterIssuer selfsigned** (sin dominio público; LE no puede validar `nip.io` local) |
| `patches/secret-store-patch.yaml` | `https://kv-dev-ecommerce.vault.azure.net` | `https://host.docker.internal:4577/devstoreaccount1-keyvault` + trust del cert (evaluación 4.4) |
| `resources:` | incluye `observability/` y `security/` | ídem; **sin** `external-dns` (descartado) ni Argo CD Applications (ver 4.3) |

### 4.2 Instalar componentes base (Kyverno/PSA/ingress/cert-manager/ESO)

Dos caminos:

**A. Via Argo CD (máxima fidelidad — recomendado si va estable):**
`cluster/base/argocd/applications/*.yaml` despliegan los charts
(ingress-nginx, cert-manager, kyverno, keda, kube-prometheus-stack,
external-secrets) desde el repo; Argo CD renderiza Helm **server-side** (no
hace falta helm local):

```powershell
# 1. Instalar Argo CD primero (bootstrap manual, sin helm)
kubectl apply -k cluster/base/argocd/install

# 2. Esperar CRDs y controller
kubectl -n argocd rollout status deployment/argocd-server
kubectl -n argocd rollout status deployment/argocd-repo-server

# 3. Aplicar el resto del base (namespaces, ClusterIssuer, IngressClass,
#    ClusterSecretStore, policies Kyverno, KEDA, monitoring)
kubectl apply -k cluster/overlays/local
```

> Las Applications del base apuntan a rutas de este repo y a charts remotos;
> para emulación conviene un overlay local que **no** incluya
> `argocd/app-of-apps.yaml` ni las Applications de ambiente (evita que Argo
> sincronice contra overlays que no existen en local). **Por verificar** cuál
> es la combinación exacta de resources que renderiza limpio en el k3s
> (charts remotos requieren acceso a internet para el primer render).

**B. Directo con kustomize (fallback sin Argo CD):**

```powershell
kubectl apply -k cluster/overlays/local    # namespaces, workloads, policies
# charts de plataforma en raw manifests (sin helm):
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/.../deploy/static/provider/kind/deploy.yaml
# kyverno/eso/cert-manager: usar sus manifests release (URLs a decidir en ejecución)
```

### 4.3 Despliegue de la aplicación

```powershell
$env:KUBECONFIG = "$env:USERPROFILE\.kube\floci-ecommerce.yaml"
kubectl apply -k cluster/overlays/local

# Orden de dependencia (Spring Cloud): config-service y discovery-service primero
kubectl -n ecommerce rollout status deploy/config-service
kubectl -n ecommerce rollout status deploy/discovery-service
kubectl -n ecommerce rollout status deploy/catalog-svc    # etc.
```

### 4.4 Activar switches latentes (paridad real)

| Switch | Estado hoy | Acción para local | Fuente |
|---|---|---|---|
| cart-svc → Redis | comentado en `config-service/src/main/resources/config/cart-svc.yml` | descomentar `spring.data.redis.host/port` = env `REDIS_HOST/REDIS_PORT` | `cart-svc.yml` |
| Gateway rate limiter | comentado en `api-gateway/src/main/resources/application.yml` (Requires reactive-redis en pom) | descomentar filtro `RequestRateLimiter` + dependency en `pom.xml` | `application.yml` |
| notification-svc → Kafka | `NOTIFICATIONS_KAFKA_ENABLED=false` (configmap base) | `true` en el configmap del overlay local + `KAFKA_BOOTSTRAP=host.docker.internal:9093` + `KAFKA_SECURITY_PROTOCOL=PLAINTEXT` | `notification-svc/k8s/base/configmap.yaml` y `NotificationConsumer.java` |
| Secretos vía ESO | `ClusterSecretStore` → vault Azure | ver 4.4.1 | `cluster/base/external-secrets/` |

> Estos son **cambios de código/config a ejecutar en la fase** (PRs aparte);
> este plan solo los identifica y justifica.

#### 4.4.1 ESO → Key Vault emulado: evaluación de viabilidad

- **Endpoint**: `https://host.docker.internal:4577/devstoreaccount1-keyvault`
  (path-style, HTTPS obligatorio para el SDK).
- **Bloqueos**: (1) el cert self-signed debe estar en el trust store **del
  pod/sistema del k3s** — inyectar `floci-az.crt` en `/etc/ssl/certs` del nodo
  k3s o via `SSL_CERT_FILE` en el deployment del controller ESO; (2)
  `authType: ManagedIdentity` — el emulador expone IMDS (managed-identity.md,
  HTTP-only) y dev auth acepta cualquier credencial, pero el provider Azure de
  ESO hace un flujo de challenge/IMDS que puede no mapear 1:1.
- **Decisión**: intentar en la fase con el ClusterSecretStore apuntando al
  emulador; si el controller ESO no resuelve (error de auth/challenge), **usar
  el fallback documentado: Kubernetes Secrets planos** con las mismas claves
  (`db-password`, `keycloak-admin-*`, SASL/PLAINTEXT no aplica) — el mismo
  modelo que `data/README.md` describe como «camino in-cluster».
- **Por verificar**: comportamiento del provider ESO azure-go contra el
  endpoint emulado (issue upstream si falla; reportar en §8).

### 4.5 PSA restricted + Kyverno

Los manifests ya cumplen PSA restricted (runAsNonRoot + seccomp, verificado
por `test-service-contract.py`) y `cluster/overlays/dev` aplica el componente
`security/pod-security`. El overlay local hace lo mismo. Prueba negativa de la
policy `disallow-latest-tag` en Fase 5.

**Riesgos de la fase**: R5 (ESO), R6 (contract test), R2 (k3s), R9 (pulls y
arranques Spring Boot lentos en el k3s dentro de WSL2).

---

## Fase 5 — Verificación end-to-end

**Objetivo**: demostrar que la emulación está operativa y se comporta como
producción.

### 5.1 Salud de la plataforma

```powershell
$env:KUBECONFIG = "$env:USERPROFILE\.kube\floci-ecommerce.yaml"
kubectl get nodes -o wide
kubectl get pods -n ecommerce -o wide          # todos Running/Ready
kubectl get pods -n ingress-nginx -n cert-manager -n kyverno -n external-secrets
kubectl -n ecommerce get configmap ecommerce-env-config -o yaml
```

**Verificación objetiva**: 100% de pods `Running/Ready` (con `kubectl wait
--for=condition=Ready pod -l app=<svc> -n ecommerce --timeout=180s`); Eureka
listando todas las instancias (port-forward al discovery-service y ver
`/eureka/apps`).

### 5.2 BFF con datos reales del Postgres emulado

```powershell
# Port-forward del BFF (o ingress) y pedir el catálogo
kubectl -n ecommerce port-forward svc/bff-web 8090:8080 &
curl.exe -s http://localhost:8090/api/catalog | ConvertFrom-Json | Select-Object -First 3
```

**Verificación objetiva**: el catálogo devuelve productos en español (mock data
`ENABLE_MOCK_DATA=true` conforme el overlay) y **persistidos en Postgres
emulado** — verificable con psql al contenedor (`SELECT count(*) FROM
catalog.<tabla>`; nombre de tabla por verificar con el esquema de catalog-svc).

### 5.3 Flujo completo de checkout

```powershell
# 1. Crear orden vía checkout-svc → order-svc
# 2. Pago vía payment-svc → emite "payment-events"
# 3. El consumer notification-svc (groupId notification-svc) consume order-events/payment-events
# Logs del consumer:
kubectl -n ecommerce logs deploy/notification-svc -f | Select-String "order-events|payment-events"
```

**Verificación objetiva**: en los logs del consumer aparecen los eventos
(`notification-svc` loguea `order-events ← {...}` / `payment-events ← {...}` —
`NotificationConsumer.java`); alternativamente consumir con un cliente Kafka
contra `localhost:9093` y comprobar los offsets del group `notification-svc`.

### 5.4 Frontend en localhost vía ingress

```powershell
# nip.io resuelve *.127.0.0.1.nip.io → 127.0.0.1 sin tocar hosts
Start-Process "http://www.127.0.0.1.nip.io"     # o api.127.0.0.1.nip.io para el API
```

**Verificación objetiva**: el navegador carga el frontend (ingress-nginx),
login con Keycloak (realm ecommerce) y el checkout funciona de punta a punta.

### 5.5 Pruebas negativas (las policies siguen activas)

```powershell
kubectl -n ecommerce run nginx-latest --image=nginx:latest --restart=Never
# → debe ser rechazado por disallow-latest-tag (Kyverno Enforce)
```

### 5.6 Obstáculos comunes

| Síntoma | Causa probable | Mitigación |
|---|---|---|
| Pods `ImagePullBackOff` | mirror `registries.yaml` no configurado / host mal | Revisar Fase 3.3; `kubectl describe pod` |
| `CrashLoopBackOff` de DB services | DB no creada (metadata-only) o password distinto | Fase 2.6; el emulador no valida credenciales pero el app sí |
| Gateway 503 | Eureka aún no registró la instancia | esperar; `curl discovery-service/eureka/apps` |
| 429 en gateway | rate limiter activo y Redis caído | revisar `spring.data.redis` en cart-svc/gateway |
| Kyverno rechaza pods de charts | policy excluye namespaces conocidos; charts nuevos no | ajustar `namespaceSelector` (excluir otros) |
| frontend sin carga | ingress host/SSL | usar `http://www.127.0.0.1.nip.io` (sin TLS si el issuer es selfsigned opcional) |

---

## Fase 6 — Opcionales de paridad

| Opcional | Descripción | Esfuerzo |
|---|---|---|
| **Entra emulado (OIDC)** | Probar adquisición/validación de tokens RS256 contra `/{tenant}/oauth2/v2.0/token` + JWKS (`docs/services/entra.md`). La app real usa Keycloak; sirve para validar que un futuro STS de Azure funcione. Sin `refresh_token` (limitación del emulador). | Bajo (curl/SDK) |
| **Monitoreo** | Prometheus/Grafana ya in-cluster (kube-prometheus-stack): ServiceMonitors de los servicios (`observability/`); dashboards locales. | Bajo |
| **Persistencia y reset** | `hybrid`/`wal` por servicio (`FLOCI_AZ_STORAGE_SERVICES_<SVC>_MODE`) + `/_admin/reset` para aislamiento de tests; limpieza completa al final (§6 R10). | Bajo |
| **Argo CD app-of-apps completo** | Replicar el GitOps de prod dentro del k3s (repo local o fork); requiere configurar el repo en Argo CD. | Medio |
| **SDK smoke tests** | Correr `compatibility-tests/compat-terraform` u otro suite del repo Floci contra el emulador local para confirmar el baseline. | Bajo |

---

## 5. Secrets y seguridad local

> El propósito de la emulación es **desarrollo local**: todas las credenciales
> son dev del emulador o fake. Reglas:

1. **Credenciales dev del emulador** (no son secretos reales):
   - Azurite well-known key `<devstoreaccount1-key>`
     (documentada en `docs/configuration/docker-compose.md` y `README.md` del
     repo clonado; el auth mode dev de Floci **no valida** credenciales —
     `provider.tf` usa `fake-secret`).
   - IDs fake de sub/tenant/client del provider (`00000000-...-0001/0002/0003`).
   - Passwords operadas por el operador para la demo (`postgres_administrator_password`,
     `keycloak-admin-password`): elegir dev, jamás valores reales.
2. **Nunca commitear** (agregar al `.gitignore` del repo):
   - `infra/terraform/envs/local/*.tfvars` (contiene passwords).
   - `.kube/floci-ecommerce.yaml` (kubeconfig con credenciales del k3s local).
   - `infra/floci/data/` (volumen persistido del emulador: estado, cert TLS,
     firma Entra — `FLOCI_AZ_STORAGE_PERSISTENT_PATH=/app/data`).
   - El PEM descargado del emulador (`floci-az.crt`).
   - `plan.tfplan` (puede contener valores de outputs).
3. **El emulador no valida nada**: las políticas de seguridad del repo (Kyverno
   `disallow-latest-tag`, PSA restricted) siguen aplicando **al repositorio**, no
   al emulador; el hecho de que el emulador acepte cualquier credencial no
   autoriza a relajarlas.
4. **Aislamiento de red**: bindear los puertos a loopback en el compose
   (`ports: "127.0.0.1:4577:4577"`, igual con 6443/5000/6379/9093/5672/5432) si
   la máquina está en una LAN compartida.
5. **Limpieza de credenciales al terminar** (ver R10):
   `docker compose down` + borrar `infra/floci/data/` + `/_admin/reset`.

---

## 6. Riesgos y mitigaciones

| # | Riesgo | Impacto | Detección | Mitigación |
|---|---|---|---|---|
| <a id="r1"></a>R1 | Daemon de Docker apagado / Docker Desktop no arranca | Nada corre (Fase 1 bloqueada) | `docker info` falla | Fase 0.1: arranque + espera del daemon; verificar WSL2 backend |
| <a id="r2"></a>R2 | Contenedor k3s privilegiado falla en Docker Desktop/WSL2 (seccomp/apparmor, nested containerization) | AKS emulado sin clúster → Fase 2 incompleta | `provisioningState` queda en `Failed`; logs del sidecar k3s | **Degradar a Opción B** (§2.2-2.4); o abrir issue upstream con la traza |
| <a id="r3"></a>R3 | `host.docker.internal` no resuelve dentro del contenedor k3s de Floci | Pods no pullean del registry (Fase 4) | `ImagePullBackOff`; `docker exec $k3s getent hosts host.docker.internal` | Alternativa `{container}:5000/{registry}` (loginServer intra-Docker, `acr.md`); o copiar el cert/mirror vía `docker exec` |
| <a id="r4"></a>R4 | El read de `azurerm_kubernetes_cluster` pide campos que el ARM emulado no devuelve (p. ej. `kubelet_identity`) | `terraform apply` falla en el read post-create | plan/apply loguea el campo que falta | Workspace sin AKS es la ruta de degradación; reportar issue upstream (los compat-tests no cubren AKS todavía) |
| <a id="r5"></a>R5 | External Secrets → Key Vault emulado no resuelve (challenge/IMDS/trust) | Pods de auth sin secretos (Fase 4.4) | ESO secret `ERR_SYNC`; SecretStore `NotReady` | Fallback documentado: Kubernetes Secrets planos con las mismas claves (modelo «camino in-cluster» de `data/README.md`) |
| <a id="r6"></a>R6 | `test-service-contract.py` exige prefix `acr.azurecr.io` | Test falla contra el overlay local | pytest rojo | `CONTRACT_IMAGE_PREFIX` env (default = CI actual) o excepción documentada (Fase 3.4) |
| <a id="r7"></a>R7 | Timeouts/lentitud de `terraform apply` (creación de k3s, pulls de imágenes sidecar) | Abortos o plan interrumpido | `DeadlineExceeded` | `TF_LOG=info` para ver poll; el provider azurerm puea largos; agregar `retry` en vars |
| <a id="r8"></a>R8 | Conflictos de puertos (4577, 6443, 5000, 6379, 9093, 5672, 5432) | Sidecars no bootean | Logs de sidecar con `bind: address already in use` | `netstat -ano` / `Get-NetTCPConnection`; liberar o cambiar `FLOCI_AZ_SERVICES_POSTGRES_DEFAULT_PORT`; bindear loopback en compose |
| <a id="r9"></a>R9 | Primer pull de imágenes pesadas (`rancher/k3s`, `postgres:17-alpine`, `valkey`, `registry:2`, `redpanda`) | Fase 1/2 parecen colgadas | `docker pull` lento en logs | Pre-pullear los sidecars en Fase 1 (`docker pull rancher/k3s:...`); esperar con paciencia; cachear en el daemon |
| <a id="r10"></a>R10 | Estado residual del emulador (storage persistido, sidecars huérfanos) | Pruebas contaminadas entre runs | Recursos que «ya existían» | `/_admin/reset` entre demos; `docker compose down` (sin `-v`) y borrar `infra/floci/data/` para limpieza total |

---

## 7. Criterios de «listo»

Checklist de cierre del entorno emulado (todas las fases previas cumplidas):

- [ ] Fase 0: `docker info` OK; JDK/Maven/Node verificados; cert TLS importado y
      `terraform plan` resuelve `GET https://localhost:4577/metadata/endpoints`.
- [ ] Fase 1: `GET /health` 200, `/_admin/reset` 204, storage `hybrid` con
      `data/` persistido.
- [ ] Fase 2: `terraform apply` **idempotente** (segundo `plan` sin diff);
      `kubectl get nodes` → 1 nodo k3s `Ready`; psql `SELECT 1` contra Postgres
      emulado; `redis-cli PONG`; 3 DBs creadas; secrets en el Key Vault emulado;
      namespace de Event Hubs con `order-events` y `payment-events` (api nativa).
- [ ] Fase 3: 18 imágenes en `localhost:5000/localecommercefloci01` con tag por
      SHA; **cero** tags `:latest`; mirror `registries.yaml` configurado en el
      k3s (o loginServer intra-Docker); contract test en verde con
      `CONTRACT_IMAGE_PREFIX`.
- [ ] Fase 4: 100% de pods de la plataforma `Running/Ready`; ESO resuelto (o
      fallback de secrets planos documentado); switches latentes activos
      (cart→Redis, gateway rate limiter, `NOTIFICATIONS_KAFKA_ENABLED=true`).
- [ ] Fase 5: checkout e2e completo (orden → pago → evento Kafka → log del
      consumer `notification-svc`); frontend vía `www.127.0.0.1.nip.io`;
      prueba negativa de Kyverno pasa (rechaza `nginx:latest`).
- [ ] Limpieza: `docker compose down` + `infra/floci/data/` borrado (o estado
      documentado como conservado para la próxima sesión).

---

## 8. Fuentes y referencias

### 8.1 Afirmaciones clave → origen

| Afirmación | Fuente |
|---|---|
| TLS requerido por azurerm; cert en `/_floci/tls-cert`; `environment=stack` + `metadata_host` | `floci-az/docs/terraform.md` |
| Provider dev: immutables `use_cli=false`, `skip_provider_registration`, IDs fake, `fake-secret` | `floci-az/compatibility-tests/compat-terraform/provider.tf` |
| Recursos soportados por ARM (sin role assignment, sin eventhub) | `floci-az/compatibility-tests/compat-terraform/main.tf` |
| AKS emulado = k3s real; kubeconfig con CA real; puertos 6443-7443; `*_MOCKED` escape hatch | `floci-az/docs/services/aks.md` |
| ACR = `registry:2` compartido; data plane directo; loginServer path-style `localhost:5000/{registry}`; anónimo | `floci-az/docs/services/acr.md` |
| PostgreSQL = `postgres:17-alpine`; DBs metadata-only; firewall metadata-only; `sslmode=disable`; `FLOCI_AZ_SERVICES_POSTGRES_DEFAULT_PORT` | `floci-az/docs/services/postgresql.md` |
| Redis = `valkey:8-alpine`; primary key = password; puertos 6379-6399; no-SSL | `floci-az/docs/services/redis.md` |
| Event Hubs nativo (no ARM); `PUT /{account}-eventhub/namespaces`; Kafka Redpanda 9093 opt-in | `floci-az/docs/services/event-hub.md` + `src/main/java/io/floci/az/services/eventhub/EventHubHandler.java` (verificado: sin ruta `Microsoft.EventHub`) |
| Key Vault `/{account}-keyvault`, HTTPS, versionado; Entra OIDC RS256 sin refresh_token | `floci-az/docs/services/key-vault.md`; `floci-az/docs/services/entra.md` |
| Storage modes; `hybrid`; `FLOCI_AZ_STORAGE_PERSISTENT_PATH=/app/data` default | `floci-az/docs/configuration/storage.md`; `docs/configuration/docker-compose.md` |
| Well-known key Azurite | `floci-az/docs/configuration/docker-compose.md`; `floci-az/README.md` |
| IMDS/managed identity emulado | `floci-az/docs/services/managed-identity.md` |
| Compose oficial del contenedor (volúmenes, socket, red, envs) | `floci-az/docker-compose.yml` |
| Versión emulador 0.12.0 (2026-09-01) | `floci-az/CHANGELOG.md` |
| Módulos de infra + envs (estado local, parametrización) | `E:\Repo\K8s\infra\terraform\README.md`, `infra\terraform\modules\*`, `envs\dev\main.tf` |
| Overlay dev: env-config, secret-store-patch, estructura kustomize | `E:\Repo\K8s\cluster\overlays\dev\` |
| Policy `disallow-latest-tag` (Enforce; namespaces exentos) | `E:\Repo\K8s\cluster\base\kyverno\policies\disallow-latest-tag.yaml` |
| ESO ClusterSecretStore (azure/ManagedIdentity) | `E:\Repo\K8s\cluster\base\external-secrets\cluster-secret-store.yaml` |
| Contract test: prefix `acr.azurecr.io`, PSA, probes | `E:\Repo\K8s\tests\manifests\test-service-contract.py` (líneas 79-80 y 107) |
| H2 default; env `DB_HOST/DB_PORT/DB_NAME`; latent switches (cart Redis, rate limiter, Kafka consumer) | `services/catalog-svc/.../application.yml`, `services/config-service/.../config/cart-svc.yml`, `services/api-gateway/.../application.yml`, `services/notification-svc/.../NotificationConsumer.java` + `.../k8s/base/configmap.yaml` |
| auth con `keycloak-admin-*` desde Key Vault | `services/auth/k8s/base/external-secret.yaml` |
| Fallback in-cluster (CNPG, Bitnami Redis, Strimzi) | `E:\Repo\K8s\data\README.md` |

### 8.2 Por verificar (no confirmado en la fase de diseño)

| Ítem | Dónde se resuelve | Impacto si cambia |
|---|---|---|
| Tag Docker Hub `floci/floci-az:latest` == 0.12.0 | Fase 1 (`docker pull`) | Pin de versión en el compose |
| Nombre exacto del env para consumer groups de Event Hubs (`FLOCI_AZ_SERVICES_EVENT_HUB_CONSUMER_GROUPS` o creación por API) | Fase 2.5 | Bootstrap de Kafka; groupId de notification-svc |
| Ruta/inyección de `registries.yaml` en el k3s del emulador (¿usa `/etc/rancher/k3s/` estándar?) | Fase 3.3 | Mirror del registry; alternativa `{container}:5000` |
| `host.docker.internal` resuelve dentro del k3s (Docker Desktop WSL2) | Fase 3.3 / 4.1 | Prefix de imágenes del overlay |
| Provider ESO azure-go contra el Key Vault emulado (challenge/IMDS) | Fase 4.4.1 | Fallback secrets planos |
| Read de `azurerm_kubernetes_cluster` completo contra el ARM emulado (`kubelet_identity`/`node_resource_group`/`oidc_issuer_url`) | Fase 2.4 | Open issue upstream o degradación a B |
| Modo de auth del sidecar Redpanda (¿PLAINTEXT?) | Fase 2.5 | `KAFKA_SECURITY_PROTOCOL` del overlay |
| `enable_non_ssl_port=false` de `modules/databases` vs. Redis no-SSL de Floci | Fase 2.4 | Parametrizar el módulo (variable) |
| Zonas DNS públicas (`Microsoft.Network/dnsZones`) para external-dns | Fase 4 (descartado por diseño) | Re-introducir si el emulador las soporta |
| Hibernate crea esquema en Postgres emulado o requiere migraciones | Fase 2.6 / 5.2 | Script de schema en Fase 2.6