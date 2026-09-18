# ---------------------------------------------------------------------------
# Provider azurerm — apunta al emulador Floci-AZ (NO a Azure real).
#
# Floci se conecta con la configuracion "stack" + metadata_host:
#   - environment = "stack"  (NO "public" ni endpoint)
#   - metadata_host = "localhost:4577"
# Las credenciales son FAKE — el emulador no valida (terraform.md).
#
# Importante: NO existe backend.tf en este workspace a proposito. El estado
# se guarda localmente (envs/local/terraform.tfstate) — simil minikube/kind.
# ---------------------------------------------------------------------------

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      # Alineado al pin del repo (~>4.0 en versions.tf). Los modulos de
      # infra/terraform/modules tuvieron drift de sintaxis (args v2/v3:
      # enable_auto_scaling, node_count_min/max, identity.managed,
      # enable_non_ssl_port) - corregidos a los nombres v4 durante la puesta
      # en marcha local contra Floci.
      version = "~> 4.0"
    }
  }
}

provider "azurerm" {
  features {}

  # Emulador Floci-AZ
  environment                = "stack"
  metadata_host              = "localhost:4577"
  skip_provider_registration = true
  use_cli                    = false

  # Credenciales fake — el emulador no valida (solo requiere formato UUID)
  subscription_id = "00000000-0000-0000-0000-000000000001"
  tenant_id       = "00000000-0000-0000-0000-000000000002"
  client_id       = "00000000-0000-0000-0000-000000000003"
  client_secret   = "fake-secret"
}