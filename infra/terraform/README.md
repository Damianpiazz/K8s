# Terraform — Infraestructura Azure (AKS)

Infrastructure-as-Code para la plataforma de microservicios e-commerce sobre
**Azure AKS**. Este directorio aprovisiona los recursos cloud base de los que
dependen el clúster y sus servicios: networking, AKS, container registry,
bases de datos, streaming y (opcionalmente) DNS.

> Esto es un proyecto práctico universitario. Todo lo de abajo está escrito
> para que un estudiante pueda ir de `az login` a un clúster corriendo en una
> sesión, con defaults sensatos para `dev`, `staging` y `prod`.

---

## 1. Qué crea esto

| Módulo | Recursos Azure | Propósito |
| --- | --- | --- |
| `modules/networking` | Resource group, VNet, subnet delegada a AKS, NSG con regla de load balancer | Base de red; reglas AKS correctas (permitir `AzureLoadBalancer`, denegar demás inbound) |
| `modules/aks` | Clúster AKS (system identity, issuer OIDC, RBAC Entra ID opcional, node pool con autoscaling, pool `services` opcional), role assignment `AcrPull` del ACR | Clúster Kubernetes con acceso de pull de imágenes |
| `modules/registry` | Azure Container Registry (ACR) | Guarda las imágenes de microservicios pusheadas por CI/CD |
| `modules/databases` | Azure Database for PostgreSQL Flexible Server (una DB por servicio: `orders`, `payments`, `catalog`) + Azure Cache for Redis | Persistencia y caché de sesiones/cart |
| `modules/streaming` | Namespace de Event Hubs con **compatibilidad Kafka** + event hubs (`order-events`, `payment-events`) | Eventos asíncronos entre servicios usando el wire protocol de Kafka, sin correr Kafka |
| `modules/dns` (opcional) | Zona Azure DNS (creada solo cuando `dns_zone_name != ""`); los registros los gestiona external-dns in-cluster después | DNS público para la aplicación |

Cada carpeta de ambiente (`envs/dev`, `envs/staging`, `envs/prod`) es un root
module completo de Terraform que llama **todos** los módulos con sus propios
valores.

---

## 2. Prerrequisitos

