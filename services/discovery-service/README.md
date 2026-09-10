# discovery-service (Eureka Server)

Registro de servicios de toda la plataforma. Cada servicio de Spring Cloud se
registra acá y el api-gateway resuelve los targets de ruta `lb://` a través de
él.

## Correrlo localmente

```bash
cd services/discovery-service
mvn spring-boot:run
```

- Dashboard de Eureka: `http://localhost:8761/`
- Health: `http://localhost:8761/actuator/health`
- Métricas de Prometheus: `http://localhost:8761/actuator/prometheus`

## Nota de HA (prod)

Los deployments reales corren **3 réplicas de Eureka** para que el registro
sobreviva la pérdida de nodos y replique el estado entre peers. Para el
proyecto universitario, 1 réplica (dev) / 2 réplicas (overlay de prod) es
aceptable. Para ir a HA completo:

1. `register-with-eureka: true`, `fetch-registry: true`
2. `defaultZone` apuntando a cada peer (ver comentario del overlay de prod)
3. Un `StatefulSet` o IDs de red estables por réplica (nombres DNS de peers)

## Kubernetes

| Manifiesto | Propósito |
|---|---|
| `k8s/base/deployment.yaml` | 1 réplica, probes, securityContext, resources |
| `k8s/base/service.yaml` | ClusterIP `discovery-service:8761` |
| `k8s/base/hpa.yaml` | CPU 70%, réplicas 1→5 |
| `k8s/base/pdb.yaml` | minAvailable 1 |
| `k8s/base/networkpolicy.yaml` | ingress desde todos los pods de `ecommerce-platform`; egress DNS + intra-namespace + 443 |
| `k8s/overlays/prod/` | 2 réplicas, resources más grandes |

## Placeholders a reemplazar

1. `acr.azurecr.io` → tu ACR login server (`k8s/base/kustomization.yaml`,
   `k8s/overlays/prod/kustomization.yaml`, `k8s/base/deployment.yaml`).