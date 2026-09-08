# ---------------------------------------------------------------------------
# STAGING environment — concrete example values.
#
# REPLACE the placeholder <...> values before running `terraform plan`.
# Never commit real passwords or secrets.
# ---------------------------------------------------------------------------

environment = "prod"
location    = "westeurope"
suffix      = "tppractico01"
env_num     = 30

tags = {
  project     = "ecommerce"
  environment = "prod"
  owner       = "student"
}

dns_prefix = "aksecommerce-prod-tppractico01" # must be globally unique

# --- Registry (Standard SKU; admin disabled for prod) ---
acr_sku                        = "Standard"
acr_admin_enabled              = false
acr_public_network_access_enabled = false

# --- AKS (Standard tier, zonal HA, larger pools) ---
kubernetes_version  = "1.30"
aks_sku_tier        = "Standard"
aks_vm_size         = "Standard_D4s_v3"
node_count_min      = 3
node_count_max      = 6
aks_autoscaling_enabled = true
enable_services_pool    = true
services_pool_vm_size   = "Standard_D4s_v3"
services_pool_min       = 2
services_pool_max       = 5

# Entra ID RBAC with a dedicated privileged cluster-admin group.
enable_aad             = true
admin_group_object_ids = ["<your-entra-group-object-id>"]

# --- PostgreSQL (General Purpose, larger storage & backups) ---
postgres_sku                        = "GP_Standard_D4s_v3"
postgres_version                    = "16"
postgres_storage_mb                 = 262144
postgres_public_network_access_enabled = false
postgres_allowed_ip_ranges          = ["<office-or-cicd-cidr>/32"]
postgres_administrator_login        = "pgadmin" # REPLACE
postgres_administrator_password     = "<STRONG-PASSWORD>" # REPLACE, no '@' special chars
postgres_backup_retention_days      = 30
postgres_databases                  = ["orders", "payments", "catalog"]

# --- Redis (Standard, capacity 2, more throughput) ---
redis_sku      = "Standard"
redis_capacity = 2
redis_family   = "C"

# --- Streaming (Event Hubs, Kafka-compatible, more capacity) ---
eventhub_sku       = "Standard"
eventhub_capacity  = 4

# --- DNS (optional; zone only — external-dns manages records in-cluster) ---
dns_zone_name = "ecommerce.example.com"
