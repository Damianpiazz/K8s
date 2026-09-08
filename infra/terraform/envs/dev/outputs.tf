# ---------------------------------------------------------------------------
# DEV environment outputs (aggregated from modules).
# ---------------------------------------------------------------------------

output "resource_group_name" {
  description = "Resource group hosting the environment."
  value       = module.networking.resource_group_name
}

output "cluster_name" {
  description = "AKS cluster name."
  value       = module.aks.cluster_name
}

output "cluster_fqdn" {
  description = "AKS control-plane FQDN."
  value       = module.aks.fqdn
}

output "kubeconfig" {
  description = "Raw kubeconfig for the cluster (sensitive)."
  value       = module.aks.kubeconfig
  sensitive   = true
}

output "oidc_issuer_url" {
  description = "AKS OIDC issuer URL."
  value       = module.aks.oidc_issuer_url
}

output "acr_login_server" {
  description = "ACR login server."
  value       = module.registry.login_server
}

output "acr_admin_username" {
  description = "ACR admin username (dev convenience)."
  value       = module.registry.admin_username
  sensitive   = true
}

output "acr_admin_password" {
  description = "ACR admin password (dev convenience)."
  value       = module.registry.admin_password
  sensitive   = true
}

output "postgres_fqdn" {
  description = "PostgreSQL server FQDN."
  value       = module.databases.postgres_fqdn
  sensitive   = true
}

output "postgres_admin_user" {
  description = "PostgreSQL admin login."
  value       = module.databases.postgres_admin_user
  sensitive   = true
}

output "postgres_database_names" {
  description = "Databases created on PostgreSQL."
  value       = module.databases.postgres_database_names
}

output "redis_hostname" {
  description = "Redis cache hostname."
  value       = module.databases.redis_hostname
}

output "redis_port" {
  description = "Redis cache SSL port."
  value       = module.databases.redis_ssl_port
}

output "eventhub_namespace" {
  description = "Event Hubs namespace name."
  value       = module.streaming.namespace_name
}

output "eventhub_names" {
  description = "Event hub (Kafka topic) names."
  value       = module.streaming.eventhub_names
}

output "eventhub_connection_string" {
  description = "Event Hubs primary connection string (sensitive)."
  value       = module.streaming.connection_string
  sensitive   = true
}

output "dns_zone_name" {
  description = "DNS zone name (empty when disabled)."
  value       = module.dns.zone_name
}

output "dns_name_servers" {
  description = "DNS zone name servers."
  value       = module.dns.name_servers
}
