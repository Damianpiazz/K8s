# Resumen de servicios

Floci-AZ proporciona emulación de varios servicios centrales de Azure.

| Servicio | Endpoint | Estado de implementación |
|---|---|---|
| **Azure Resource Manager** | `/subscriptions/...` + `/providers/...` | ✅ Suscripciones, grupos de recursos, listado de recursos/providers; fallthrough del plano de administración para los providers `Microsoft.*` |
| **Blob Storage** | `/{account}/` | ✅ CRUD completo; operaciones de filesystem/rutas DFS de ADLS Gen2 con compatibilidad Hadoop ABFS 3.3.4; clave/SAS de delegación de usuario |
| **Queue Storage** | `/{account}-queue/` | ✅ CRUD completo |
| **Table Storage** | `/{account}-table/` | ✅ CRUD completo |
| **Azure Functions** | `/{account}-functions/` | ✅ HTTP Triggers, runtimes Docker |
| **App Configuration** | `/{account}-appconfig/` | ✅ Key-values, labels, feature flags, snapshots, revisiones, locks, paginación, `$select`, filtrado por tags, `Accept-Datetime`, `Sync-Token` |
| **Cosmos DB (SQL API)** | `/{account}-cosmos/` | ✅ Databases, containers, CRUD de documentos, consultas SQL, partition keys |
| **Cosmos DB multi-API** | _(motores sidecar)_ | ✅ MongoDB, PostgreSQL, Cassandra, Gremlin, Table, NoSQL (motores Docker opt-in) |
| **Key Vault** | `/{account}-keyvault/` | ✅ Secrets y keys CRUD, versioning, eliminación temporal, actualización de properties, backup/restore, rotación, criptografía RSA/EC/oct, `/rng`, Managed HSM |
| **Event Hubs** | AMQP `:5672` / Kafka `:9093` | ✅ AMQP 1.0 (Artemis), compatible con Kafka (Redpanda, opt-in) |
| **Service Bus** | `/{account}-servicebus/` + AMQP `:5673` | ✅ Queues, topics, suscripciones (dinámicas); AMQP 1.0 mediante sidecar Artemis o mocked |
| **Azure SQL Database** | Ruta ARM + `/{account}-sql/` | ✅ Servers, databases, firewall rules; solo ARM por defecto, SQL Server administrado opt-in |
| **Azure Database for PostgreSQL** | Ruta ARM (`Microsoft.DBforPostgreSQL`) + `/{account}-postgres/` | ✅ Flexible servers, databases, firewall rules, configuraciones; contenedores `postgres` respaldados por Docker o mocked |
| **Azure Database for MySQL** | Ruta ARM (`Microsoft.DBforMySQL`) + `/{account}-mysql/` | ✅ Flexible servers, databases, firewall rules, configuraciones; contenedores `mysql` respaldados por Docker o mocked |
| **Azure Database for MariaDB** | Ruta ARM (`Microsoft.DBforMariaDB`) + `/{account}-mariadb/` | ✅ Servers (modelo single-server), databases, firewall rules, configuraciones; contenedores `mariadb` respaldados por Docker o mocked |
| **Azure Kubernetes Service** | Ruta ARM (`Microsoft.ContainerService`) | ✅ Clusters, agent pools, credenciales; contenedores k3s reales o mocked |
| **Azure Container Apps** | Ruta ARM (`Microsoft.App`) + entrada FQDN | ✅ Managed environments, apps, revisiones, ingress, secrets, réplicas min/max; respaldado por Docker o mocked |
| **API Management** | Ruta ARM (`Microsoft.ApiManagement`) + `/{account}-apim/` | ✅ APIs, operations, products, suscripciones, named values, backends, importación OpenAPI; gateway routing + subconjunto de policies |
| **Virtual Network** | Ruta ARM (`Microsoft.Network`) | ✅ VNets, subnets, NICs, public IPs, NSGs, private DNS zones (+ virtual network links, record sets), private endpoints (+ private DNS zone groups), private link services; estado ARM en proceso para Terraform/OpenTofu y dependencias de VM |
| **Virtual Machines** | Ruta ARM (`Microsoft.Compute`) | ✅ Ciclo de vida de VM (create/start/stop/deallocate/restart/delete/list), instanceView; mocked (respaldo por Docker planificado) |
| **Azure Cache for Redis** | Ruta ARM (`Microsoft.Cache`) | ✅ CRUD de cache, listKeys/regenerateKey; contenedores Redis reales (plano de datos) o mocked |
| **Azure Container Registry** | Ruta ARM (`Microsoft.ContainerRegistry`) | ✅ CRUD de registry, admin credentials, checkNameAvailability; un `registry:2` compartido (Docker Registry V2 push/pull) o mocked |
| **Azure Container Instances** | Ruta ARM (`Microsoft.ContainerInstance`) | ✅ Ciclo de vida de container groups (create/update/delete/list), start/stop/restart, logs del contenedor, instanceView; mocked (respaldo por Docker planificado) |
| **Microsoft Entra ID** | `/{tenant}/oauth2/...` + `/.well-known/openid-configuration` | ✅ Proveedor de OpenID Connect — tokens firmados con RS256, JWKS, discovery; grants client-credentials, ROPC y authorization-code+PKCE (la administración del registro de aplicaciones aún está planificada) |
| **Microsoft Graph** | `/v1.0/...` | ✅ Porción limitada: descubrimiento de service principals, administración de pertenencia a grupos (`getMemberGroups`, `members/$ref`); CRUD completo de Graph fuera de alcance |
| **Event Grid** | Ruta ARM (`Microsoft.EventGrid`) + `/{topic}-eventgrid/api/events` | ✅ Custom Topics, access keys, suscripciones de eventos webhook con filtros, publish (Event Grid + CloudEvents 1.0), entrega asíncrona con reintentos, handshake de validación de suscripciones |
| **Azure Monitor / Log Analytics** | Ruta ARM (`Microsoft.OperationalInsights` / `Microsoft.Insights`) + `/dataCollectionRules/...` + `/v1/workspaces/...` | ✅ Workspaces, Data Collection Endpoints/Rules; Logs Ingestion API; consultas de Log Analytics con un subconjunto de KQL (`where`/`project`/`take`/`limit`, timespan) |
| **Communication Services Email** | `/emails:send` + `/emailMessages` + ruta ARM (`Microsoft.Communication`) | ✅ Envío de Email de ACS + sondeo de estado; buzón de inspección en memoria (estilo Mailpit); ARM communication/email services + domains. Sin entrega real |
| **Managed Identity** | Ruta ARM (`Microsoft.ManagedIdentity`) + `/metadata/identity/oauth2/token` | ✅ User-assigned identities + federated identity credentials, `identities/default` de system-assigned, endpoint de token IMDS (`ManagedIdentityCredential` mediante `AZURE_POD_IDENTITY_AUTHORITY_HOST`); tokens firmados con la clave de Entra |

