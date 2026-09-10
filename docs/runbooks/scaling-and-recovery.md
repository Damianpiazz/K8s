# Runbook: Escalado y recuperación

Cómo crece la plataforma, cómo sobrevive a la muerte de un nodo, y cómo
revertir cuando algo sale mal en un deploy. Modelo de referencia: **HPA/KEDA
para elasticidad, PDB para drenajes seguros, Argo CD para rollback, datos
gestionados para durabilidad** (ADRs 0002, 0006, 0008).

## 1. Escalado horizontal — lo que viene con la plataforma

| Mecanismo | Dónde | Propósito |
|---|---|---|
| **HPA** | `services/<svc>/k8s/base/hpa.yaml` (los 17) | Escalado de réplicas por CPU por servicio |
| **KEDA** | `cluster/base/keda/scaledobject-example.yaml` | Escalado event-driven — order-worker escala por lag de Event Hubs (Kafka) |
| **PDB** | `services/<svc>/k8s/base/pdb.yaml` (los 17) | Garantiza N réplicas durante disrupción voluntaria (drenaje de nodo, upgrades) |
| **Node pool autoscaler** | `infra/terraform/envs/*/terraform.tfvars` (camino AKS) | Elasticidad de nodos a nivel de clúster |

### Operar HPA

```bash
kubectl -n ecommerce get hpa,deploy
kubectl -n ecommerce describe hpa catalog-svc        # utilization, conditions
kubectl -n ecommerce get pod -l app=catalog-svc -o wide
```

- El escalado necesita `requests` en los contenedores (los manifiestos base
  los setean) — el HPA reporta `<unknown>/<unknown>` cuando faltan requests
  (runbook de troubleshooting, §7).
- Ajustá con el parche de recursos del overlay para prod; demo de scale-up:
  `kubectl -n ecommerce run -it --rm load --image=busybox -- sh -c "while true; do wget -qO- http://catalog-svc:8081/actuator/health >/dev/null; done"`.

### KEDA (ejemplo order-worker)

```bash
kubectl -n ecommerce get scaledobject,scaledjob
kubectl -n ecommerce describe hpa order-worker     # KEDA respalda este HPA
```

- Se dispara por lag de Azure Event Hubs (`lagThreshold: 10`,
  `minReplicaCount: 1`, `maxReplicaCount: 10`); el scale-down tiene una
  ventana de estabilización de 300s por defecto (esperado).
- `cooldownPeriod`/`pollingInterval` viven en el ScaledObject — ajustalos
  después del load testing.

## 2. Drenaje de nodo / disrupción voluntaria (PDB en acción)

```bash
kubectl drain <node> --ignore-daemonsets --delete-emptydir-data
kubectl get pdb -n ecommerce     # ALLOWED DISRUPTIONS debe mantenerse >= 1
kubectl uncordon <node>
```

- Con PDB `minAvailable: 1` (o 50%) la API de eviction **bloquea** el drenaje
  cuando quedaría solo 1 réplica — vas a ver un drenaje colgado; eso es el PDB
  trabajando, no un bug.
- Primero los workers, después el control plane (minikube/kind local:
  `minikube stop` es toda la historia).

## 3. Crash / falla de nodo (disrupción involuntaria)

- Un nodo muerto = los Pods se reprograman en los sobrevivientes (DaemonSet
  metrics-server, kube-proxy se re-ejecutan automáticamente).
- Los Pods que referenciaban datos locales vuelven vacíos — todo el estado de
  la app vive en la **capa de datos gestionada** (ADR-0006), así que esto no
  es un evento para los datos.
- Mirá si: nodo NotReady > 5m → `kubectl describe node <node>` → logs de
  kubelet/containerd via SSH/consola; out-of-disk → limpiá imágenes con
  `docker system prune` (o bumps de spot en AKS).
- Eureka se re-registra por heartbeats (ADR-0004): después de un drenaje
  grande, dale 2× el intervalo de registro antes de juzgar el dashboard.

## 4. Datos gestionados — backups y recuperación

| Servicio | Mecanismo de backup | Restore |
|---|---|---|
| Postgres Flexible Server | **backups automáticos** de Azure (retención de 7 días por defecto en tfvars) | Portal/CLI: `az postgres flexible-server restore` → apuntá al PITR |
| Redis Cache | persistencia AOF (limitaciones del SKU Basic — ver abajo) | export/import de `az redis` (blob RDB) |
| Event Hubs | Nada persistente (stream); replay via offsets del consumidor | Re-correr los consumidores desde un checkpoint guardado |

- Demo de PITR:
  ```bash
  az postgres flexible-server restore \
    -n pg-dev-ecommerce-restored --source-server pg-dev-ecommerce \
    -g rg-ecommerce-dev --restore-time <ISO-8601>
  ```
- **Trade-off conocido del SKU Basic** (ADR-0006): el Azure Cache Basic no
  tiene SLA ni garantía de persistencia — aceptable para dev; los tfvars de
  prod deben usar Standard/Premium y documentar la elección de RPO en el
  README.
- Event Hubs es high-watermark: restore = rebobinar el checkpoint del consumer
  group, no resurrección de datos — diseñado así (las órdenes viven en
  Postgres).

## 5. Rollback (estilo GitOps, ADR-0002)

Git es la fuente de verdad; el rollback seguro más rápido es un **commit de
revert** (nunca `kubectl delete` — selfHeal lo re-crearía):

```bash
git log --oneline -10                        # encontrá el commit malo (p. ej. abc1234)
git revert abc1234                            # commit nuevo que deshace el cambio
git push origin <branch>                      # el CD corre de nuevo; Argo CD selfHeals
argocd app wait ecommerce-<env> --sync        # o mirá `argocd app get`
```

- Rollback específico de tag de imagen: el rewrite de newTag **es un commit**
  en el overlay — `git revert` del commit "bump catalog-svc a SHA" y el
  pipeline re-sincroniza la imagen anterior.
- Si Argo CD mismo está roto: `kubectl -n argocd rollout restart deploy/argocd-server`,
  o re-ejecutá `bootstrap-argocd.ps1` (idempotente).
- Camino local sin Argo: `git checkout <good-commit> -- cluster/overlays/dev &&
  .\scripts\deploy\apply-overlay.ps1 -Environment dev`.

### Checklist post-rollback

- [ ] `argocd app get ecommerce-<env>` → Synced + Healthy
- [ ] Las imágenes de los Pods confirman el rollback: `kubectl -n ecommerce get deploy -o
      jsonpath='{range .items[*]}{.metadata.name}{" -> "}{.spec.template.spec.containers[0].image}{"\n"}{end}'`
- [ ] Los dashboards vuelven al baseline (service-sli, tasa de error)

Ver `runbook-index.md` para la lista completa; `troubleshooting.md` cubre la
mitad a nivel de síntoma de estos flujos.