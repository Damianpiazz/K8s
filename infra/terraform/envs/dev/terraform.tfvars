# ---------------------------------------------------------------------------
# DEV environment — concrete example values.
#
# REPLACE the placeholder <...> values before running `terraform plan`.
# Never commit real passwords or secrets.
# ---------------------------------------------------------------------------

environment = "dev"
location    = "westeurope"
suffix      = "tppractico01"
env_num     = 10

tags = {
  project     = "ecommerce"
  environment = "dev"
  owner       = "student"
}

dns_prefix = "aksecommerce-dev-tppractico01" # must be globally unique

# --- Registry (dev: Basic SKU, admin enabled for CI/CD convenience) ---
acr_sku                        = "Basic"
acr_admin_enabled              = true
acr_public_network_access_enabled = true

# --- AKS (Free tier, small autoscaled pool) ---
kubernetes_version  = "1.30"
aks_sku_tier        = "Free"
aks_vm_size         = "Standard_B2s"
node_count_min      = 1
node_count_max      = 2
aks_autoscaling_enabled = true
enable_services_pool    = false

# Entra ID RBAC: set enable_aad to false to fall back to local accounts while
# learning, OR provide the object ID of the Entra group you will grant admin.
enable_aad             = true
admin_group_object_ids = ["<your-entra-group-object-id>"]

# --- PostgreSQL (Burstable B1s) ---
postgres_sku                        = "B_Standard_B1s"
postgres_version                    = "16"
postgres_storage_mb                 = 32768
postgres_public_network_access_enabled = true
postgres_allowed_ip_ranges          = []    # e.g. ["137.135.0.0/32"]
postgres_administrator_login        = "pgadmin" # REPLACE
postgres_administrator_password     = "<STRONG-PASSWORD>" # REPLACE, no '@' special chars
postgres_backup_retention_days      = 7
postgres_databases                  = ["orders", "payments", "catalog"]

# --- Redis (Basic, capacity 1) ---
redis_sku      = "Basic"
redis_capacity = 1
redis_family   = "C"

# --- Streaming (Event Hubs, Kafka-compatible) ---
eventhub_sku       = "Standard"
eventhub_capacity  = 1

# --- DNS (optional; leave "" to create nothing) ---
dns_zone_name = ""    # e.g. "dev.ecommerce.example.com"