## Endpoint unificado

Todos los servicios son accesibles a través de un único puerto (`4577`). El enrutamiento se
gestiona inspeccionando la ruta y los headers de la petición.

## Servicios respaldados por Docker

Los siguientes servicios levantan contenedores Docker bajo demanda y requieren el socket de Docker:

| Servicio | Imagen de Docker | Plano de datos |
|---|---|---|
| **Azure Functions** | Imagen proporcionada por el usuario | HTTP hacia el contenedor |
| **Azure SQL Database** | `mcr.microsoft.com/mssql/server:2025-latest` | Modo administrado opcional; TDS directo al puerto del contenedor |
| **Motores de Cosmos DB** | Varias (mongo, postgres, cassandra, …) | Protocolo directo al puerto del contenedor |
| **Azure Kubernetes Service** | `rancher/k3s:latest` | kubectl directo al puerto del API server de k3s |
| **Azure Database for PostgreSQL** | `postgres:17-alpine` | Protocolo wire de PostgreSQL directo al puerto del contenedor |
| **Azure Database for MySQL** | `mysql:8.0` | Protocolo wire de MySQL directo al puerto del contenedor |
| **Azure Database for MariaDB** | `mariadb:10.11` | Protocolo wire de MySQL directo al puerto del contenedor |
| **Azure Container Apps** | Imágenes proporcionadas por el usuario | Ingress HTTP enviado por proxy a través del puerto 4577 |

> Estos servicios **deben** tener acceso al demonio de Docker (mount `/var/run/docker.sock` en
> Docker Compose).