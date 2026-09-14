# Referencia de puertos

Floci-AZ usa un **único puerto HTTP** para todas las APIs de administración, más un puerto HTTPS para el SDK de Java de Cosmos.
Los servicios sidecar (Event Hubs, motores de Cosmos) vinculan sus propios puertos directamente al host — no se
proxean a través de floci-az.

## Puertos de floci-az

| Puerto | Protocolo | Propósito |
|---|---|---|
| `4577` | HTTP | Todos los servicios REST (Blob, Queue, Table, Functions, App Config, Cosmos, Key Vault) |
| `4578` | HTTPS | Cosmos DB — solo SDK de Java (aplica TLS en modo gateway) |

floci-az expone ambos puertos por sí mismo. Publica solo estos dos en tu `docker-compose.yml`.

## Enrutamiento por ruta (puerto 4577)

Todos los servicios comparten el puerto 4577 y se enrutan por el prefijo de la ruta URL:

| Servicio | Prefijo de ruta |
|---|---|
| Blob Storage | `/{account}/` |
| Queue Storage | `/{account}-queue/` |
| Table Storage | `/{account}-table/` |
| Azure Functions | `/{account}-functions/` |
| App Configuration | `/{account}-appconfig/` |
| Cosmos DB | `/{account}-cosmos/` |
| Key Vault | `/{account}-keyvault/` |
| Event Hubs | `/{account}-eventhub/` |
| **Azure SQL Database** | `/{account}-sql/` o `/subscriptions/.../providers/Microsoft.Sql/...` |
| **Azure Kubernetes Service** | `/subscriptions/.../providers/Microsoft.ContainerService/...` |

---

## Puertos de contenedores sidecar (dinámicos)

Los servicios que crean contenedores Docker vinculan un **puerto aleatorio asignado por el sistema operativo** directamente al host.
Estos puertos se resuelven en tiempo de ejecución mediante el daemon de Docker — nunca los configuras manualmente.

| Servicio | Contenedor sidecar | Protocolo | Cómo descubrir el puerto |
|---|---|---|---|
| Azure SQL Database (`managed`) | `mssql/server` | TDS (SQL Server) | `GET /{account}-sql/servers/{name}/connect` → campo `port` |
| Azure Kubernetes Service | `rancher/k3s` | HTTPS (Kubernetes API) | `POST .../listClusterAdminCredential` → `kubeconfigs[0].value` → `server:` en el kubeconfig |
| Cosmos MongoDB | `mongo` | MongoDB wire | `GET /{account}-cosmosmongo/connect` → campo `port` |
| Cosmos PostgreSQL | `postgres` | PostgreSQL | `GET /{account}-cosmospostgresql/connect` → campo `port` |
| Cosmos Cassandra | `cassandra` | CQL | `GET /{account}-cosmoscassandra/connect` → campo `port` |
| Cosmos Gremlin | _(integrado)_ | WebSocket | `GET /{account}-cosmosgremlin/connect` |
| Event Hubs (AMQP) | `activemq-artemis` | AMQP 1.0 | Fijado en `5672` (vinculado al host por Artemis) |
| Event Hubs (Kafka) | `redpanda` | Kafka | Fijado en `9093` (vinculado al host por Redpanda) |

> **NO agregues los puertos de los sidecars a la sección `ports:` del servicio `floci-az`** en Docker Compose.
> El daemon de Docker crea esos contenedores y vincula sus puertos directamente al host.
> Duplicarlos en el servicio `floci-az` causaría conflictos de puertos.

---

## Cambiar el puerto de administración

```yaml
environment:
  FLOCI_AZ_PORT: "14577"
```

Cuando cambies el puerto, actualiza también `FLOCI_AZ_BASE_URL` para que las URLs incrustadas en las respuestas
de las APIs (tokens SAS, ubicaciones de operaciones, URLs JDBC) sean correctas:

```yaml
environment:
  FLOCI_AZ_PORT: "14577"
  FLOCI_AZ_BASE_URL: "http://localhost:14577"
```

---

## Ejemplo de Docker Compose

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"   # HTTP management plane (all services)
      - "4578:4578"   # HTTPS (Cosmos Java SDK only)
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock  # required for SQL, Functions, Cosmos engines

    # Sidecar ports (SQL Server, MongoDB, Postgres, etc.) bind directly to the host
    # via the Docker daemon — do NOT list them here.
```

Actualiza siempre `FLOCI_AZ_BASE_URL` junto con `FLOCI_AZ_PORT` para que las URLs incrustadas en las respuestas
de las APIs (tokens SAS, ubicaciones de operaciones) apunten a la dirección correcta.