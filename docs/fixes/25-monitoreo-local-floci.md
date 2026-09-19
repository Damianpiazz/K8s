# Fix 25 — Monitoreo local en floci (Prometheus + Grafana + ServiceMonitors + alerts)

## Fecha
2026-09-18

## Error / contexto observado
El emulador local **floci** (k3s single-node dentro de docker) no tenía
monitoreo: `cluster/overlays/local/kustomization.yaml` usaba delete patches que
sacaban del build:

- la **Application ArgoCD `kube-prometheus-stack`** (el stack nunca se
  instalaba en local),
- los **18 ServiceMonitors** (los 17 de `observability/servicemonitors/` más el
  de `otel-collector` en `cluster/base/monitoring/opentelemetry-collector/`),
- las **5 PrometheusRules** (`jvm-memory-pressure`, `service-down`,
  `service-high-latency`, `service-high-error-rate` de
  `observability/alerts/` y `order-svc-slo` de
  `cluster/base/monitoring/dashboard-example.yaml`).

Además, el ServiceMonitor de `auth` (Keycloak) apuntaba al puerto `http`
(8080) con path `/metrics`, pero Keycloak **no expone métricas por defecto**:
hay que habilitarlas con `--metrics-enabled=true` (en Keycloak <= 24 se
sirven en el puerto HTTP principal, 8080). Resultado esperable: `ServiceDown`
en Azure y cero métricas de auth en ambos lados.

## Causa raíz
La exclusión por nombre se diseñó en la época del bootstrap directo (fix 22),
cuando ArgoCD gestionaba localmente solo el install y el resto de componentes
se aplicaban a mano: sin las CRDs de `monitoring.coreos.com` instaladas, el
`apply` de un ServiceMonitor/PrometheusRule falla ("no matches for kind"), así
que el overlay los barre del build completo. Ese razonamiento quedó obsoleto
al buscar la paridad GitOps floci ↔ Azure (PLAN.md): si la Application
`kube-prometheus-stack` queda viva, ArgoCD instala el stack y sus CRDs, y los
ServiceMonitors/Rules pueden renderizarse igual que en dev/staging/prod.

En paralelo, Keycloak no es un servicio Spring Boot: no tiene
`/actuator/prometheus`. Sus métricas viven en `/metrics` del puerto HTTP
principal (8080, igual que health) y están apagadas por defecto
(`--metrics-enabled`). El SM de auth ya tenía el path `/metrics` corregido,
pero apuntaba al puerto correcto sin que las métricas existieran. Nota: el
puerto de management (9000) recién aparece en Keycloak 25+; si la imagen sube
de versión, /metrics (y /health) migran a 9000 y el SM + los probes deben
migrar juntos.

## Fix aplicado
1. **Overlay local** (`cluster/overlays/local/kustomization.yaml`):
   - Eliminados los delete patches de la Application `kube-prometheus-stack`,
     de los 18 ServiceMonitors y de las 5 PrometheusRules.
   - Se mantienen los deletes de las demás Applications (app-of-apps
     `ecommerce-apps` y las 10 children: solo ArgoCD los aplica en Azure),
     external-dns, example-external-secret, ClusterIssuers, ClusterPolicies de
     kyverno, ClusterSecretStore + ExternalSecret de keycloak-admin, el
     ScaledObject de KEDA y los HPA (fix 13, nodo único).
   - Comentarios actualizados: la Application `kube-prometheus-stack` queda
     viva para que ArgoCD la gestione en local (paridad con Azure).
2. **Métricas de Keycloak** (`services/auth/k8s/base/deployment.yaml`):
   `command`/`args` explícitos replicando el ENTRYPOINT de la imagen
   (`start-dev --import-realm`) y agregando `--health-enabled=true` (los
   probes `/health/live` y `/health/ready` ya lo asumían) y
   `--metrics-enabled=true`. En Keycloak <= 24 ambos se sirven en el puerto
   HTTP principal (8080), así que no se agrega ningún puerto nuevo: el
   containerPort `http: 8080` queda solo (sin `management`). El overlay local
   no repite command/args: viven en el base (fix 25).
