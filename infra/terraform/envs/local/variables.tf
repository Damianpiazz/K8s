# ---------------------------------------------------------------------------
# Variables — LOCAL (Floci-AZ).
# Solo las variables que usa main.tf de este workspace. Los valores se
# definen en terraform.tfvars.
# ---------------------------------------------------------------------------

variable "environment" {
  type        = string
  description = "Environment name used in resource naming."
}

variable "location" {
  type        = string
  description = "Azure region for all resources (solo etiqueta en floci)."
}

variable "suffix" {
  type        = string
  description = "Short unique suffix for resource names."
}

variable "env_num" {
  type        = number
  description = "Numeric environment id used to derive the VNet CIDR (10.<env_num>.0.0/16)."
}

variable "aks_subnet_cidr" {
  type        = string
  description = "CIDR for the AKS node subnet. Empty derives 10.<env_num>.1.0/24."
  default     = ""
}

variable "tags" {
  type        = map(string)
  description = "Tags applied to all created resources."
  default     = {}
}

# --- Registry ---------------------------------------------------------------

variable "acr_sku" {
  type        = string
  description = "ACR SKU: Basic, Standard, or Premium."
  default     = "Basic"
}

variable "acr_admin_enabled" {
  type        = bool
  description = "Enable the registry admin user (dev convenience)."
  default     = true
}

variable "acr_public_network_access_enabled" {
  type        = bool
  description = "Allow public network access to the registry."
  default     = true
}

# --- AKS --------------------------------------------------------------------

variable "dns_prefix" {
  type        = string
  description = "DNS prefix for the cluster control plane."
}

variable "kubernetes_version" {
  type        = string
  description = "Kubernetes version for the cluster."
  default     = "1.30"
}

variable "aks_sku_tier" {
  type        = string
  description = "AKS SKU tier. 'Free' for dev, 'Standard' for prod/HA."
  default     = "Free"
}

variable "aks_vm_size" {
  type        = string
  description = "VM size for the default node pool."
  default     = "Standard_B2s"
}

variable "node_count_min" {
  type        = number
  description = "Minimum node count for the default node pool autoscaler."
  default     = 1
}

variable "node_count_max" {
  type        = number
  description = "Maximum node count for the default node pool autoscaler."
  default     = 3
}

variable "aks_autoscaling_enabled" {
  type        = bool
  description = "Enable cluster autoscaler on the default node pool."
  default     = true
}

variable "enable_aad" {
  type        = bool
  description = "Enable Entra ID RBAC. False for local (fallback a cuentas locales)."
  default     = false
}

variable "admin_group_object_ids" {
  type        = list(string)
  description = "Entra ID group object IDs granted cluster admin. Empty when enable_aad = false."
  default     = []
}

variable "enable_services_pool" {
  type        = bool
  description = "Create a dedicated 'services' node pool."
  default     = false
}

variable "services_pool_vm_size" {
  type        = string
  description = "VM size for the optional services node pool."
  default     = "Standard_B2s"
}

variable "services_pool_min" {
  type        = number
  description = "Minimum nodes for the services node pool autoscaler."
  default     = 1
}

variable "services_pool_max" {
  type        = number
  description = "Maximum nodes for the services node pool autoscaler."
  default     = 2
}

# Desvio 3: null evita azurerm_role_assignment (Microsoft.Authorization).
variable "acr_id" {
  type        = string
  description = "ACR resource id to grant AcrPull to the cluster kubelet identity. null = skip the role assignment (local)."
  default     = null
}

# --- Databases --------------------------------------------------------------

variable "postgres_sku" {
  type        = string
  description = "PostgreSQL Flexible Server SKU."
  default     = "B_Standard_B1s"
}

variable "postgres_version" {
  type        = string
  description = "PostgreSQL server version."
  default     = "16"
}

variable "postgres_storage_mb" {
  type        = number
  description = "Storage size in MB for the PostgreSQL server."
  default     = 32768
}

variable "postgres_public_network_access_enabled" {
  type        = bool
  description = "Allow public network access to PostgreSQL."
  default     = true
}

variable "postgres_allowed_ip_ranges" {
  type        = list(string)
  description = "CIDR ranges allowed through the PostgreSQL firewall."
  default     = []
}

variable "postgres_administrator_login" {
  type        = string
  description = "PostgreSQL administrator login name."
  sensitive   = true
}

variable "postgres_administrator_password" {
  type        = string
  description = "PostgreSQL administrator password (NUNCA committear el real)."
  sensitive   = true
}

variable "postgres_backup_retention_days" {
  type        = number
  description = "Number of days to retain PostgreSQL backups."
  default     = 7
}

variable "postgres_databases" {
  type        = list(string)
  description = "Databases to create on the PostgreSQL server."
  default     = ["orders", "payments", "catalog"]
}

variable "redis_sku" {
  type        = string
  description = "Redis cache SKU: Basic, Standard, or Premium."
  default     = "Basic"
}

variable "redis_capacity" {
  type        = number
  description = "Redis cache capacity (1-6 depending on SKU family)."
  default     = 1
}

variable "redis_family" {
  type        = string
  description = "Redis cache family: C (Basic/Standard) or P (Premium)."
  default     = "C"
}