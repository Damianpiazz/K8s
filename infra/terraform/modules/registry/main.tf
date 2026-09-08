# ---------------------------------------------------------------------------
# Registry module
#
# Creates an Azure Container Registry (ACR) and exposes login credentials.
# `admin_enabled` is enabled as a DEV/NURSE convenience for CI/CD push access.
# For production, disable it and use Entra ID / workload identity instead.
# ---------------------------------------------------------------------------

variable "environment" {
  type        = string
  description = "Environment name used in resource naming."
}

variable "location" {
  type        = string
  description = "Azure region for the registry."
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

variable "sku" {
  type        = string
  description = "ACR SKU: Basic, Standard, or Premium. Basic suits dev."
  default     = "Basic"
}

variable "admin_enabled" {
  type        = bool
  description = "Enable the registry admin user. DEV-ONLY convenience for CI/CD; production should use Entra/workload identity."
  default     = true
}

variable "public_network_access_enabled" {
  type        = bool
  description = "Allow public network access to the registry. Disable for private/prod."
  default     = true
}

locals {
  registry_name = replace("${var.environment}-ecommerce-${var.suffix}", "-", "") # ACR names must be alphanumeric
}

resource "azurerm_container_registry" "this" {
  name                = local.registry_name
  location            = var.location
  resource_group_name = var.resource_group_name
  sku                 = var.sku
  admin_enabled       = var.admin_enabled
  public_network_access_enabled = var.public_network_access_enabled
  tags                = var.tags
}

output "registry_name" {
  description = "Name of the container registry."
  value       = azurerm_container_registry.this.name
}

output "registry_id" {
  description = "Resource ID of the container registry."
  value       = azurerm_container_registry.this.id
}

output "login_server" {
  description = "Login server (registry endpoint) for pushing/pulling images."
  value       = azurerm_container_registry.this.login_server
}

output "admin_username" {
  description = "Registry admin username (only when admin_enabled = true)."
  value       = var.admin_enabled ? azurerm_container_registry.this.admin_username : null
  sensitive   = true
}

output "admin_password" {
  description = "Registry admin password (only when admin_enabled = true)."
  value       = var.admin_enabled ? azurerm_container_registry.this.admin_password : null
  sensitive   = true
}

output "resource_group" {
  description = "Resource group hosting the registry."
  value       = var.resource_group_name
}
