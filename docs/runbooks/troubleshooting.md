# Runbook: Troubleshooting de problemas comunes

Guía operativa para la plataforma e-commerce (ver `../adr/` para el por qué de
cada pieza). Cada entrada: **síntoma → causa probable → diagnóstico → fix**.
Los comandos asumen que estás en el contexto correcto (`az-login.ps1`,
`kubectl config use-context minikube`, etc.).

## Índice

| Problema | Fix rápido |
|---|---|
| CrashLoopBackOff | `kubectl -n ecommerce logs deploy/<svc> --tail=50` — wiring de env/config |
| ImagePullBackOff | placeholder de ACR `acr.azurecr.io`, imagePullSecrets faltantes, registry privado |
| Problemas de TLS | issuer de cert-manager staging vs prod, propagación de DNS, rate limits |
| Kyverno rechaza `latest` | correr CD/deploy-cd-manual para fijar un tag real |
| PSA restricted rechaza Pods | agregar/chequear `seccompProfile`, runAsNonRoot, capabilities |
| Argo CD OutOfSync | drift del repo, política automática apagada, source path malo |
| HPA no escala | metrics-server, resource requests seteados, target utilization |
| Eureka no se registra | URL/config de discovery, policy de red egress, timing |

---

## 1. CrashLoopBackOff

**Síntomas**: el Pod reinicia; `kubectl get pods -n ecommerce` muestra
CrashLoopBackOff; el contador de Restarts sube.

**Diagnóstico**:
```bash
kubectl -n ecommerce describe pod <pod>
kubectl -n ecommerce logs <pod> --tail=50 --previous   # el output del último crash
kubectl -n ecommerce get events --sort-by=.lastTimestamp | tail
```

**Causas probables & fixes**:
- **Wiring de env/config**: los Pods de servicios traen los ConfigMaps
  `ecommerce-env-config` y `<svc>-config` via `envFrom`. Una clave faltante
  (p. ej. `DB_HOST` para un servicio que necesita datasource) hace fallar a
  Spring rápido.
  Fix: `kubectl -n ecommerce get configmap ecommerce-env-config -o yaml`;
  chequeá los valores del overlay `env-config.yaml`.
- **¿Eureka/config alcanzable?** Los servicios se registran al arrancar — si
  `discovery-service` o `config-service` están caídos, los clientes
  reintentan/salen.
  Fix: desplegar primero los servicios de infraestructura (orden del overlay).
- **Probes demasiado estrictas**: el liveness con `initialDelaySeconds: 40`
  asume un boot JVM lento; en un nodo de 1 CPU subí el delay o el
  failureThreshold.
- **Errores de persistencia/init**: el Postgres gestionado rechaza al usuario
  del servicio — verificá que las credenciales vengan de external-secrets
  (ADR-0007), no de drift.

## 2. ImagePullBackOff

**Síntomas**: Pod trabado en ImagePullBackOff / ErrImagePull.

**Diagnóstico**:
```bash
kubectl -n ecommerce describe pod <pod> | Select-String -Pattern 'Image|Events'
# o, en PowerShell 5.1:
kubectl -n ecommerce describe pod <pod>
```

**Causas probables & fixes**:
- **Placeholder de ACR**: la imagen sigue siendo `acr.azurecr.io/<svc>:latest`
  y no existe tal registry. Fix: `scripts/azure/deploy-cd-manual.ps1 -Acr <ACR>` +
  actualizar el `newName` del overlay una vez (tabla de placeholders de
  `services/README.md`).
- **Pull de registry privado**: AKS necesita `imagePullSecrets` o ACR
  Attached Configuration (AKS `--attach-acr`); en local, kind/minikube
  necesitan `docker login` ≤ mirror del registry o `imagePullSecrets`.
- **El tag desaparece**: el CD reescribió `newTag` a un SHA que nunca se
  pusheó (el build manual se salteó). Verificá el ACR: `az acr repository
  show-tags -n <acr> --repository <svc>` y re-fijá el tag.

## 3. Problemas de certificados TLS (cert-manager)

**Síntoma**: el ingress muestra cert default/fake; warning del browser;
`kubectl get certificate -A` trabado.

**Diagnóstico**:
```bash
kubectl -n ecommerce describe certificate api-gateway-tls
kubectl get challenges.acme.cert-manager.io -A
kubectl -n cert-manager get events --sort-by=.lastTimestamp | tail
```

**Causas probables & fixes**:
- **Issuer del ambiente**: los overlays de dev/staging usan Let's Encrypt
  **staging** (certs falsos por diseño) — verificá cuál `letsencrypt-default`
  está activo: `kubectl get clusterissuer letsencrypt-default -o yaml`. Certs
  reales solo en prod (ADR-0001, README del overlay).
- **DNS no propagado / external-dns sin sync**: external-dns debe crear el
  registro A para `api.<domain>`; chequeá `kubectl logs -n external-dns
  deploy/external-dns --tail=50` y la zona DNS en Azure.
- **Rate limits de ACME**: demasiados pedidos contra el endpoint de prod —
  el Certificate/Order queda trabado. Fix: arreglá el DNS primero, esperá la
  ventana de backoff, o usá staging mientras testeás.
- **Email faltante en el issuer**: los placeholders de `issuer-default.yaml`
  (`admin@ecommerce.*`) deben reemplazarse.

## 4. Kyverno rechazando tags `latest`

**Síntoma**: `kubectl apply -k cluster/overlays/dev` tiene éxito pero los Pods
nunca aparecen; los eventos de Kyverno dicen `disallow-latest-tag`.

**Diagnóstico**:
```bash
kubectl get events -A | Select-String -Pattern 'kyverno|latest'
kubectl -n kyverno logs -l app.kubernetes.io/name=kyverno --tail=20
```

