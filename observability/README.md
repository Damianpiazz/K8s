# observability/ — Observabilidad a nivel de aplicación (Fase 8)

La **capa de observabilidad de aplicación** para los 17 servicios
e-commerce. Se apoya *por encima* del bootstrap de plataforma de la Fase 3
(`cluster/base/monitoring/`) y lo extiende con scraping por servicio,
dashboards, SLIs y alertas — todo lo que el bootstrap de plataforma
deliberadamente NO trajo (solo instala el stack y una regla de ejemplo).

## Cómo se enchufa a la plataforma desplegada

| Capa | Plataforma Fase 3 (existe, read-only) | Capa de app Fase 8 (este directorio) |
|---|---|---|
| Stack | chart Helm kube-prometheus-stack (`cluster/base/monitoring/kube-prometheus-stack/`) | — lo consume |
| Selectors | values.yaml abre `serviceMonitorSelector: {}`, `serviceMonitorNamespaceSelector: {}`, `ruleSelector: {}` | los CRs se descubren de CUALQUIER namespace |
| Scraping | solo el ServiceMonitor del otel-collector + regla de ejemplo | 17 ServiceMonitors → endpoints de `services/*/k8s/base` |
| Dashboards | persistencia de grafana off, dashboards via sidecar de ConfigMap | 3 ConfigMaps con `grafana_dashboard: "1"` |
| Reglas | `dashboard-example.yaml` (ejemplo de SLO p95 de order-svc) | 4 PrometheusRules (down / 5xx / p99 / heap) |

### Contratos de integración verificados

- **Path de scrape**: cada servicio expone `/actuator/prometheus`
  (`services/README.md`), y cada pom trae `spring-boot-starter-actuator` +
  `micrometer-registry-prometheus` (verificado en los 17 `services/*/pom.xml`).
- **Puerto de scrape**: cada Service declara el puerto nombrado `http` →
  `targetPort: http`, y los endpoints del ServiceMonitor referencían
  `port: http` (sin números hardcodeados).
- **Labels**: los servicios llevan `app: <svc>` en Service + Pod (verificado
  en `services/*/k8s/base/{service,deployment}.yaml`); los ServiceMonitors
  seleccionan por `app: <svc>` y están en `observability` con
  `namespaceSelector.matchNames: [ecommerce]` (un ServiceMonitor solo mira su
  PROPIO namespace por defecto — este es el error de setup más común).
- **Label job**: `spec.jobLabel: app` → `job=<svc-name>`, coincidiendo con la
  regla de plataforma existente `cluster/base/monitoring/dashboard-example.yaml`
  (`job="order-svc"`).
- **NetworkPolicy**: la política por servicio de cada servicio ya permite
  ingress desde el namespace `observability` + el pod con
  `app.kubernetes.io/name: prometheus`, así que el scraping de Prometheus está
  permitido end-to-end.
- **Sidecar de Grafana**: los values desplegados NO overridean
  `grafana.sidecar.*`, así que aplican los **defaults** del chart de
  kube-prometheus-stack: los ConfigMaps de dashboards necesitan el label
  `grafana_dashboard: "1"` y claves de data que terminen en `.json`. Los
  dashboards referencían el datasource de Prometheus provisionado por el chart
  con `uid: prometheus` (el uid reservado por defecto del chart).

## Layout

```
observability/
├─ kustomization.yaml        agrega servicemonitors + alerts + dashboards
├─ servicemonitors/          17 ServiceMonitors (namespace ecommerce)
├─ podmonitors/              solo README — deliberadamente omitido (ver archivo)
├─ dashboards/               spring-boot-overview.json, ecommerce-platform.json,
│                            service-sli.json (+ configMapGenerator)
├─ alerts/                   service-down, service-high-error-rate,
│                            service-high-latency, jvm-memory-pressure
├─ otel/                     referencia de env OTLP solo guía (no aplicado)
└─ README.md
```

## Build & apply

```bash
kustomize build observability          # dry-run / render
kubectl apply -k observability         # apply directo (dev/demo)
```

### Wiring en GitOps (para el orquestador — NO edites cluster/ vos mismo)

Agregá a **cada** `cluster/overlays/{dev,staging,prod}/kustomization.yaml`,
dentro de la lista `resources:` existente:

```yaml
  # ── Capa de app de observabilidad (Fase 8) ──
  - ../../../observability
```

Agregalo después de la entrada `../../base`. Argo CD (o
`kubectl apply -k cluster/overlays/<env>`) después despliega los
ServiceMonitors, PrometheusRules y dashboards de Grafana para ese ambiente.
Los recursos `namespaces:` / env-config del overlay no se tocan.

> El namespace `observability` en sí ya existe
> (`cluster/base/namespaces/namespaces.yaml`) — no hay que crear nada.

## Definiciones de SLI (usadas por dashboards + alertas)

Todos los SLIs se calculan sobre ventanas de 5 minutos, por servicio (label
`job`):

| SLI | Expresión |
|---|---|
| Disponibilidad | `avg(up{namespace="ecommerce"})` → 0..1 |
| Errores | `sum(rate(http_server_requests_seconds_count{namespace="ecommerce",status=~"5.."}[5m])) by (job) / clamp_min(sum(rate(...{namespace="ecommerce"}[5m])) by (job), 0.001)` |
| Latencia p99 | `histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{namespace="ecommerce"}[5m])) by (le, job))` |
| Heap JVM | `sum(jvm_memory_used_bytes{namespace="ecommerce",area="heap"}) by (job) / clamp_min(sum(jvm_memory_max_bytes{namespace="ecommerce",area="heap"}) by (job), 1)` |

Objetivos de SLO sugeridos para la demo del TP (ajustalos a tu SLA):

| SLO | Target | Alerta |
|---|---|---|
| Disponibilidad | ≥ 99.9% (avg up) | `ServiceDown` (up == 0, 1m) |
| Errores | ≤ 1% de las peticiones | `ServiceHighErrorRate` (> 5%, 5m) |
| Latencia | p99 ≤ 2s | `ServiceHighLatency` (> 2s, 5m) |
| Memoria | heap ≤ 85% | `JvmMemoryPressure` (> 85%, 10m) |

## Lo que NO está acá intencionalmente

- **PodMonitors** — ver `podmonitors/README.md` (targets duplicados;
  ServiceMonitor alcanza).
- **ServiceMonitor del frontend** — el frontend (Next.js, `services/frontend`)
  corre un servidor Node standalone que NO expone métricas de Prometheus (no
  tiene `/actuator/prometheus`, ni `/metrics`). Su NetworkPolicy igual abre el
  peer de scrape de observability por consistencia, pero el scraping
  devolvería el cuerpo JSON de health (`/health`) en el formato incorrecto,
  así que no se crea ServiceMonitor para él. El frontend se monitorea solo con
  probes de liveness/readiness de k8s.
- **Fallback OTLP / sidecar de agente** — ver `otel/README.md` (el collector
  ya está presente; el cambio del lado de la app es pom + env).
- **Logs (Loki) y backend de traces (Tempo)** — placeholders en la config del
  collector; scope para una fase posterior.