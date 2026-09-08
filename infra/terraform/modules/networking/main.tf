# ---------------------------------------------------------------------------
# Networking module
#
# Creates the resource group, VNet, AKS subnet (delegated to AKS) and an NSG
# that enforces Azure's required inbound rules for the AKS load balancer.
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

variable "env_num" {
  type        = number
  description = "Numeric environment id used to derive the VNet CIDR (10.<env_num>.0.0/16)."
}

variable "tags" {
  type        = map(string)
  description = "Tags applied to all created resources."
  default     = {}
}

variable "aks_subnet_cidr" {
  type        = string
  description = "CIDR for the AKS node subnet. Must be inside the VNet CIDR and must NOT overlap other subnets."
  default     = ""
}

locals {
  vnet_cidr      = "10.${var.env_num}.0.0/16"
  aks_subnet     = var.aks_subnet_cidr != "" ? var.aks_subnet_cidr : "10.${var.env_num}.1.0/24"
  resource_group = "rg-${var.environment}-ecommerce-${var.suffix}"
  vnet_name      = "vnet-${var.environment}-ecommerce-${var.suffix}"
  subnet_name    = "snet-aks-${var.environment}"
  nsg_name       = "nsg-aks-${var.environment}"
}

resource "azurerm_resource_group" "this" {
  name     = local.resource_group
  location = var.location
  tags     = var.tags
}

resource "azurerm_virtual_network" "this" {
  name                = local.vnet_name
  location            = var.location
  resource_group_name = azurerm_resource_group.this.name
  address_space       = [local.vnet_cidr]
  tags                = var.tags
}

# AKS node subnet, delegated to the AKS service so Kubernetes can manage it.
resource "azurerm_subnet" "aks" {
  name                 = local.subnet_name
  resource_group_name  = azurerm_resource_group.this.name
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = [local.aks_subnet]

  delegation {
    name = "aksDelegation"
    service_delegation {
      name    = "Microsoft.ContainerService/managedClusters"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

# Network security group enforcing Azure's mandatory LB rules on the AKS subnet.
resource "azurerm_network_security_group" "aks" {
  name                = local.nsg_name
  location            = var.location
  resource_group_name = azurerm_resource_group.this.name
  tags                = var.tags
}

# Required by Azure: allow inbound from the Azure Load Balancer (health probes
# and traffic when the cluster is in non-overlay mode). Service tag scopes this.
resource "azurerm_network_security_rule" "allow_lb" {
  name                        = "Allow-AzureLoadBalancer-Inbound"
  priority                    = 100
  direction                   = "Inbound"
  access                      = "Allow"
  protocol                    = "*"
  source_port_range           = "*"
  destination_port_range      = "*"
  source_address_prefix       = "AzureLoadBalancer"
  destination_address_prefix  = "*"
  resource_group_name         = azurerm_resource_group.this.name
  network_security_group_name = azurerm_network_security_group.aks.name
}

# Explicitly deny all other inbound traffic. Safety net: NSG on a delegated AKS
# subnet is largely managed by AKS, but an explicit deny documents intent.
resource "azurerm_network_security_rule" "deny_other_inbound" {
  name                        = "Deny-All-Other-Inbound"
  priority                    = 4096
  direction                   = "Inbound"
  access                      = "Deny"
  protocol                    = "*"
  source_port_range           = "*"
  destination_port_range      = "*"
  source_address_prefix       = "*"
  destination_address_prefix  = "*"
  resource_group_name         = azurerm_resource_group.this.name
  network_security_group_name = azurerm_network_security_group.aks.name
}

resource "azurerm_subnet_network_security_group_association" "aks" {
  subnet_id                 = azurerm_subnet.aks.id
  network_security_group_id = azurerm_network_security_group.aks.id
}

output "resource_group_name" {
  description = "Name of the resource group created for this environment."
  value       = azurerm_resource_group.this.name
}

output "resource_group_id" {
  description = "Resource ID of the resource group."
  value       = azurerm_resource_group.this.id
}

output "location" {
  description = "Azure region used for this environment."
  value       = var.location
}

output "vnet_id" {
  description = "Resource ID of the VNet."
  value       = azurerm_virtual_network.this.id
}

output "vnet_name" {
  description = "Name of the VNet."
  value       = azurerm_virtual_network.this.name
}

output "aks_subnet_id" {
  description = "Resource ID of the AKS-delegated subnet."
  value       = azurerm_subnet.aks.id
}
