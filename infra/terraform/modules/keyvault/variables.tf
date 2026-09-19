# ---------------------------------------------------------------------------
# Key Vault module — variables.
#
# Seeds every secret that the cluster consumes through external-secrets:
#   - keycloak-admin-username / keycloak-admin-password (auth pod, start-dev)
#   - grafana-admin-password            (grafana-admin-credentials ExternalSecret)
#   - db-username / db-password         (db-credentials ExternalSecret — fix 30)
#   - argocd-oidc-client-secret         (oidc-argocd ExternalSecret, per overlay)
#   - grafana-oauth-client-secret       (grafana-oauth-credentials ES, per overlay)
#   - event-hubs-sasl-username/password (event-hubs-credentials ES, Kafka SASL)
#
# Passwords are random_password resources (no literals in code) EXCEPT the
# Postgres admin pair (fix 30): db-username/db-password come from the SAME
# tfvars variables the databases module consumes, so the cluster always gets
# the real server credentials (single source of truth, no drift).
# The two OIDC client secrets come from variables so they can stay in sync with
# the Keycloak realm (client.secret) — the default placeholder matches the realm
# shipped by the overlays, so the chain works out of the box and rotation is a
# var change.
# ---------------------------------------------------------------------------

variable "environment" {
  type        = string
  description = "Environment name used in resource naming."
}

variable "location" {
  type        = string
  description = "Azure region for the Key Vault."
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

variable "kubelet_identity_object_id" {
  type        = string
  description = "Object ID (principal) of the AKS kubelet system-assigned identity. external-secrets authenticates via IMDS with this identity (authType: ManagedIdentity, identityId = its client ID); we grant it 'Key Vault Secrets User' (data-plane) on the vault."
}

variable "event_hubs_connection_string" {
  type        = string
  description = "Event Hubs namespace primary connection string (from the streaming module). Stored as the SASL password for Kafka clients; the SASL username is the constant '$ConnectionString'."
  sensitive   = true
}

variable "keycloak_admin_username" {
  type        = string
  description = "Keycloak admin bootstrap username (start-dev)."
  default     = "admin"
}

variable "argocd_oidc_client_secret" {
  type        = string
  description = "Secret of the Keycloak client 'argocd'. MUST match the realm shipped by the overlay (client.secret) and the Kubernetes Secret oidc-argocd (clientSecret). Default equals the realm placeholder so everything syncs out of the box; set a real value to rotate."
  sensitive   = true
  default     = "REPLACE_WITH_A_SECURE_CLIENT_SECRET"
}

variable "grafana_oauth_client_secret" {
  type        = string
  description = "Secret of the Keycloak client 'grafana'. MUST match the realm (client.secret) and the Kubernetes Secret grafana-oauth-credentials (GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET). Default equals the realm placeholder."
  sensitive   = true
  default     = "REPLACE_WITH_A_SECURE_CLIENT_SECRET"
}

variable "postgres_administrator_login" {
  type        = string
  description = "PostgreSQL Flexible Server administrator login — the SAME value passed to the databases module. Seeded as KV secret 'db-username' (fix 30: services + Keycloak authenticate with the server admin credentials)."
  sensitive   = true
}

variable "postgres_administrator_password" {
  type        = string
  description = "PostgreSQL Flexible Server administrator password — the SAME value passed to the databases module. Seeded as KV secret 'db-password' (fix 30). Never a literal in manifests: external-secrets delivers it."
  sensitive   = true
}