- [Terraform](https://developer.hashicorp.com/terraform/downloads) >= 1.5
- Azure CLI (`az`) >= 2.50, logueado
- Una suscripción de Azure en la que puedas crear recursos

Versiones de proveedor elegidas (ver `versions.tf`):

- `azurerm ~> 4.0` — la versión del proveedor de Terraform es una restricción
  **mínima**; corré `terraform init -upgrade` para tomar el último patch 4.x.

---

## 3. Quick start (ambiente dev)

```bash
# 1. Autenticación con Azure
az login
az account set --subscription "<your-subscription-id>"

# 2. Creá el storage account + container que va a tener el estado remoto
#    (solo una vez por ambiente). HACÉ ESTO ANTES de `terraform init`.
az group create --name tfstate-rg --location westeurope
az storage account create \
  --name tfecommerce01 \
  --resource-group tfstate-rg \
  --sku Standard_LRS \
  --allow-blob-public-access false
az storage container create \
  --name tfstate \
  --account-name tfecommerce01 \
  --auth-mode login

# 3. Registrá la access key del storage account (la Opción B de abajo la referencía):
az storage account keys list --account-name tfecommerce01 --resource-group tfstate-rg

# 4. Entrá a la carpeta del ambiente
cd infra/terraform/envs/dev

# 5. Inicializá con el backend remoto:
terraform init \
  -backend-config="storage_account_name=tfecommerce01" \
  -backend-config="container_name=tfstate" \
  -backend-config="key=dev/ecommerce.terraform.tfstate" \
  -backend-config="access_key=<storage-account-access-key>"

# 6. Revisá y aplicá
terraform plan
terraform apply        # tipeá 'yes' cuando estés conforme
```

### Alternativa: estado local (sin backend)

Si no querés estado remoto durante el práctico (p. ej. trabajando offline o
en el examen), borrá o renombrá `backend.tf` en la carpeta del ambiente y corré
`terraform init` de nuevo — Terraform cae al `terraform.tfstate` local.

---

## 4. Qué debes completar antes de aplicar

| Item | Dónde | Notas |
| --- | --- | --- |
| Nombre + access key del storage account | `envs/*/backend.tfvars.example` → `terraform init -backend-config=...` | El storage account debe existir **antes** del init |
| `suffix` | `terraform.tfvars` por env | Único por estudiante, p. ej. `tppractico01`. Mantiene los nombres globalmente únicos |
| `postgres_administrator_login/password` | `terraform.tfvars` por env | Nunca commitees passwords reales. Usá archivos `.tfvars` que estén en el gitignore, o variables de ambiente `TF_VAR_` |
| `admin_group_object_ids` (cuando `enable_aad = true`) | `terraform.tfvars` por env | Object ID del grupo de Entra ID con cluster-admin. `az ad group show --group <name> --query id -o tsv`. O seteá `enable_aad = false` para usar cuentas locales mientras aprendés |
| `dns_zone_name` | `terraform.tfvars` por env | Dejá `""` para no crear zona DNS |

> **Secretos**: el `.gitignore` de la raíz del repo ya ignora `.terraform/`,
> `*.tfstate` y `.terraform.lock.hcl`, pero **no** `*.tfvars` — agregá este
> bloque al `.gitignore` de la raíz antes de committear valores reales:
>
> ```gitignore
> *.tfvars
> *.tfvars.local
> backend.tfvars
> ```
>
> Después proveé credenciales via un `terraform.tfvars` local, un archivo
> `terraform.tfvars.local`, variables de ambiente `TF_VAR_*`, o flags
> `-var` — nunca las commitees. Los valores incluidos en estos archivos son
> solo placeholders.

---

## 5. Comparación de ambientes

| | dev | staging | prod |
| --- | --- | --- | --- |
| VNet CIDR | `10.10.0.0/16` | `10.20.0.0/16` | `10.30.0.0/16` |
| AKS tier | Free | Standard | Standard (+ 3 AZs) |
| Pool default | `Standard_B2s`, 1–2 nodos | `Standard_D2s_v3`, 2–4 nodos | `Standard_D4s_v3`, 3–6 nodos |
| Pool services | off | on (1–3 nodos) | on (2–5 nodos) |
| ACR SKU | Basic, admin on | Standard, admin off | Standard, admin off |
| PostgreSQL | `B_Standard_B1s`, público + servicios Azure | `GP_Standard_D2s_v3`, privado | `GP_Standard_D4s_v3`, privado |
| Redis | Basic, capacity 1 | Standard, capacity 1 | Standard, capacity 2 |
| Event Hubs | Standard, 1 TU | Standard, 2 TU | Standard, 4 TU |
| Zona DNS | off | `staging.…` | `ecommerce.example.com` |

Corré cada ambiente desde **su propia carpeta**; cada uno tiene su propio
`terraform.tfvars` y su propia key de estado en el backend (`dev/…`,
`staging/…`, `prod/…`).

---

## 6. Notas de costo

- **dev**: aproximadamente lo más barato posible siendo Azure real: AKS
  Free-tier (control plane gratis), uno/dos nodos `Standard_B2s`, ACR Basic,
  PostgreSQL Burstable `B_Standard_B1s`, Redis Basic. Esperá pocos euros por
  día como máximo.
- **staging / prod**: los SKUs `Standard` con VMs `Standard_D*` son
  significativamente más caros. Borrá los ambientes que no estés usando
  activamente (`terraform destroy` desde la misma carpeta) para evitar
  facturas sorpresa.
- Pará las VMs / escalá el node pool a 0 cuando el laboratorio no esté
  corriendo; Azure factura por hora por nodos, PostgreSQL y Redis.

---

## 7. Notas de seguridad

- **El `admin_enabled = true` del ACR es una conveniencia SOLO dev** para que
  el CI/CD pueda pushear con credenciales simples. Deshabilitarlo en
  staging/prod (ya es default ahí) y usar Entra ID / workload identity.
- **`local_account_disabled = true` del AKS** con RBAC de Entra ID significa
  que el acceso al clúster se otorga a través de grupos de Entra, no con
  passwords de admin compartidos. Si seteás `enable_aad = false` (modo
  aprendizaje), el clúster cae a cuentas locales — aceptable solo para dev.
- **`oidc_issuer_enabled = true`** está activo para todos los ambientes.
  Habilita la federación de workload identity después (p. ej. SSO de Argo CD,
  auth servicio-a-servicio) sin guardar credenciales cloud de larga vida en el
  clúster.
- PostgreSQL/Redis en dev usan endpoints públicos con firewall rules por
  conveniencia; staging/prod los cambia a privados (variables del módulo), y el
  endurecimiento final de producción (private endpoints, refinamiento de
  `network_policy`) se cubre en fases posteriores.
- El NSG en el subnet de AKS sigue el set de reglas obligatorio de Azure:
  permitir inbound del service tag `AzureLoadBalancer`, denegar todo lo demás.

---

## 8. Cheat-sheet de módulos (cómo funciona el wiring)

```
networking (RG, VNet, subnet, NSG)
    │  resource_group_name, aks_subnet_id
    ▼
registry ──acr_id──► aks        ◄── databases, streaming, dns
    (ACR)            (cluster)       todos comparten el mismo resource group
                         │
                         └── la kubelet identity recibe AcrPull sobre el ACR
```

Las dependencias se expresan a través de inputs de módulos (ver cada
`envs/*/main.tf`), así que Terraform crea automáticamente el RG/VNet primero,
después el ACR, después el clúster, y otorga el acceso de pull, etc.

## 9. Problemas comunes

- **Mismatch de versión del proveedor `azurerm`** — corré `terraform init -upgrade`
  una vez para resolver la restricción `~> 4.0` al último 4.x.
- **`dns_prefix` ya en uso** — los prefijos DNS de AKS deben ser globalmente
  únicos; incluí el suffix, p. ej. `aksecommerce-dev-tppractico01`.
- **Password de PostgreSQL rechazado** — Azure prohíbe passwords comunes o
  simples y el `'@'` en el password del administrador; usá un password fuerte
  sin `'@'`.
- **Backend no encontrado en el init** — el storage account y el container
  deben existir antes de `terraform init` (ver Quick start).