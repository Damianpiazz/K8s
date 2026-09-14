# ---------------------------------------------------------------------------
# Valores LOCALES — emulador Floci-AZ (Fase 2 / punto 2.1 del plan).
#
# Postgres:  publico + firewall abierto (0.0.0.0/0) — SOLO LOCAL.
# Redis:     Basic capacity 1.
# ACR:       Basic, admin habilitado (pull/push local).
# AKS:       1 nodo, sin autoscaling, sin AAD, sin services pool.
# acr_id:    null (desvio 3 — evita Microsoft.Authorization en el emulador).
# ---------------------------------------------------------------------------

environment = "local"
location    = "eastus"
suffix      = "floci01"
env_num     = 90
tags = {
  environment = "local"
  component   = "ecommerce"
  managed-by  = "terraform"
}

# --- AKS ---------------------------------------------------------------------
dns_prefix              = "floci"
kubernetes_version      = "1.30"
aks_sku_tier            = "Free"
aks_vm_size             = "Standard_B2s"
node_count_min          = 1
node_count_max          = 1
aks_autoscaling_enabled = false
enable_aad              = false
admin_group_object_ids  = []
enable_services_pool    = false

# Desvio 3: sin role assignment Microsoft.Authorization contra el emulador.
acr_id = null

# --- Registry ----------------------------------------------------------------
acr_sku                           = "Basic"
acr_admin_enabled                 = true
acr_public_network_access_enabled = true

# --- Databases: PostgreSQL ---------------------------------------------------
postgres_sku                           = "B_Standard_B1s"
postgres_version                       = "16"
postgres_storage_mb                    = 32768
postgres_public_network_access_enabled = true
postgres_allowed_ip_ranges             = ["0.0.0.0/0"] # SOLO LOCAL
postgres_administrator_login           = "psqladmin"
postgres_administrator_password        = "<fuerte-sin-@>" # NUNCA committear el real
postgres_backup_retention_days         = 7
postgres_databases                     = ["orders", "payments", "catalog"]

# --- Databases: Redis --------------------------------------------------------
redis_sku      = "Basic"
redis_capacity = 1
redis_family   = "C"