3. **Service de auth** (`services/auth/k8s/base/service.yaml`): sin cambios
   finales — queda solo el puerto `http` 8080 → `targetPort http`. Si la
   imagen sube a Keycloak 25+, hay que agregar `management` (9000) aquí, en
   el Deployment y en el SM a la vez.
4. **ServiceMonitor de auth** (`observability/servicemonitors/servicemonitors.yaml`):
   endpoint → `port: http`, `path: /metrics` (Keycloak <= 24 las sirve en el
   puerto HTTP principal, 8080). Nota YAML: en Azure/prod el endpoint puede
   requerir `bearerTokenSecret` (preocupación de fase 3/5 — NO implementado),
   y si la imagen sube a Keycloak 25+, el endpoint pasa a `port: management`
   (9000) junto con los probes. La NetworkPolicy de auth ya permite el
   scrape: la regla de ingress desde `observability`/prometheus no restringe
   puertos (patrón del repo).
5. **Sanity-check del resto de SMs**: spot-check de `api-gateway` (8080) y
   `checkout-svc` (8083) contra sus Deployments/application.yml — ambos exponen
   Actuator/Prometheus en el puerto principal; sin mismatches. La ausencia de
   SM del frontend es intencional (Next.js sin métricas, documentado en
   `observability/README.md`).
6. **Acople `start-dev` al base**: el base fija explícitamente `kc.sh start-dev`
   (H2) en `command`/`args` — el mismo comportamiento que ya tenía la imagen
   por ENTRYPOINT, pero ahora explícito en GitOps. Es un acople de entorno:
   los overlays Azure deben pisar ese command/args a `kc.sh start` +
   PostgreSQL gestionado antes de la posture final de producción (Fase 6,
   `docs/PLAN.md`).
7. **Assertion de wiring en la suite**: `tests/manifests/test-kustomize-build.py`
   incluye ahora `cluster/overlays/local` como target y, en los overlays
   completos (dev/staging/prod/local), verifica que cada
   `ServiceMonitor.spec.endpoints[].port` resuelve a un puerto con nombre del
   Service seleccionado y que su `targetPort` existe como containerPort del
   Deployment seleccionado (falla ruidoso ante mismatch).

## Archivos afectados
- `cluster/overlays/local/kustomization.yaml`
- `services/auth/k8s/base/deployment.yaml`
- `services/auth/k8s/base/service.yaml`
- `observability/servicemonitors/servicemonitors.yaml`
- `tests/manifests/test-kustomize-build.py` (target local + assertion de wiring)
- `docs/fixes/README.md` (índice) y `docs/PLAN.md` (estado de Fase 1)

## Cómo verificar
```powershell
# Render del overlay local; debe contener la Application, 18 ServiceMonitors
# y 5 PrometheusRules:
kubectl kustomize cluster/overlays/local | Select-String "kind: ServiceMonitor"   # 18
kubectl kustomize cluster/overlays/local | Select-String "kind: PrometheusRule"   # 5
kubectl kustomize cluster/overlays/local | Select-String "kind: Application"      # 1 (kube-prometheus-stack)
kubectl kustomize cluster/overlays/local | Select-String "kind: HorizontalPodAutoscaler"  # 0 (fix 13 intacto)

# Los demás overlays siguen buildando (ahora incluye cluster/overlays/local y
# la assertion de wiring SM -> Service -> Deployment):
python tests/manifests/test-kustomize-build.py   # KUSTOMIZE BUILD CHECK PASSED

# En el render: el SM de auth apunta a http:/metrics, el Service de auth NO
# tiene puerto management y el Deployment de auth expone solo 8080:
kubectl kustomize cluster/overlays/local | Select-String -Context 2,2 "port: http"
kubectl kustomize cluster/overlays/local | Select-String "management"   # sin resultados
```

> **Nota de apply en el clúster vivo**: `kubectl apply -k cluster/overlays/local`
> va a fallar con "no matches for kind ServiceMonitor/PrometheusRule" mientras
> las CRDs de `monitoring.coreos.com` no existan. Orden sugerido: (1) aplicar
> solo la Application `kube-prometheus-stack` (o el overlay completo y
> re-aplicar), (2) dejar que ArgoCD sincronice el stack y cree las CRDs,
> (3) re-aplicar el overlay. Verificar luego en Grafana (ingress local) que
> las targets `up` y las alertas cargan (siguiente paso de la Fase 1).