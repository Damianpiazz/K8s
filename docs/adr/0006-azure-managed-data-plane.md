# ADR-0006: Capa de datos gestionada por Azure (prod); alternativas in-cluster para dev

- **Estado**: aceptado
- **Fecha**: 2026-09-07
- **Decisores**: equipo de plataforma

## Contexto

La plataforma necesita PostgreSQL (orders/payments/catalog), Redis (cart) y un
message bus compatible con Kafka (notifications, order events). Existen dos
caminos viables: **servicios gestionados de Azure** (aprovisionados por
`infra/terraform`) u **operadores in-cluster** (CloudNativePG, chart de
Bitnami Redis, Kafka Strimzi). El TP corre con presupuesto cero, así que el
desarrollo local debe funcionar sin Azure; el camino de demo debe verse como
un despliegue real.

## Decisión

- **Camino de producción/default — gestionado por Azure** (`data/README.md`):
  - PostgreSQL → **Azure Database for PostgreSQL Flexible Server**
    (B1s best-effort en tfvars de dev, `postgres_databases = [orders, payments, catalog]`);
  - Redis → **Azure Cache for Redis** (SKU Basic, puerto TLS 6380);
  - Kafka → **Azure Event Hubs** (SKU Standard, endpoint Kafka
    `ns-<env>-ecommerce.servicebus.windows.net:9093`, SASL_SSL).
- **Alternativa dev/local — in-cluster** (esto es `data/`): `cluster.yaml` de
  CloudNativePG, `values.yaml` de Bitnami Redis, CR de Kafka Strimzi —
  desplegado solo en minikube/kind, con las mismas claves de configuración
  apuntando a nombres DNS in-cluster (`postgres.data.svc.cluster.local`,
  `redis-master.data…`, …).
- Los servicios nunca hardcodean connection strings: las claves del ConfigMap
  `ecommerce-env-config` por ambiente (`DB_HOST`, `REDIS_HOST`,
  `KAFKA_BOOTSTRAP`, …) se alimentan de las salidas de terraform en Azure o
  del DNS in-cluster en local.
- Los dos caminos son **mutuamente excluyentes por clúster** (nunca ambos),
  forzado por la documentación y el contenido de los overlays.

## Consecuencias

- El camino gestionado trae backups, TLS y HA gratis; dev no toca Azure.
- Event Hubs habla el protocolo Kafka, así que los clientes Spring Kafka y el
  scaler de Kafka de KEDA (`cluster/base/keda/scaledobject-example.yaml`)
  funcionan sin cambios.
- El material secreto (passwords de DB, SASL de Event Hubs) debe venir de Key
  Vault via External Secrets (ADR-0007), nunca del ConfigMap.
- Terraform es dueño de los endpoints gestionados; los ConfigMaps por ambiente
  deben refrescarse después de `terraform apply` (runbook
  `deploy-end-to-end.md`).

## Alternativas consideradas

- **Todo in-cluster incluso en prod**: lo más barato para la demo, pero el
  profesor pide servicios gestionados en el camino cloud; además los
  operadores (CNPG/Strimzi) son una superficie operacional extra completa.
- **Cosmos DB en vez de Postgres**: mejor historia de marketing, pero los
  servicios están escritos contra DDL de Postgres / tests unitarios
  compatibles con H2 — cambiarlo rompería el contrato de test in-memory de
  `ci.yml`.
- **Kafka como servicio via Confluent**: el mismo trabajo que Event Hubs con
  vendor lock; Event Hubs ya está en la ruta de suscripción de Azure.