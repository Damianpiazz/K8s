# ---------------------------------------------------------------------------
# Key Vault module — resources.
#
# RBAC authorization model (enable_rbac_authorization): the vault issues NO
# access policies; authorization is role assignments on the vault scope.
#   - 'Key Vault Secrets User'  → AKS kubelet identity (consumed by
#     external-secrets via IMDS, authType: ManagedIdentity).
#
# The overlay's ClusterSecretStore patch (cluster/overlays/<env>/patches/
# secret-store-patch.yaml) references keyvault_vault_url + tenant id +
# kubelet_identity_client_id (see outputs).
# ---------------------------------------------------------------------------

data "azurerm_client_config" "current" {}

locals {
  vault_name = "kv-${var.environment}-ecommerce-${var.suffix}"
}

resource "azurerm_key_vault" "this" {
  name                       = local.vault_name
  location                   = var.location
  resource_group_name        = var.resource_group_name
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  sku_name                   = "standard"
  soft_delete_retention_days = 7
  purge_protection_enabled   = false
  enable_rbac_authorization  = true
  tags                       = var.tags
}

# external-secrets (controller pod on AKS) authenticates with the node's
# system-assigned identity through IMDS (authType: ManagedIdentity). Granting
# the kubelet identity the data-plane reader role on the vault is the whole
# authorization — no access policies needed.
resource "azurerm_role_assignment" "eso_kv_secrets_user" {
  scope                = azurerm_key_vault.this.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = var.kubelet_identity_object_id
}

# --- Passwords: random, no literals. special=false keeps values YAML/URI-safe.
resource "random_password" "keycloak_admin" {
  length           = 20
  min_upper        = 1
  min_lower        = 1
  min_numeric      = 1
  special          = false
}

resource "random_password" "grafana_admin" {
  length           = 20
  min_upper        = 1
  min_lower        = 1
  min_numeric      = 1
  special          = false
}

# --- Secrets ----------------------------------------------------------------

resource "azurerm_key_vault_secret" "keycloak_admin_username" {
  name         = "keycloak-admin-username"
  value        = var.keycloak_admin_username
  key_vault_id = azurerm_key_vault.this.id
}

resource "azurerm_key_vault_secret" "keycloak_admin_password" {
  name         = "keycloak-admin-password"
  value        = random_password.keycloak_admin.result
  key_vault_id = azurerm_key_vault.this.id
}

resource "azurerm_key_vault_secret" "grafana_admin_password" {
  name         = "grafana-admin-password"
  value        = random_password.grafana_admin.result
  key_vault_id = azurerm_key_vault.this.id
}

# Postgres admin credentials — SINGLE source of truth para los servicios
# (datasource + Keycloak, fix 30): el MISMO valor que el módulo databases
# recibe como postgres_administrator_*. El usuario fija la password UNA vez en
# terraform.tfvars; KV la siembra y external-secrets la entrega (nunca literal
# en manifests).
resource "azurerm_key_vault_secret" "db_username" {
  name         = "db-username"
  value        = var.postgres_administrator_login
  key_vault_id = azurerm_key_vault.this.id
}

resource "azurerm_key_vault_secret" "db_password" {
  name         = "db-password"
  value        = var.postgres_administrator_password
  key_vault_id = azurerm_key_vault.this.id
}

# SSO client secrets. Sourced from variables so they stay in sync with the
# Keycloak realm client.secret (see fix 29 — realm/secret sync contract).
resource "azurerm_key_vault_secret" "argocd_oidc_client_secret" {
  name         = "argocd-oidc-client-secret"
  value        = var.argocd_oidc_client_secret
  key_vault_id = azurerm_key_vault.this.id
}

resource "azurerm_key_vault_secret" "grafana_oauth_client_secret" {
  name         = "grafana-oauth-client-secret"
  value        = var.grafana_oauth_client_secret
  key_vault_id = azurerm_key_vault.this.id
}

# Event Hubs Kafka SASL. Event Hubs SASL is username='$ConnectionString',
# password = the namespace connection string (fixed convention of the Kafka
# endpoint of Azure Event Hubs).
resource "azurerm_key_vault_secret" "event_hubs_sasl_username" {
  name         = "event-hubs-sasl-username"
  value        = "$ConnectionString"
  key_vault_id = azurerm_key_vault.this.id
}

resource "azurerm_key_vault_secret" "event_hubs_sasl_password" {
  name         = "event-hubs-sasl-password"
  value        = var.event_hubs_connection_string
  key_vault_id = azurerm_key_vault.this.id
}