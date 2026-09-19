# ---------------------------------------------------------------------------
# Key Vault module — outputs.
# ---------------------------------------------------------------------------

output "keyvault_name" {
  description = "Name of the Key Vault."
  value       = azurerm_key_vault.this.name
}

output "keyvault_id" {
  description = "Resource ID of the Key Vault."
  value       = azurerm_key_vault.this.id
}

output "keyvault_vault_url" {
  description = "Vault URI (https://<kv-name>.vault.azure.net) used by the ClusterSecretStore overlay patch."
  value       = azurerm_key_vault.this.vault_uri
}

output "keyvault_tenant_id" {
  description = "Tenant ID the vault belongs to (used by the ClusterSecretStore tenantId)."
  value       = azurerm_key_vault.this.tenant_id
}

output "secret_names" {
  description = "Names of the secrets seeded in the Key Vault (each matches an ExternalSecret remoteRef key)."
  value       = [
    "keycloak-admin-username",
    "keycloak-admin-password",
    "grafana-admin-password",
    "db-password",
    "argocd-oidc-client-secret",
    "grafana-oauth-client-secret",
    "event-hubs-sasl-username",
    "event-hubs-sasl-password",
  ]
}