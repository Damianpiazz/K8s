# ---------------------------------------------------------------------------
# Shared root outputs (reference template).
#
# This file documents the full aggregate output surface that every environment
# root module produces. The real values come from the per-env modules, so the
# environment root modules (envs/<env>/outputs.tf) each contain a copy of
# these `output` blocks fed by their own module calls.
#
# The local variable declarations below exist only to make this reference file
# a valid, self-consistent Terraform document when validated at the workspace
# root. Do not treat them as real values.
# ---------------------------------------------------------------------------

variable "resource_group_name" {
  type        = string
  description = "Aggregated: name of the resource group (from networking module)."
}

variable "cluster_name" {
  type        = string
  description = "Aggregated: AKS cluster name (from aks module)."
}

variable "cluster_fqdn" {
  type        = string
  description = "Aggregated: AKS cluster control-plane FQDN (from aks module)."
}

variable "oidc_issuer_url" {
  type        = string
  description = "Aggregated: AKS OIDC issuer URL (from aks module)."
}

variable "acr_login_server" {
  type        = string
  description = "Aggregated: ACR login server (from registry module)."
}

variable "acr_admin_username" {
  type        = string
  description = "Aggregated: ACR admin username (from registry module)."
}

variable "postgres_fqdn" {
  type        = string
  description = "Aggregated: PostgreSQL flexible server FQDN (from databases module)."
}

variable "postgres_databases" {
  type        = list(string)
  description = "Aggregated: list of databases on the PostgreSQL server (from databases module)."
}

variable "redis_hostname" {
  type        = string
  description = "Aggregated: Redis cache hostname (from databases module)."
}

variable "eventhub_connection_string" {
  type        = string
  description = "Aggregated: Event Hubs namespace connection string (from streaming module)."
}

output "resource_group_name" {
  description = "Name of the resource group that holds the e-commerce resources."
  value       = var.resource_group_name
}

output "cluster_name" {
  description = "Name of the AKS cluster."
  value       = var.cluster_name
}

output "cluster_fqdn" {
  description = "Public FQDN of the AKS cluster control plane."
  value       = var.cluster_fqdn
}

output "oidc_issuer_url" {
  description = "OIDC issuer URL of the AKS cluster. Used later for workload identity / Argo CD SSO."
  value       = var.oidc_issuer_url
}

output "acr_login_server" {
  description = "Login server (registry endpoint) of the Azure Container Registry."
  value       = var.acr_login_server
}

output "acr_admin_username" {
  description = "Admin username for ACR. Dev-only convenience; prefer Entra/workload identity in production."
  value       = var.acr_admin_username
  sensitive   = true
}

output "postgres_fqdn" {
  description = "FQDN of the Azure Database for PostgreSQL Flexible server."
  value       = var.postgres_fqdn
  sensitive   = true
}

output "postgres_databases" {
  description = "List of databases created on the PostgreSQL server."
  value       = var.postgres_databases
}

output "redis_hostname" {
  description = "Hostname of the Azure Cache for Redis instance."
  value       = var.redis_hostname
}

output "eventhub_connection_string" {
  description = "Connection string of the Event Hubs namespace (Kafka-compatible)."
  value       = var.eventhub_connection_string
  sensitive   = true
}