**Fix (por diseño, no es un bug)**: `cluster/base/kyverno` aplica Enforce de
disallow-latest-tag en el namespace `ecommerce`. Corré el pipeline real
(`cd.yml`) o `scripts/azure/deploy-cd-manual.ps1` para reescribir los tags de
imagen al SHA del commit antes de aplicar; ver `cluster/overlays/README.md` →
"Por qué kubectl apply -k directo puede ser rechazado".

## 5. PSA restricted rechaza Pods

**Síntoma**: Pods trabados en `Pending`/`CreateContainerConfigError`; eventos
dicen `Forbidden: violates PodSecurity "restricted:latest"`.

**Diagnóstico**:
```bash
kubectl -n ecommerce get pod <pod> -o yaml | Select-String -Pattern 'security'
kubectl get ns ecommerce -o yaml | Select-String -Pattern 'pod-security'
```

**Fix**: el deployment debe satisfacer restricted: `runAsNonRoot: true`,
`runAsUser` seteado (1000), `allowPrivilegeEscalation: false`, `drop` de
capabilities, `seccompProfile.type: RuntimeDefault`.
`tests/manifests/test-service-contract.py` chequea los 17 servicios. Los
workloads de charts (helm) que usan namespaces de tooling siguen con audit-only
(ADR-0009) — no fuerces restricted en `observability`, etc. todavía.

## 6. Argo CD OutOfSync / sync trabado

**Síntomas**: la UI muestra OutOfSync para siempre; el sync falla; recursos
eliminados.

**Diagnóstico**:
```bash
argocd app get ecommerce-<env>
argocd app diff ecommerce-<env>
kubectl -n argocd get applications.argoproj.io ecommerce-<env> -o yaml
```

**Causas probables & fixes**:
- **Drift**: alguien aplicó manifiestos con kubectl directo — `selfHeal: true`
  los revierte (ese es el punto). Fix: editar Git, no el clúster.
- **Source path malo**: la Application apunta a `cluster/overlays/<env>` que
  ya no existe / `targetRevision` ≠ branch. Arreglá el manifiesto de la
  Application.
- **Mismatch de CRDs**: la Application de un chart se instaló mientras un
  recurso referencia un CRD faltante (`no matches for kind: ClusterPolicy`).
  Fix: seguir el orden de bootstrap (`cluster/base/README.md`) — esperar a que
  las apps de charts estén Healthy, después aplicar la kustomization.
- **Prune bloqueado**: recursos namespaced fuera del scope de la Application.
  Chequeá `syncOptions` (`CreateNamespace=true`, `PruneLast`).
- Estado manual sin el CLI:
  ```bash
  kubectl -n argocd get app ecommerce-<env> -o jsonpath='{.status.health.status}{" "}{.status.sync.status}'
  kubectl -n argocd annotate app ecommerce-<env> argocd.argoproj.io/refresh=hard
  ```

## 7. HPA no escala

**Síntomas**: CPU alto, sin crecimiento de réplicas; `kubectl get hpa -n ecommerce`.

**Diagnóstico**:
```bash
kubectl -n ecommerce describe hpa <svc>
kubectl top pods -n ecommerce          # datos de metrics-server
kubectl -n kube-system get deploy metrics-server
```

**Causas probables & fixes**:
- **Sin resource requests**: el HPA no puede calcular utilization sin
  `requests` — los deployments base los setean (100m/256Mi) y prod los sube;
  un request faltante desactiva el escalado en silencio.
- **Metrics-server ausente** (addon de minikube `metrics-server` no habilitado)
  o métricas no fluyen: `kubectl top nodes` vacío → habilita metrics-server.
- **Target demasiado bajo/alto**: target del HPA base (chequeá `hpa.yaml`) —
  ajustá el target de CPU por servicio; el order-worker de KEDA escala por lag
  de Event Hubs (`lagThreshold: 10`) con `minReplicaCount: 1`,
  `maxReplicaCount: 10`.
- **Réplica única + estabilización**: el scale-down tiene un
  `stabilizationWindowSeconds` (KEDA 300s) — es histeresis esperada, no un bug.

## 8. Eureka no se registra

**Síntomas**: el servicio está arriba y healthy pero ausente del dashboard de
Eureka; el gateway da 503 "no instances available".

**Diagnóstico**:
```bash
kubectl -n ecommerce logs deploy/<svc> --tail=50 | Select-String -Pattern 'eureka|discovery'
kubectl -n ecommerce get svc discovery-service
kubectl -n ecommerce get networkpolicy  # chequeá las reglas de egress
```

**Causas probables & fixes**:
- **URL de config equivocada**: la URL de discovery de Spring Cloud debe
  apuntar a `http://discovery-service:8761/eureka/` (DNS del servicio dentro
  de ecommerce).
- **Egress de network policy**: la policy de cada servicio permite pares de
  ecommerce via `app.kubernetes.io/part-of: ecommerce-platform` — si la
  policy de un servicio nuevo copió una vieja sin esa regla, el tráfico de
  registro se descarta en silencio por el default-deny. Arreglá la policy, no
  la app.
- **Orden/timing de arranque**: Eureka se re-registra por heartbeat; dales a
  los servicios margen en `initialDelaySeconds` o chequeá los intervalos de
  fetch/registration en application.yml del registry.
- **Automation del token del ServiceAccount**: `automountServiceAccountToken:
  false` es intencional; no tiene relación con Eureka pero es un red herring
  común.

---

Ver `runbook-index.md` para la lista completa de runbooks y
`deploy-end-to-end.md` para el walkthrough de despliegue del camino feliz.