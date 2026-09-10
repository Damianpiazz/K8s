# data/redis — Opciones de despliegue de Redis

## Gestionado (default): Azure Cache for Redis

Aprovisionado por `infra/terraform` (Fase 2). Los servicios se conectan via
el ConfigMap por ambiente:

| Clave | Valor gestionado (ejemplo) |
|---|---|
| `REDIS_HOST` | `redis-dev-ecommerce.redis.cache.windows.net` |
| `REDIS_PORT` | `6380` (TLS) |

Particularidades adicionales de Azure Cache que los servicios deben manejar:

- **Solo TLS** — Azure Cache rechaza conexiones plaintext (puerto 6379).
  Seteá `REDIS_TLS=true` en el ambiente del servicio, o apuntá tu cliente al
  esquema `rediss://`.
- **Auth** — la access key viene de Key Vault via
  `ClusterSecretStore`/`ExternalSecret` (ver `cluster/base/external-secrets/`),
  nunca de un ConfigMap.
- **Clustering** — si se usa un SKU premium con clustering, los clientes deben
  soportar redirects `MOVED`/`ASK` o usar un cliente cluster-aware. El SKU por
  defecto de terraform (standard) mantiene esto como no-issue.

## In-cluster (alternativa dev/local): Bitnami Redis

`values.yaml` en este directorio — nodo standalone, auth habilitado, PVC de
1Gi.

```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
kubectl create secret generic redis-creds -n data \
  --from-literal=redis-password=change-me
helm install redis bitnami/redis -n data -f data/redis/values.yaml
```

DNS del servicio: `redis-master.data.svc.cluster.local:6379` (sin TLS en la
red del clúster — no expongas este Service fuera del clúster).

Cambiá in-cluster vs gestionado editando solo el ConfigMap por ambiente:

```yaml
DB_HOST:      # pg-… (gestionado)  OR  postgres.data.svc.cluster.local (in-cluster)
REDIS_HOST:   # redis-… (gestionado)  OR  redis-master.data.svc.cluster.local
KAFKA_BOOTSTRAP: # ns-…:9093 (gestionado)  OR  ecommerce-kafka-kafka-bootstrap.kafka.svc:9092
```

## Qué NO hacer

- No despliegues ambas alternativas de Redis en el mismo clúster.
- No guardes la access key de Azure en el ConfigMap — es un Secret, propiedad
  de Key Vault.
- No setees `auth.enabled: false` en dev "por conveniencia" — enseña a los
  servicios a saltearse la auth, y el camino gestionado la requiere igual.