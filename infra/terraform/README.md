# Terraform — Azure Infrastructure (AKS)

Infrastructure-as-Code for the e-commerce microservices platform on **Azure
AKS**. This directory provisions the base cloud resources the cluster and its
services depend on: networking, AKS, container registry, databases,
streaming and (optionally) DNS.

> This is a university practical project. Everything below is written so a
> student can go from `az login` to a running cluster in one session, with
> sensible defaults for `dev`, `staging` and `prod`.

---

## 1. What this creates

| Module | Azure resources | Purpose |
| --- | --- | --- |
| `modules/networking` | Resource group, VNet, AKS-delegated subnet, NSG with load-balancer rule | Network foundation; correct AKS rules (allow `AzureLoadBalancer`, deny other inbound) |
| `modules/aks` | AKS cluster (system identity, OIDC issuer, Entra ID RBAC optional, autoscaled node pool, optional `services` pool), ACR `AcrPull` role assignment | Kubernetes cluster with image pull access |
| `modules/registry` | Azure Container Registry (ACR) | Stores the microservice images pushed by CI/CD |
| `modules/databases` | Azure Database for PostgreSQL Flexible Server (one DB per service: `orders`, `payments`, `catalog`) + Azure Cache for Redis | Persistence and sessions/cart cache |
| `modules/streaming` | Event Hubs namespace with **Kafka compatibility** + event hubs (`order-events`, `payment-events`) | Asynchronous events between services using the Kafka wire protocol, without running Kafka |
| `modules/dns` (optional) | Azure DNS zone (created only when `dns_zone_name != ""`); records are managed by external-dns in-cluster later | Public DNS for the application |

Each environment folder (`envs/dev`, `envs/staging`, `envs/prod`) is a complete
Terraform root module that calls **every** module with its own values.

---

## 2. Prerequisites

