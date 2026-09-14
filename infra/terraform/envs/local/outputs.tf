# ---------------------------------------------------------------------------
# Outputs — LOCAL (Floci-AZ).
#
# Los comandos de verificacion del plan (Fase 2.3) usan:
#   terraform output -raw kubeconfig
#   terraform output -raw registry_name
#   terraform output -raw redis_primary_access_key
#
# Nota: si el emulador no devuelve algun atributo (p.ej. fqdn / oidc_issuer_url),
# `terraform output` puede fallar — es un error esperado por verificar.
# ---------------------------------------------------------------------------

output "resource_group_name" {
  description = "Name of the resource group."
  value       = module.networking.resource_group_name
}

# --- AKS ---------------------------------------------------------------------

output "cluster_name" {
  description = "Name of the AKS cluster."
  value       = module.aks.cluster_name
}

output "kubeconfig" {
  description = "Raw kubeconfig for the cluster (sensitive)."
  value       = module.aks.kubeconfig
  sensitive   = true
}

output "oidc_issuer_url" {
  description = "OIDC issuer URL of the cluster (por verificar contra floci)."
  value       = module.aks.oidc_issuer_url
}

# --- Registry ----------------------------------------------------------------

output "registry_name" {
  description = "Name of the container registry (para docker build/push)."
  value       = module.registry.registry_name
}

output "registry_login_server" {
  description = "Login server (registry endpoint) for pushing/pulling images."
  value       = module.registry.login_server
}

output "registry_admin_username" {
  description = "Registry admin username (sensitive)."
  value       = module.registry.admin_username
  sensitive   = true
}

output "registry_admin_password" {
  description = "Registry admin password (sensitive)."
  value       = module.registry.admin_password
  sensitive   = true
}

# --- Databases: PostgreSQL ---------------------------------------------------

output "postgres_fqdn" {
  description = "FQDN of the PostgreSQL Flexible server."
  value       = module.databases.postgres_fqdn
  sensitive   = true
}

output "postgres_admin_user" {
  description = "PostgreSQL administrator login."
  value       = module.databases.postgres_admin_user
  sensitive   = true
}

output "postgres_database_names" {
  description = "List of databases created on the PostgreSQL server."
  value       = module.databases.postgres_database_names
}

# --- Databases: Redis --------------------------------------------------------

output "redis_hostname" {
  description = "Redis cache hostname."
  value       = module.databases.redis_hostname
}

output "redis_ssl_port" {
  description = "Redis cache SSL port (emulado)."
  value       = module.databases.redis_ssl_port
}

output "redis_primary_access_key" {
  description = "Redis primary access key (sensitive)."
  value       = module.databases.redis_primary_key
  sensitive   = true
}