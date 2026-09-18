# Fix 16: Wiring del data plane a Postgres del emulador (las apps usaban H2)

Las services del stack local arrancaban con H2 en memoria: ninguna hablaba con
el Postgres que el emulador expone en el host (puerto dinámico `37865` →
`5432`). Se agregó la configuración de datasource explícita en el overlay
local.

## Error observado

- Ninguna service usaba Postgres: no había `SPRING_DATASOURCE_URL` ni
  `jdbc:postgresql` en `cluster/`.
- El config-server solo servía el perfil `demo` (H2).
- Los productos del catálogo salían de H2 in-memory, con
  `ENABLE_MOCK_DATA=true`.
- El URL de H2 venía de los configmaps base y el driver H2 de
  `SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.h2.Driver` del `envFrom` (la
  auto-detección por URL perdía contra ese env).

Errores típicos en los logs:

```text
PSQLException: Connection ... refused
Driver org.h2.Driver claims to not accept jdbcUrl, jdbc:postgresql://...
```

## Por qué ocurre

El stack se validó con apps + Eureka + gateway + frontend, pero el wiring a
los sidecars de datos **nunca se completó**. El sidecar de Postgres expone un
puerto **dinámico** del host (`37865` → `5432`), por lo que la configuración
de datasource no estaba en ningún lado: las apps arrancaban con lo que traían
los configmaps base (H2).

## Fix aplicado

En `cluster/overlays/local/kustomization.yaml` se agregaron patches JSON6902
por deployment (`catalog`, `order`, `payment`, `inventory`) con env
**explícito** (el env del contenedor tiene precedencia sobre `envFrom` para la
misma clave en Spring):

| Variable | Valor |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://host.docker.internal:37865/ecommerce` |
| `SPRING_DATASOURCE_DRIVER_CLASS_NAME` | `org.postgresql.Driver` |
| `SPRING_DATASOURCE_USERNAME` | `psqladmin` |
| `SPRING_DATASOURCE_PASSWORD` | fuerte, sin `@` |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `update` |

Además:

- `cluster/overlays/local/env-config.yaml`: `DB_PORT=37865`.
- Se creó la base `ecommerce` dentro del sidecar (`CREATE DATABASE`).

Nota: el driver de Postgres no necesita declararse manualmente si ya está en
el classpath; aquí se forzó porque el `envFrom` ponía `org.h2.Driver`, que
ganaba la auto-detección por URL. `cart-svc` no usa Redis todavía (pom
comentado), así que no se wireó.

## Cómo verificar

```bash
kubectl logs deploy/catalog-svc
docker exec floci-az-pg-psql-local-ecommerce-floci01 psql -U psqladmin -d ecommerce -c "select count(*) from products"
```

- Los logs de `catalog-svc` muestran `HikariPool-1 - Start completed` y
  `Seeded 4 demo products`.
- El `select count(*)` sobre `products` devuelve `4`.