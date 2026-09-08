# ---------------------------------------------------------------------------
# AKS module
#
# Creates an Azure Kubernetes Service cluster with system-assigned identity,
# Entra ID (Azure AD) RBAC optional, OIDC issuer, autoscaled default node pool,
# an optional dedicated "services" node pool, and wires ACR pull access to the
# cluster kubelet identity.
# ---------------------------------------------------------------------------

variable "environment" {
  type        = string
  description = "Environment name used in resource naming."
}

variable "location" {
  type        = string
  description = "Azure region for the cluster."
}

variable "suffix" {
  type        = string
  description = "Short unique suffix for resource names."
}

variable "resource_group_name" {
  type        = string
  description = "Resource group from the networking module that hosts the cluster."
}

variable "aks_subnet_id" {
  type        = string
  description = "Resource ID of the AKS-delegated subnet from the networking module."
}

variable "dns_prefix" {
  type        = string
  description = "DNS prefix for the cluster control plane. Must be globally unique within Azure."
}

variable "kubernetes_version" {
  type        = string
  description = "Kubernetes version for the cluster. Example: 1.30."
  default     = "1.30"
}

variable "sku_tier" {
  type        = string
  description = "AKS SKU tier. 'Free' for dev, 'Standard' for prod/HA."
  default     = "Free"
}

# --- Default node pool / autoscaling ---------------------------------------

variable "vm_size" {
  type        = string
  description = "VM size for the default node pool. Example: Standard_B2s for dev."
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

variable "autoscaling_enabled" {
  type        = bool
  description = "Enable cluster autoscaler on the default node pool."
  default     = true
}

# --- Entra ID RBAC ----------------------------------------------------------

variable "enable_aad" {
  type        = bool
  description = "Enable Entra ID (Azure AD) RBAC. When true, admin_group_object_ids grant cluster admin and local accounts are disabled. When false, local admin accounts are enabled (Azure CLI access works without AAD)."
  default     = true
}

variable "admin_group_object_ids" {
  type        = list(string)
  description = "List of Entra ID group object IDs granted cluster admin (rbac-admin). Required when enable_aad = true. Example: ['<your-entra-group-object-id>']."
  default     = []
}

# --- Optional secondary node pool (services) --------------------------------

variable "enable_services_pool" {
  type        = bool
  description = "Create a dedicated 'services' node pool for application workloads."
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

# --- ACR attach -------------------------------------------------------------

variable "acr_id" {
  type        = string
  description = "Resource ID of the Azure Container Registry to grant AcrPull to the cluster kubelet identity. Pass the registry module's acr_id output."
  default     = null
}

locals {
  cluster_name  = "aks-${var.environment}-ecommerce-${var.suffix}"
  node_rg       = "MC_${var.resource_group_name}_${local.cluster_name}_${var.location}"
  # Node pool names are limited to 12 lowercase alphanumeric chars, so we keep
  # a static short name instead of embedding the environment.
  services_pool = "services"
}

# AKS requires that the network and control plane coexist; the cluster is placed
# into the subnet created by the networking module.
resource "azurerm_kubernetes_cluster" "this" {
  name                = local.cluster_name
  location            = var.location
  resource_group_name = var.resource_group_name
  dns_prefix          = var.dns_prefix
  kubernetes_version  = var.kubernetes_version
  sku_tier            = var.sku_tier
  node_resource_group = local.node_rg

  # Good practice: disable local auth so the cluster can only be administered
  # via Entra ID. When AAD is disabled we fall back to local accounts.
  local_account_disabled = var.enable_aad

  default_node_pool {
    name                = "system"
    vm_size             = var.vm_size
    vnet_subnet_id      = var.aks_subnet_id
    zones               = var.sku_tier == "Standard" ? ["1", "2", "3"] : null
    enable_auto_scaling = var.autoscaling_enabled
    node_count          = var.autoscaling_enabled ? null : var.node_count_min
    node_count_min      = var.autoscaling_enabled ? var.node_count_min : null
    node_count_max      = var.autoscaling_enabled ? var.node_count_max : null
  }

  identity {
    type = "SystemAssigned"
  }

  network_profile {
    network_plugin    = "azure"
    network_policy    = "azure"
    load_balancer_sku = "standard"
  }

  oidc_issuer_enabled = true

  role_based_access_control_enabled = true
  azure_active_directory_role_based_access_control {
    managed                = true
    admin_group_object_ids = var.enable_aad ? var.admin_group_object_ids : []
    azure_rbac_enabled     = var.enable_aad
  }

  tags = {
    environment = var.environment
    component   = "aks"
  }
}

# Optional dedicated pool for application workloads, labelled role=services.
resource "azurerm_kubernetes_cluster_node_pool" "services" {
  count                 = var.enable_services_pool ? 1 : 0
  name                  = local.services_pool
  kubernetes_cluster_id = azurerm_kubernetes_cluster.this.id
  vm_size               = var.services_pool_vm_size
  vnet_subnet_id        = var.aks_subnet_id
  enable_auto_scaling   = true
  node_count_min        = var.services_pool_min
  node_count_max        = var.services_pool_max
  node_labels = {
    role = "services"
  }
  # Taints are intentionally disabled by default for simplicity. Uncomment to
  # dedicate the pool to workloads that tolerate the taint.
  # node_taints = ["workload=services:NoSchedule"]

  tags = {
    environment = var.environment
    component   = "aks-services"
  }
}

# Wire ACR pull access: grant the cluster kubelet system-assigned identity the
# AcrPull role on the container registry so nodes can pull images.
resource "azurerm_role_assignment" "acr_pull" {
  count                = var.acr_id != null ? 1 : 0
  scope                = var.acr_id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_kubernetes_cluster.this.kubelet_identity[0].object_id
}

output "cluster_name" {
  description = "Name of the AKS cluster."
  value       = azurerm_kubernetes_cluster.this.name
}

output "cluster_id" {
  description = "Resource ID of the AKS cluster."
  value       = azurerm_kubernetes_cluster.this.id
}

output "resource_group" {
  description = "Name of the resource group hosting the cluster."
  value       = var.resource_group_name
}

output "node_resource_group" {
  description = "Name of the auto-created AKS node resource group."
  value       = azurerm_kubernetes_cluster.this.node_resource_group
}

output "fqdn" {
  description = "Public FQDN of the cluster control plane."
  value       = azurerm_kubernetes_cluster.this.fqdn
}

output "kubelet_identity_id" {
  description = "Object ID of the cluster kubelet system-assigned identity (used for ACR pull)."
  value       = azurerm_kubernetes_cluster.this.kubelet_identity[0].object_id
}

output "kubeconfig" {
  description = "Raw kubeconfig for the cluster (sensitive)."
  value       = azurerm_kubernetes_cluster.this.kube_config_raw
  sensitive   = true
}

output "oidc_issuer_url" {
  description = "OIDC issuer URL of the cluster. Can be used for workload identity federation and Argo CD SSO later."
  value       = azurerm_kubernetes_cluster.this.oidc_issuer_url
}
