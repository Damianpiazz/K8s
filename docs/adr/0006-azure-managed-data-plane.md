# ADR-0006: Azure managed data plane (prod); in-cluster alternatives for dev

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: platform team

## Context

The platform needs PostgreSQL (orders/payments/catalog), Redis (cart), and a
Kafka-compatible message bus (notifications, order events). Two viable paths
exist: **managed Azure services** (provisioned by `infra/terraform`) or
**in-cluster operators** (CloudNativePG, Bitnami Redis chart, Strimzi Kafka).
The TP runs on a zero budget, so local dev must work without Azure; the demo
path must still look like a real deployment.

## Decision

- **Production/default path — Azure managed** (`data/README.md`):
  - PostgreSQL → **Azure Database for PostgreSQL Flexible Server**
    (Best-effort B1s in dev tfvars, `postgres_databases = [orders, payments, catalog]`);
  - Redis → **Azure Cache for Redis** (Basic SKU, TLS port 6380);
  - Kafka → **Azure Event Hubs** (Standard SKU, Kafka endpoint
    `ns-<env>-ecommerce.servicebus.windows.net:9093`, SASL_SSL).
- **Dev/local alternative — in-cluster** (this is `data/`): CloudNativePG
  `cluster.yaml`, Bitnami Redis `values.yaml`, Strimzi Kafka CR — deployed
  only on minikube/kind, with the same config keys pointing at in-cluster DNS
  names (`postgres.data.svc.cluster.local`, `redis-master.data…`, …).
- Services never hardcode connection strings: per-env
  `ecommerce-env-config` ConfigMap keys (`DB_HOST`, `REDIS_HOST`,
  `KAFKA_BOOTSTRAP`, …) are fed by terraform outputs on Azure or the
  in-cluster DNS on local.
- The two paths are **mutually exclusive per cluster** (never both),
  enforced by doc + overlay contents.

## Consequences

- Managed path buys back-ups, TLS and HA for free; dev skips Azure entirely.
- Event Hubs speaks Kafka protocol, so the Spring Kafka clients and KEDA's
  kafka scaler (`cluster/base/keda/scaledobject-example.yaml`) work unchanged.
- Secret material (DB passwords, Event Hubs SASL) must come from Key Vault
  via External Secrets (ADR-0007), never from the ConfigMap.
- Terraform owns the managed endpoints; the per-env ConfigMaps must be
  refreshed after `terraform apply` (runbook `deploy-end-to-end.md`).

## Alternatives considered

- **Everything in-cluster even for prod**: cheapest to demo, but the
  professor asks for managed services in the cloud path; also operators
  (CNPG/Strimzi) are a whole extra operational surface.
- **Cosmos DB instead of Postgres**: bigger marketing story, but the
  services are written against Postgres DDL/H2-compatible unit tests —
  switching would break the in-memory test contract of `ci.yml`.
- **Kafka as a service via Confluent**: same Event Hubs job with vendor lock;
  Event Hubs is already in the Azure subscription path.