# catalog-svc — API REST de catálogo de productos

CRUD de productos respaldado por Spring Data JPA. El perfil de demo usa H2
in-memory y seedea 4 productos al arrancar; el deployment puede cambiar a
Postgres gestionado por Azure sin cambios de código.

## Endpoints

| Método | Path | Comportamiento |
|---|---|---|
| GET | `/api/catalog/products` | lista todos (filtro opcional `?category=`) |
| GET | `/api/catalog/products/{id}` | producto individual (404 si desconocido) |
| POST | `/api/catalog/products` | crea (devuelve 201 + entidad con id) |
| PUT | `/api/catalog/products/{id}` | update completo (404 si desconocido) |
| DELETE | `/api/catalog/products/{id}` | elimina (204; 404 si desconocido) |

## Correrlo localmente

```bash
cd services/catalog-svc
mvn spring-boot:run          # arranca en :8081, seedea datos demo
```

```bash
curl localhost:8081/api/catalog/products
curl -X POST localhost:8081/api/catalog/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Webcam HD-720","description":"1080p","price":59.99,"stock":25,"category":"Accessories"}'
```

## Cambiar a Postgres gestionado por Azure

`k8s/base/configmap.yaml` (`catalog-svc-config`) guarda los defaults del
datasource. El ConfigMap `ecommerce-env-config` (de `cluster/overlays/*`) ya
lleva `DB_HOST` / `DB_PORT` / `DB_NAME`. Sobreescribí las claves
`SPRING_DATASOURCE_*` según las instrucciones comentadas en ese archivo, y poné
el password en Azure Key Vault (patrón external-secrets — ver
`cluster/base/external-secrets/`).

## Kubernetes

| Manifiesto | Propósito |
|---|---|
| `k8s/base/deployment.yaml` | 1 réplica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `catalog-svc:8081` |
| `k8s/base/configmap.yaml` | defaults de datasource + URLs de config/eureka |
| `k8s/base/hpa.yaml` | CPU 70%, réplicas 1→5 |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress desde api-gateway; egress DNS + peers + 5432/443 |
| `k8s/overlays/prod/` | 3 réplicas, resources más grandes |

## Placeholders a reemplazar

1. `acr.azurecr.io` → tu ACR login server.
2. Datasource → Postgres de Azure (ver comentarios del configmap) + credenciales de DB en Key Vault.