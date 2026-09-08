# ---------------------------------------------------------------------------
# Streaming module
#
# Creates an Azure Event Hubs namespace with Kafka compatibility enabled. Event
# Hubs speaks the Kafka wire protocol, so we get Kafka semantics (topics,
# consumer groups) without running a Kafka cluster ourselves. One event hub
# ("topic") is created per event stream.
# ---------------------------------------------------------------------------

variable "environment" {
  type        = string
  description = "Environment name used in resource naming."
}

variable "location" {
  type        = string
  description = "Azure region for the namespace."
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

variable "namespace_sku" {
  type        = string
  description = "Event Hubs namespace SKU. 'Basic' has no Kafka-compatible endpoint; use 'Standard' or 'Premium'."
  default     = "Standard"
}

variable "namespace_capacity" {
  type        = number
  description = "Throughput units (Standard) or processing units (Premium) for the namespace."
  default     = 1
}

variable "eventhubs" {
  type = map(object({
    partition_count   = number
    message_retention = number
  }))
  description = "Map of event hub (Kafka topic) name to its partition count and message retention in days."
  default = {
    "order-events" = {
      partition_count   = 3
      message_retention = 1
    }
    "payment-events" = {
      partition_count   = 3
      message_retention = 1
    }
  }
}

locals {
  namespace_name = "eh-${var.environment}-ecommerce-${var.suffix}"
}

resource "azurerm_eventhub_namespace" "this" {
  name                = local.namespace_name
  location            = var.location
  resource_group_name = var.resource_group_name
  sku                 = var.namespace_sku
  capacity            = var.namespace_capacity
  # Enables the Kafka-compatible endpoint on the namespace.
  kafka_enabled = true
  tags          = var.tags
}

resource "azurerm_eventhub" "topics" {
  for_each = var.eventhubs

  namespace_id      = azurerm_eventhub_namespace.this.id
  name              = each.key
  partition_count   = each.value.partition_count
  message_retention = each.value.message_retention
}

output "namespace_name" {
  description = "Name of the Event Hubs namespace."
  value       = azurerm_eventhub_namespace.this.name
}

output "namespace_id" {
  description = "Resource ID of the Event Hubs namespace."
  value       = azurerm_eventhub_namespace.this.id
}

output "connection_string" {
  description = "Primary connection string of the Event Hubs namespace (sensitive). Kafka clients use the Kafka bootstrap equivalent."
  value       = azurerm_eventhub_namespace.this.default_primary_connection_string
  sensitive   = true
}

output "kafka_bootstrap_endpoint" {
  description = "Kafka-compatible bootstrap endpoint for clients."
  value       = azurerm_eventhub_namespace.this.kafka_bootstrap_endpoint
  sensitive   = true
}

output "eventhub_names" {
  description = "List of event hub (Kafka topic) names."
  value       = keys(azurerm_eventhub.topics)
}

output "resource_group" {
  description = "Resource group hosting the streaming namespace."
  value       = var.resource_group_name
}
