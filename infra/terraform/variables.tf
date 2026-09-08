# ---------------------------------------------------------------------------
# Shared variables consumed by the per-environment root modules.
#
# Each environment (envs/<name>) re-declares and passes its own values through
# `main.tf` -> individual modules. The values below define the common contract
# expected by every environment. Most have a default so `terraform plan` works
# out of the box in a fresh environment; override per environment via tfvars.
# ---------------------------------------------------------------------------

variable "environment" {
  type        = string
  description = "Deployment environment name (dev, staging, prod). Also used as a prefix for resource names."
}

variable "location" {
  type        = string
  description = "Azure region where resources are deployed. Example: westeurope, eastus."
  default     = "westeurope"
}

variable "suffix" {
  type        = string
  description = "Short unique suffix appended to resource names, e.g. tppractico01. Keeps names unique across environments/subscriptions."
  default     = "tppractico01"
}

variable "env_num" {
  type        = number
  description = "Numeric identifier used to derive VNet CIDR blocks per environment (10.<env_num>.0.0/16). Must differ per environment to avoid overlapping networks."
}

variable "tags" {
  type        = map(string)
  description = "Common tags applied to every resource. Merge env-specific tags here."
  default     = {}
}
