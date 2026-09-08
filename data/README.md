# data/ — Data layer (deployment options + connection config)

The data layer of the e-commerce platform (Phase 4). This directory holds the
**in-cluster alternatives** for local/dev environments PLUS the connection
configuration that services consume.

## The big decision: managed vs in-cluster

The platform's **default and primary path is Azure-managed data services**,
provisioned by `infra/terraform` (Phase 2):

| Service | Managed (Phase 2, default) | In-cluster (this dir, alternative) |
|---|---|---|
| PostgreSQL | Azure Database for PostgreSQL Flexible Server | CloudNativePG (`data/postgres/manifests/cluster.yaml`) |
| Redis | Azure Cache for Redis | Bitnami Redis (`data/redis/values.yaml`) |
| Kafka | Azure Event Hubs (Kafka endpoint) | Strimzi Kafka (`data/kafka/strimzi/kafka-cluster.yaml`) |

Rule of thumb:

- **AKS cluster with terraform outputs** → use managed services. Point services
  at the FQDNs from `cluster/overlays/*/env-config.yaml` (auto-injected via
  `ecommerce-env-config`).
- **minikube / kind / local dev cluster** → deploy the in-cluster options from
  this directory. Services keep the same config keys, only the hosts change
  (service DNS names like `postgres.data.svc.cluster.local`).

## Connection model

Services never hardcode connection strings. Two mechanisms:

1. **Per-env ConfigMap** `ecommerce-env-config` (from `cluster/overlays/`)
   — hosts, ports, bootstrap servers:

   | Key | Managed value (example) | In-cluster value |
   |---|---|---|
   | `DB_HOST` | `pg-dev-ecommerce.postgres.database.azure.com` | `postgres.data.svc.cluster.local` |
   | `DB_PORT` | `5432` | `5432` |
   | `REDIS_HOST` | `redis-dev-ecommerce.redis.cache.windows.net` | `redis-master.data.svc.cluster.local` |
   | `REDIS_PORT` | `6380` (TLS) | `6379` |
   | `KAFKA_BOOTSTRAP` | `ns-dev-ecommerce.servicebus.windows.net:9093` | `ecommerce-kafka-kafka-bootstrap.kafka.svc.cluster.local:9092` |

2. **Secrets** — passwords and credentials never live in ConfigMaps:
   - Managed path: Azure Key Vault → `ClusterSecretStore` → `ExternalSecret`
     (see `cluster/base/external-secrets/`).
   - In-cluster path: plain Kubernetes Secrets from this directory
     (`postgres-creds`, `redis-creds`, `event-hubs-credentials`).

## Directory map

```
data/
├─ README.md                    ← you are here
├─ postgres/
│   ├─ values-cloudnative-pg.yaml   # CNPG operator Helm values (thin)
│   ├─ values-patroni.yaml          # (optional) Patroni chart alternative — documented
│   └─ manifests/
│       ├─ cloudnativepg-operator.yaml  # reference: operator install flow
│       ├─ cluster.yaml             # CNPG Cluster CR (2 replicas, PVC, backup placeholder)
│       └─ postgres-creds.yaml      # placeholder Secret (namespace data)
├─ redis/
│   ├─ values.yaml              # Bitnami Redis Helm values (standalone, dev)
│   └─ README.md                # managed vs in-cluster
└─ kafka/
    ├─ strimzi/
    │   ├─ values.yaml          # Strimzi operator Helm values (thin)
    │   └─ kafka-cluster.yaml   # Kafka CR (3 brokers, dev listeners)
    └─ README.md                # managed vs in-cluster
```

## In-cluster install order (local/dev only)

```bash
# Postgres — operator first, Cluster second (namespace data exists in base)
helm install cnpg cloudnative-pg/cloudnative-pg -n data -f data/postgres/values-cloudnative-pg.yaml
kubectl apply -f data/postgres/manifests/postgres-creds.yaml
kubectl apply -f data/postgres/manifests/cluster.yaml

# Redis — chart deploys with an existingSecret reference
#   create redis-creds first (see data/redis/README.md)
kubectl create secret generic redis-creds -n data --from-literal=redis-password=change-me
helm install redis bitnami/redis -n data -f data/redis/values.yaml

# Kafka — operator first, Kafka CR second (namespace kafka must exist)
kubectl create ns kafka
helm install strimzi strimzi/strimzi-kafka-operator -n kafka -f data/kafka/strimzi/values.yaml
kubectl apply -f data/kafka/strimzi/kafka-cluster.yaml
```

**Never** deploy both paths for the same service on one cluster: the in-cluster
alternatives exist so local dev can run without Azure resources. The managed
path remains the production answer, and terraform outputs feed the per-env
ConfigMaps that services actually read.

## Placeholder audit

| File | Placeholder | Replace with |
|---|---|---|
| `postgres/manifests/postgres-creds.yaml` | `change-me` | a real password (or let external-secrets own it) |
| `postgres/manifests/cluster.yaml` | commented `objectStore` backup block | your Azure Blob Storage settings when enabling backups |
| `redis/values.yaml` | `redis-creds` secret | create it (command above) before `helm install` |
| `kafka/strimzi/kafka-cluster.yaml` | listener ports / storage class | your dev cluster's storage class if not `standard` |