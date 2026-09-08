# ---------------------------------------------------------------------------
# STAGING environment — root module.
#
# Medium footprint: Standard-tier AKS, Standard SKU ACR, Standard Redis, a few
# nodes. Used to validate the full deployment before release.
# ---------------------------------------------------------------------------

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
  }

  # Remote state in Azure Storage. Configured in backend.tf.
}

provider "azurerm" {
  features {}
}

# ---------------------------------------------------------------------------
# Networking
# ---------------------------------------------------------------------------
module "networking" {
  source      = "../../modules/networking"
  environment = var.environment
  location    = var.location
  suffix      = var.suffix
  env_num     = var.env_num
  tags        = var.tags
}

# ---------------------------------------------------------------------------
# Registry
# ---------------------------------------------------------------------------
module "registry" {
  source                        = "../../modules/registry"
  environment                   = var.environment
  location                      = var.location
  suffix                        = var.suffix
  resource_group_name           = module.networking.resource_group_name
  tags                          = var.tags
  sku                           = var.acr_sku
  admin_enabled                 = var.acr_admin_enabled
  public_network_access_enabled = var.acr_public_network_access_enabled
}

# ---------------------------------------------------------------------------
# AKS
# ---------------------------------------------------------------------------
module "aks" {
  source                 = "../../modules/aks"
  environment            = var.environment
  location               = var.location
  suffix                 = var.suffix
  resource_group_name    = module.networking.resource_group_name
  aks_subnet_id          = module.networking.aks_subnet_id
  dns_prefix             = var.dns_prefix
  kubernetes_version     = var.kubernetes_version
  sku_tier               = var.aks_sku_tier
  vm_size                = var.aks_vm_size
  node_count_min         = var.node_count_min
  node_count_max         = var.node_count_max
  autoscaling_enabled    = var.aks_autoscaling_enabled
  enable_aad             = var.enable_aad
  admin_group_object_ids = var.admin_group_object_ids
  enable_services_pool   = var.enable_services_pool
  services_pool_vm_size  = var.services_pool_vm_size
  services_pool_min      = var.services_pool_min
  services_pool_max      = var.services_pool_max
  acr_id                 = module.registry.registry_id
}

# ---------------------------------------------------------------------------
# Databases
# ---------------------------------------------------------------------------
module "databases" {
  source                              = "../../modules/databases"
  environment                         = var.environment
  location                            = var.location
  suffix                              = var.suffix
  resource_group_name                 = module.networking.resource_group_name
  tags                                = var.tags
  postgres_sku                        = var.postgres_sku
  postgres_version                    = var.postgres_version
  postgres_storage_mb                 = var.postgres_storage_mb
  public_network_access_enabled       = var.postgres_public_network_access_enabled
  allowed_ip_ranges                   = var.postgres_allowed_ip_ranges
  administrator_login                 = var.postgres_administrator_login
  administrator_password              = var.postgres_administrator_password
  backup_retention_days               = var.postgres_backup_retention_days
  postgres_databases                  = var.postgres_databases
  redis_sku                           = var.redis_sku
  redis_capacity                      = var.redis_capacity
  redis_family                        = var.redis_family
}

# ---------------------------------------------------------------------------
# Streaming
# ---------------------------------------------------------------------------
module "streaming" {
  source             = "../../modules/streaming"
  environment        = var.environment
  location           = var.location
  suffix             = var.suffix
  resource_group_name = module.networking.resource_group_name
  tags               = var.tags
  namespace_sku      = var.eventhub_sku
  namespace_capacity = var.eventhub_capacity
  eventhubs          = var.eventhubs
}

# ---------------------------------------------------------------------------
# DNS (optional)
# ---------------------------------------------------------------------------
module "dns" {
  source             = "../../modules/dns"
  environment        = var.environment
  resource_group_name = module.networking.resource_group_name
  dns_zone_name      = var.dns_zone_name
}
