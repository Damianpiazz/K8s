# data/redis — Redis deployment options

## Managed (default): Azure Cache for Redis

Provisioned by `infra/terraform` (Phase 2). Services connect via the per-env
ConfigMap:

| Key | Managed value (example) |
|---|---|
| `REDIS_HOST` | `redis-dev-ecommerce.redis.cache.windows.net` |
| `REDIS_PORT` | `6380` (TLS) |

Additional Azure Cache specifics the services must handle:

- **TLS only** — Azure Cache rejects plaintext (port 6379) connections.
  Set `REDIS_TLS=true` in the service environment, or point your client at
  `rediss://` scheme.
- **Auth** — the access key comes from Key Vault via
  `ClusterSecretStore`/`ExternalSecret` (see `cluster/base/external-secrets/`),
  never from a ConfigMap.
- **Clustering** — if a premium SKU with clustering is used, clients must
  support `MOVED`/`ASK` redirects or use a cluster-aware client. The default
  terraform SKU (standard) keeps this a non-issue.

## In-cluster (dev/local alternative): Bitnami Redis

`values.yaml` in this directory — standalone node, auth enabled, 1Gi PVC.

```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
kubectl create secret generic redis-creds -n data \
  --from-literal=redis-password=change-me
helm install redis bitnami/redis -n data -f data/redis/values.yaml
```

Service DNS: `redis-master.data.svc.cluster.local:6379` (no TLS on the cluster
network — do not expose this Service outside the cluster).

Switch in-cluster vs managed by editing the per-env ConfigMap only:

```yaml
DB_HOST:      # pg-… (managed)  OR  postgres.data.svc.cluster.local (in-cluster)
REDIS_HOST:   # redis-… (managed)  OR  redis-master.data.svc.cluster.local
KAFKA_BOOTSTRAP: # ns-…:9093 (managed)  OR  ecommerce-kafka-kafka-bootstrap.kafka.svc:9092
```

## What not to do

- Do not deploy both Redis alternatives on the same cluster.
- Do not store the Azure access key in the ConfigMap — it is a Secret, Key
  Vault owned.
- Do not set `auth.enabled: false` in dev "for convenience" — it teaches
  services to skip auth, and the managed path requires it anyway.