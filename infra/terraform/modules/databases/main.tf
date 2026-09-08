# ---------------------------------------------------------------------------
# Databases module
#
# Creates Azure Database for PostgreSQL Flexible Server (one database per
# microservice) and Azure Cache for Redis for sessions/cart.
# ---------------------------------------------------------------------------

variable "environment" {
  type        = string
  description = "Environment name used in resource naming."
}

variable "location" {
  type        = string
  description = "Azure region for all resources."
}

variable "suffix" {
  type        = string
  description = "Short unique suffix for resource names."
}

variable "resource_group_name" {
  type        = string
  description = "Resource group from the networking module."
}

variable "tags" {
  type        = map(string)
  description = "Tags applied to all created resources."
  default     = {}
}

# --- PostgreSQL -------------------------------------------------------------

variable "postgres_sku" {
  type        = string
  description = "PostgreSQL Flexible Server SKU. Example: B_Standard_B1s for dev, GP_Standard_D2s_v3 for prod."
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

variable "public_network_access_enabled" {
  type        = bool
  description = "Allow public network access to PostgreSQL. True is convenient for dev; production should use private endpoints."
  default     = true
}

variable "allowed_ip_ranges" {
  type        = list(string)
  description = "List of CIDR ranges allowed through the PostgreSQL firewall (in addition to Azure services). Empty by default."
  default     = []
}

variable "administrator_login" {
  type        = string
  description = "PostgreSQL administrator login name."
  sensitive   = true
}

variable "administrator_password" {
  type        = string
  description = "PostgreSQL administrator password (must meet Azure password policy)."
  sensitive   = true
}

variable "backup_retention_days" {
  type        = number
  description = "Number of days to retain PostgreSQL backups."
  default     = 7
}

variable "postgres_databases" {
  type        = list(string)
  description = "Databases to create on the PostgreSQL server, one per service. Example: ['orders', 'payments', 'catalog']."
  default     = ["orders", "payments", "catalog"]
}

# --- Redis ------------------------------------------------------------------

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

locals {
  postgres_name = "psql-${var.environment}-ecommerce-${var.suffix}"
  redis_name    = "redis-${var.environment}-ecommerce-${var.suffix}"
}

resource "azurerm_postgresql_flexible_server" "this" {
  name                         = local.postgres_name
  location                     = var.location
  resource_group_name          = var.resource_group_name
  version                      = var.postgres_version
  administrator_login          = var.administrator_login
  administrator_password       = var.administrator_password
  sku_name                     = var.postgres_sku
  storage_mb                   = var.postgres_storage_mb
  backup_retention_days        = var.backup_retention_days
  public_network_access_enabled = var.public_network_access_enabled
  tags                         = var.tags
}

# Convenience for dev: allow all Azure services through the firewall. Does not
# work unless public_network_access_enabled is true.
resource "azurerm_postgresql_flexible_server_firewall_rule" "azure_services" {
  count            = var.public_network_access_enabled ? 1 : 0
  name             = "AllowAzureServices"
  server_id        = azurerm_postgresql_flexible_server.this.id
  start_ip_address = "0.0.0.0"
  end_ip_address   = "0.0.0.0"
}

# Optional explicit IP ranges (dev workstation, CI runner, etc.).
resource "azurerm_postgresql_flexible_server_firewall_rule" "allowed_ip" {
  count            = length(var.allowed_ip_ranges) > 0 ? length(var.allowed_ip_ranges) : 0
  name             = "AllowedIP-${count.index}"
  server_id        = azurerm_postgresql_flexible_server.this.id
  start_ip_address = split("/", var.allowed_ip_ranges[count.index])[0]
  end_ip_address   = split("/", var.allowed_ip_ranges[count.index])[0]
}

# One database per microservice.
resource "azurerm_postgresql_flexible_server_database" "services" {
  count     = length(var.postgres_databases)
  name      = var.postgres_databases[count.index]
  server_id = azurerm_postgresql_flexible_server.this.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

resource "azurerm_redis_cache" "this" {
  name                = local.redis_name
  location            = var.location
  resource_group_name = var.resource_group_name
  capacity            = var.redis_capacity
  family              = var.redis_family
  sku_name            = var.redis_sku
  enable_non_ssl_port = false
  tags                = var.tags
}

output "postgres_server_id" {
  description = "Resource ID of the PostgreSQL Flexible server."
  value       = azurerm_postgresql_flexible_server.this.id
}

output "postgres_fqdn" {
  description = "FQDN of the PostgreSQL Flexible server."
  value       = azurerm_postgresql_flexible_server.this.fqdn
  sensitive   = true
}

output "postgres_admin_user" {
  description = "PostgreSQL administrator login."
  value       = azurerm_postgresql_flexible_server.this.administrator_login
  sensitive   = true
}

output "postgres_database_names" {
  description = "List of databases created on the PostgreSQL server."
  value       = azurerm_postgresql_flexible_server_database.services[*].name
}

output "redis_hostname" {
  description = "Redis cache hostname."
  value       = azurerm_redis_cache.this.hostname
}

output "redis_port" {
  description = "Redis cache non-SSL port."
  value       = azurerm_redis_cache.this.port
}

output "redis_ssl_port" {
  description = "Redis cache SSL port."
  value       = azurerm_redis_cache.this.ssl_port
}

output "redis_primary_key" {
  description = "Redis primary access key (sensitive)."
  value       = azurerm_redis_cache.this.primary_access_key
  sensitive   = true
}

output "resource_group" {
  description = "Resource group hosting the databases."
  value       = var.resource_group_name
}
