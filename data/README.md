# data/ — Capa de datos (opciones de despliegue + config de conexión)

La capa de datos de la plataforma e-commerce (Fase 4). Este directorio tiene
las **alternativas in-cluster** para ambientes locales/dev MÁS la
configuración de conexión que los servicios consumen.

## La gran decisión: gestionado vs in-cluster

El **camino default y primario de la plataforma son los servicios de datos
gestionados por Azure**, aprovisionados por `infra/terraform` (Fase 2):

| Servicio | Gestionado (Fase 2, default) | In-cluster (este dir, alternativa) |
|---|---|---|
| PostgreSQL | Azure Database for PostgreSQL Flexible Server | CloudNativePG (`data/postgres/manifests/cluster.yaml`) |
| Redis | Azure Cache for Redis | Bitnami Redis (`data/redis/values.yaml`) |
| Kafka | Azure Event Hubs (endpoint Kafka) | Strimzi Kafka (`data/kafka/strimzi/kafka-cluster.yaml`) |

Regla práctica:

- **Clúster AKS con salidas de terraform** → usá servicios gestionados. Apuntá
  los servicios a los FQDNs de `cluster/overlays/*/env-config.yaml`
  (auto-inyectado via `ecommerce-env-config`).
- **minikube / kind / clúster de dev local** → desplegá las opciones in-cluster
  de este directorio. Los servicios mantienen las mismas claves de config, solo
  cambian los hosts (nombres DNS de servicios como
  `postgres.data.svc.cluster.local`).

## Modelo de conexión

Los servicios nunca hardcodean connection strings. Dos mecanismos:

1. **ConfigMap por ambiente** `ecommerce-env-config` (de `cluster/overlays/`)
   — hosts, puertos, bootstrap servers:

   | Clave | Valor gestionado (ejemplo) | Valor in-cluster |
   |---|---|---|
   | `DB_HOST` | `pg-dev-ecommerce.postgres.database.azure.com` | `postgres.data.svc.cluster.local` |
   | `DB_PORT` | `5432` | `5432` |
   | `REDIS_HOST` | `redis-dev-ecommerce.redis.cache.windows.net` | `redis-master.data.svc.cluster.local` |
   | `REDIS_PORT` | `6380` (TLS) | `6379` |
   | `KAFKA_BOOTSTRAP` | `ns-dev-ecommerce.servicebus.windows.net:9093` | `ecommerce-kafka-kafka-bootstrap.kafka.svc.cluster.local:9092` |

2. **Secrets** — los passwords y credenciales nunca viven en ConfigMaps:
   - Camino gestionado: Azure Key Vault → `ClusterSecretStore` →
     `ExternalSecret` (ver `cluster/base/external-secrets/`).
   - Camino in-cluster: Kubernetes Secrets plain de este directorio
     (`postgres-creds`, `redis-creds`, `event-hubs-credentials`).

## Mapa del directorio

```
data/
├─ README.md                    ← estás acá
├─ postgres/
│   ├─ values-cloudnative-pg.yaml   # valores Helm del operador CNPG (thin)
│   ├─ values-patroni.yaml          # (opcional) alternativa con chart Patroni — documentado
│   └─ manifests/
│       ├─ cloudnativepg-operator.yaml  # referencia: flujo de instalación del operador
│       ├─ cluster.yaml             # CR de Cluster CNPG (2 réplicas, PVC, backup placeholder)
│       └─ postgres-creds.yaml      # Secret placeholder (namespace data)
├─ redis/
│   ├─ values.yaml              # valores Helm de Bitnami Redis (standalone, dev)
│   └─ README.md                # gestionado vs in-cluster
└─ kafka/
    ├─ strimzi/
    │   ├─ values.yaml          # valores Helm del operador Strimzi (thin)
    │   └─ kafka-cluster.yaml   # CR de Kafka (3 brokers, listeners de dev)
    └─ README.md                # gestionado vs in-cluster
```

## Orden de instalación in-cluster (solo local/dev)

```bash
# Postgres — operador primero, Cluster después (el namespace data existe en base)
helm install cnpg cloudnative-pg/cloudnative-pg -n data -f data/postgres/values-cloudnative-pg.yaml
kubectl apply -f data/postgres/manifests/postgres-creds.yaml
kubectl apply -f data/postgres/manifests/cluster.yaml

# Redis — el chart despliega con referencia a existingSecret
#   creá redis-creds primero (ver data/redis/README.md)
kubectl create secret generic redis-creds -n data --from-literal=redis-password=change-me
helm install redis bitnami/redis -n data -f data/redis/values.yaml

# Kafka — operador primero, CR de Kafka después (el namespace kafka debe existir)
kubectl create ns kafka
helm install strimzi strimzi/strimzi-kafka-operator -n kafka -f data/kafka/strimzi/values.yaml
kubectl apply -f data/kafka/strimzi/kafka-cluster.yaml
```

**Nunca** despliegues ambos caminos para el mismo servicio en un clúster: las
alternativas in-cluster existen para que el dev local corra sin recursos de
Azure. El camino gestionado sigue siendo la respuesta de producción, y las
salidas de terraform alimentan los ConfigMaps por ambiente que los servicios
realmente leen.

## Auditoría de placeholders

| Archivo | Placeholder | Reemplazar con |
|---|---|---|
| `postgres/manifests/postgres-creds.yaml` | `change-me` | un password real (o dejá que external-secrets lo maneje) |
| `postgres/manifests/cluster.yaml` | bloque `objectStore` de backup comentado | tus settings de Azure Blob Storage al habilitar backups |
| `redis/values.yaml` | secret `redis-creds` | crealo (comando de arriba) antes de `helm install` |
| `kafka/strimzi/kafka-cluster.yaml` | puertos de listener / storage class | la storage class de tu clúster de dev si no es `standard` |