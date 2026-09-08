# ---------------------------------------------------------------------------
# DNS module (optional)
#
# Creates an Azure DNS zone only when a non-empty domain is supplied. Records
# are intentionally NOT managed here: external-dns running inside the cluster
# will create/update DNS records in this zone. Set `dns_zone_name = ""` to skip
# the module entirely (create nothing).
# ---------------------------------------------------------------------------

variable "environment" {
  type        = string
  description = "Environment name used in resource naming."
}

variable "location" {
  type        = string
  description = "Azure region (unused by DNS but kept for a consistent interface)."
  default     = "global"
}

variable "resource_group_name" {
  type        = string
  description = "Resource group from the networking module."
}

variable "dns_zone_name" {
  type        = string
  description = "DNS zone name, e.g. 'ecommerce.example.com'. Empty string creates nothing."
  default     = ""
}

locals {
  enabled = var.dns_zone_name != ""
}

resource "azurerm_dns_zone" "this" {
  count               = local.enabled ? 1 : 0
  name                = var.dns_zone_name
  resource_group_name = var.resource_group_name
  tags = {
    environment = var.environment
    component   = "dns"
  }
}

output "zone_name" {
  description = "Name of the DNS zone (empty string when disabled)."
  value       = local.enabled ? azurerm_dns_zone.this[0].name : ""
}

output "zone_id" {
  description = "Resource ID of the DNS zone (null when disabled)."
  value       = local.enabled ? azurerm_dns_zone.this[0].id : null
}

output "name_servers" {
  description = "Name servers assigned to the zone, to configure at your domain registrar."
  value       = local.enabled ? azurerm_dns_zone.this[0].name_servers : []
}
