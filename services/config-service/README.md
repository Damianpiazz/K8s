# config-service (Spring Cloud Config Server)

Configuración centralizada para cada servicio. Sirve YAML desde un backend
**filesystem nativo** (`src/main/resources/config/`) — no necesita servidor de
Git para correr. Arrancalo, y los clientes bajan `catalog-svc.yml`,
`cart-svc.yml` etc. desde `http://config-service:8888`.

## Correrlo localmente

```bash
cd services/config-service
mvn spring-boot:run
```

- Endpoint de config: `http://localhost:8888/<service-name>/demo`
- Health: `http://localhost:8888/actuator/health`
- Métricas de Prometheus: `http://localhost:8888/actuator/prometheus`
- Env (debug de qué propiedad gana): `http://localhost:8888/actuator/env`

## Cambiar a un backend Git (patrón de producción)

En `src/main/resources/application.yml`, reemplazá el bloque `native` con:

```yaml
spring:
  cloud:
    config:
      server:
        git:
          uri: https://github.com/<you>/<config-repo>.git
          default-label: main
          clone-on-start: true
```

Los clientes no se afectan — los clientes de Spring Cloud Config no les
importa el backend.

## Kubernetes

| Manifiesto | Propósito |
|---|---|
| `k8s/base/deployment.yaml` | 1 réplica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `config-service:8888` |
| `k8s/base/hpa.yaml` | CPU 70%, réplicas 1→5 |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress desde todos los pods de `ecommerce-platform`; egress DNS + 443 |
| `k8s/overlays/prod/` | 2 réplicas, resources más grandes, imagen ACR |

## Placeholders a reemplazar

1. `acr.azurecr.io` → tu ACR login server (en `k8s/base/kustomization.yaml`,
   `k8s/overlays/prod/kustomization.yaml` y `k8s/base/deployment.yaml`) —
   el workflow de CD tagea imágenes matcheando `newName` en el overlay.
2. Nada más — el servicio en sí no tiene dependencias externas.

> NOTA: la política `disallow-latest-tag` de Kyverno (Enforce) significa que la
> imagen placeholder `:latest` se rechaza hasta que el `newTag` del overlay
> lleve un SHA de commit. Corré el pipeline de CD una vez, o seteá `newTag`
> manualmente antes de aplicar.