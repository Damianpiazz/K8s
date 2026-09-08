# ---------------------------------------------------------------------------
# STAGING environment — concrete example values.
#
# REPLACE the placeholder <...> values before running `terraform plan`.
# Never commit real passwords or secrets.
# ---------------------------------------------------------------------------

environment = "staging"
location    = "westeurope"
suffix      = "tppractico01"
env_num     = 20

tags = {
  project     = "ecommerce"
  environment = "staging"
  owner       = "student"
}

dns_prefix = "aksecommerce-staging-tppractico01" # must be globally unique

# --- Registry (Standard SKU; keep admin disabled for staging to mimic prod) ---
acr_sku                        = "Standard"
acr_admin_enabled              = false
acr_public_network_access_enabled = false

# --- AKS (Standard tier, medium autoscaled pools) ---
kubernetes_version  = "1.30"
aks_sku_tier        = "Standard"
aks_vm_size         = "Standard_D2s_v3"
node_count_min      = 2
node_count_max      = 4
aks_autoscaling_enabled = true
enable_services_pool    = true
services_pool_vm_size   = "Standard_D2s_v3"
services_pool_min       = 1
services_pool_max       = 3

# Entra ID RBAC on by default.
enable_aad             = true
admin_group_object_ids = ["<your-entra-group-object-id>"]

# --- PostgreSQL (General Purpose) ---
postgres_sku                        = "GP_Standard_D2s_v3"
postgres_version                    = "16"
postgres_storage_mb                 = 131072
postgres_public_network_access_enabled = false
postgres_allowed_ip_ranges          = ["<office-or-ci-cidr>/32"]
postgres_administrator_login        = "pgadmin" # REPLACE
postgres_administrator_password     = "<STRONG-PASSWORD>" # REPLACE, no '@' special chars
postgres_backup_retention_days      = 14
postgres_databases                  = ["orders", "payments", "catalog"]

# --- Redis (Standard, capacity 1) ---
redis_sku      = "Standard"
redis_capacity = 1
redis_family   = "C"

# --- Streaming (Event Hubs, Kafka-compatible) ---
eventhub_sku       = "Standard"
eventhub_capacity  = 2

# --- DNS (optional; leave "" to create nothing) ---
dns_zone_name = "staging.ecommerce.example.com"
