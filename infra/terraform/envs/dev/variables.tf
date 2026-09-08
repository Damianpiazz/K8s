# ---------------------------------------------------------------------------
# DEV environment variables.
# ---------------------------------------------------------------------------

variable "environment" {
  type        = string
  description = "Environment name."
  default     = "dev"
}

variable "location" {
  type        = string
  description = "Azure region."
  default     = "westeurope"
}

variable "suffix" {
  type        = string
  description = "Short unique suffix for resource names."
}

variable "env_num" {
  type        = number
  description = "Numeric env id used to derive the VNet CIDR. dev=10."
}

variable "tags" {
  type        = map(string)
  description = "Common tags."
  default     = {}
}

variable "dns_prefix" {
  type        = string
  description = "AKS DNS prefix (globally unique)."
}

# Networking extras not surfaced by the module
variable "aks_subnet_cidr" {
  type        = string
  description = "Optional custom AKS subnet CIDR. Empty uses the default derived from env_num."
  default     = ""
}

# Registry
variable "acr_sku" {
  type        = string
  default     = "Basic"
}
variable "acr_admin_enabled" {
  type        = bool
  default     = true
}
variable "acr_public_network_access_enabled" {
  type        = bool
  default     = true
}

# AKS
variable "kubernetes_version" {
  type        = string
  default     = "1.30"
}
variable "aks_sku_tier" {
  type        = string
  default     = "Free"
}
variable "aks_vm_size" {
  type        = string
  default     = "Standard_B2s"
}
variable "node_count_min" {
  type        = number
  default     = 1
}
variable "node_count_max" {
  type        = number
  default     = 2
}
variable "aks_autoscaling_enabled" {
  type        = bool
  default     = true
}
variable "enable_aad" {
  type        = bool
  default     = true
}
variable "admin_group_object_ids" {
  type        = list(string)
  default     = []
}
variable "enable_services_pool" {
  type        = bool
  default     = false
}
variable "services_pool_vm_size" {
  type        = string
  default     = "Standard_B2s"
}
variable "services_pool_min" {
  type        = number
  default     = 1
}
variable "services_pool_max" {
  type        = number
  default     = 2
}

# Databases
variable "postgres_sku" {
  type        = string
  default     = "B_Standard_B1s"
}
variable "postgres_version" {
  type        = string
  default     = "16"
}
variable "postgres_storage_mb" {
  type        = number
  default     = 32768
}
variable "postgres_public_network_access_enabled" {
  type        = bool
  default     = true
}
variable "postgres_allowed_ip_ranges" {
  type        = list(string)
  default     = []
}
variable "postgres_administrator_login" {
  type        = string
  sensitive   = true
}
variable "postgres_administrator_password" {
  type        = string
  sensitive   = true
}
variable "postgres_backup_retention_days" {
  type        = number
  default     = 7
}
variable "postgres_databases" {
  type        = list(string)
  default     = ["orders", "payments", "catalog"]
}
variable "redis_sku" {
  type        = string
  default     = "Basic"
}
variable "redis_capacity" {
  type        = number
  default     = 1
}
variable "redis_family" {
  type        = string
  default     = "C"
}

# Streaming
variable "eventhub_sku" {
  type        = string
  default     = "Standard"
}
variable "eventhub_capacity" {
  type        = number
  default     = 1
}
variable "eventhubs" {
  type = map(object({
    partition_count   = number
    message_retention = number
  }))
  default = {
    "order-events" = {
      partition_count   = 3
      message_retention = 1
    }
    "payment-events" = {
      partition_count   = 3
      message_retention = 1
    }
  }
}

# DNS
variable "dns_zone_name" {
  type        = string
  default     = ""
}