- [Terraform](https://developer.hashicorp.com/terraform/downloads) >= 1.5
- Azure CLI (`az`) >= 2.50, logged in
- An Azure subscription you can create resources in

Chosen provider versions (see `versions.tf`):

- `azurerm ~> 4.0` — the Terraform provider version is a **minimum** constraint;
  run `terraform init -upgrade` to pick up the latest 4.x patch.

---

## 3. Quick start (dev environment)

```bash
# 1. Authenticate with Azure
az login
az account set --subscription "<your-subscription-id>"

# 2. Create the storage account + container that will hold remote state
#    (only once per environment). DO THIS BEFORE `terraform init`.
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

# 3. Record the storage account access key (Option B below references it):
az storage account keys list --account-name tfecommerce01 --resource-group tfstate-rg

# 4. Enter the environment folder
cd infra/terraform/envs/dev

# 5. Initialize with the remote backend:
terraform init \
  -backend-config="storage_account_name=tfecommerce01" \
  -backend-config="container_name=tfstate" \
  -backend-config="key=dev/ecommerce.terraform.tfstate" \
  -backend-config="access_key=<storage-account-access-key>"

# 6. Review and apply
terraform plan
terraform apply        # type 'yes' when satisfied
```

### Alternative: record the state locally (no backend)

If you do not want remote state during the practical (e.g. working offline or
at the exam), delete or rename `backend.tf` in the environment folder and run
`terraform init` again — Terraform falls back to a local `terraform.tfstate`.

---

## 4. What you must fill in before applying

| Item | Where | Notes |
| --- | --- | --- |
| Storage account name + access key | `envs/*/backend.tfvars.example` → `terraform init -backend-config=...` | The storage account must exist **before** init |
| `suffix` | per-env `terraform.tfvars` | Unique per student, e.g. `tppractico01`. Keeps names globally unique |
| `postgres_administrator_login/password` | per-env `terraform.tfvars` | Never commit real passwords. Use `.tfvars` files that are gitignored, or `TF_VAR_` environment variables |
| `admin_group_object_ids` (when `enable_aad = true`) | per-env `terraform.tfvars` | Object ID of the Entra ID group granted cluster-admin. `az ad group show --group <name> --query id -o tsv`. Or set `enable_aad = false` to use local accounts while learning |
| `dns_zone_name` | per-env `terraform.tfvars` | Leave `""` to create no DNS zone |

> **Secrets**: the repo root `.gitignore` already ignores `.terraform/`,
> `*.tfstate` and `.terraform.lock.hcl`, but **not** `*.tfvars` — add this
> block to the repo root `.gitignore` before committing real values:
>
> ```gitignore
> *.tfvars
> *.tfvars.local
> backend.tfvars
> ```
>
> Then provide credentials via a local `terraform.tfvars`, a
> `terraform.tfvars.local` file, `TF_VAR_*` environment variables, or
> `-var` flags — never commit them. The values shipped in these files are
> placeholders only.

---

## 5. Environment comparison

| | dev | staging | prod |
| --- | --- | --- | --- |
| VNet CIDR | `10.10.0.0/16` | `10.20.0.0/16` | `10.30.0.0/16` |
| AKS tier | Free | Standard | Standard (+ 3 AZs) |
| Default pool | `Standard_B2s`, 1–2 nodes | `Standard_D2s_v3`, 2–4 nodes | `Standard_D4s_v3`, 3–6 nodes |
| Services pool | off | on (1–3 nodes) | on (2–5 nodes) |
| ACR SKU | Basic, admin on | Standard, admin off | Standard, admin off |
| PostgreSQL | `B_Standard_B1s`, public + Azure services | `GP_Standard_D2s_v3`, private | `GP_Standard_D4s_v3`, private |
| Redis | Basic, capacity 1 | Standard, capacity 1 | Standard, capacity 2 |
| Event Hubs | Standard, 1 TU | Standard, 2 TU | Standard, 4 TU |
| DNS zone | off | `staging.…` | `ecommerce.example.com` |

Run each environment from **its own folder**; each has its own `terraform.tfvars`
and its own state key in the backend (`dev/…`, `staging/…`, `prod/…`).

---

## 6. Cost notes

- **dev**: roughly the cheapest possible while still being real Azure: Free-tier
  AKS (control plane free), one/two `Standard_B2s` nodes, Basic ACR, Burstable
  `B_Standard_B1s` PostgreSQL, Basic Redis. Expect a few euros per day at most.
- **staging / prod**: `Standard` SKUs with `Standard_D*` VMs are meaningfully
  more expensive. Delete environments you are not actively using
  (`terraform destroy` from the same folder) to avoid surprise bills.
- Stop the VMs / scale the node pool to 0 when the lab is not running; Azure
  bills by the hour for nodes, PostgreSQL and Redis.

---

## 7. Security notes

- **ACR `admin_enabled = true` is a DEV-ONLY convenience** so CI/CD can push
  with simple credentials. Disable it in staging/prod (already default there)
  and use Entra ID / workload identity instead.
- **AKS `local_account_disabled = true`** with Entra ID RBAC means cluster
  access is granted through Entra groups, not shared admin passwords. If you
  set `enable_aad = false` (learning mode), the cluster falls back to local
  accounts — acceptable for dev only.
- **`oidc_issuer_enabled = true`** is on for every env. It enables workload
  identity federation later (e.g. Argo CD SSO, service-to-service auth) without
  storing long-lived cloud credentials in the cluster.
- PostgreSQL/Redis in dev use public endpoints with firewall rules for
  convenience; staging/prod switch them to private (module variables), and the
  final production hardening (private endpoints, `network_policy` refinement)
  is covered in later phases.
- The NSG on the AKS subnet follows Azure's mandatory rule set: allow inbound
  from the `AzureLoadBalancer` service tag, deny everything else.

---

## 8. Modules cheat-sheet (how the wiring works)

```
networking (RG, VNet, subnet, NSG)
    │  resource_group_name, aks_subnet_id
    ▼
registry ──acr_id──► aks        ◄── databases, streaming, dns
    (ACR)            (cluster)       all share the same resource group
                         │
                         └── kubelet identity gets AcrPull on the ACR
```

Dependencies are expressed through module inputs (see each
`envs/*/main.tf`), so Terraform automatically creates the RG/VNet first, then
ACR, then the cluster, and grants pull access, etc.

## 9. Common issues

- **`azurerm` provider version mismatch** — run `terraform init -upgrade` once
  to resolve the `~> 4.0` constraint to the latest 4.x.
- **`dns_prefix` already in use** — AKS DNS prefixes must be globally unique;
  include the suffix, e.g. `aksecommerce-dev-tppractico01`.
- **PostgreSQL password rejected** — Azure forbids common/simple passwords and
  `'@'` in the administrator password; use a strong password without `'@'`.
- **Backend not found at init** — the storage account and container must exist
  before `terraform init` (see Quick start).