# observability/ — app-level observability (Phase 8)

The **application observability layer** for the 17 e-commerce services. It sits
*on top of* the Phase 3 platform bootstrap (`cluster/base/monitoring/`) and
extends it with service-level scraping, dashboards, SLIs and alerts — everything
the platform bootstrap deliberately did NOT ship (it only installs the stack and
an example rule).

## How this plugs into the deployed platform

| Layer | Phase 3 platform (exists, read-only) | Phase 8 app layer (this directory) |
|---|---|---|
| Stack | kube-prometheus-stack Helm chart (`cluster/base/monitoring/kube-prometheus-stack/`) | — consumes it |
| Selectors | values.yaml opens `serviceMonitorSelector: {}`, `serviceMonitorNamespaceSelector: {}`, `ruleSelector: {}` | CRs are discovered from ANY namespace |
| Scraping | otel-collector ServiceMonitor + example rule only | 17 ServiceMonitors → `services/*/k8s/base` endpoints |
| Dashboards | grafana persistence off, dashboards via ConfigMap sidecar | 3 ConfigMaps with `grafana_dashboard: "1"` |
| Rules | `dashboard-example.yaml` (order-svc p95 SLO example) | 4 PrometheusRules (down / 5xx / p99 / heap) |

### Verified integration contracts

- **Scrape path**: every service exposes `/actuator/prometheus`
  (`services/README.md`), and every pom ships `spring-boot-starter-actuator` +
  `micrometer-registry-prometheus` (verified in all 17 `services/*/pom.xml`).
- **Scrape port**: every Service declares the named port `http` →
  `targetPort: http`, and the ServiceMonitor endpoints reference `port: http`
  (no hard-coded numbers).
- **Labels**: services carry `app: <svc>` on Service + Pod (verified in
  `services/*/k8s/base/{service,deployment}.yaml`); ServiceMonitors select on
  `app: <svc>` and are placed in `observability` with
  `namespaceSelector.matchNames: [ecommerce]` (a ServiceMonitor only watches its
  OWN namespace by default — this is the single most common setup mistake).
- **job label**: `spec.jobLabel: app` → `job=<svc-name>`, matching the existing
  platform rule `cluster/base/monitoring/dashboard-example.yaml` (`job="order-svc"`).
- **NetworkPolicy**: each service's per-service policy already allows ingress
  from the `observability` namespace + the `app.kubernetes.io/name: prometheus`
  pod, so Prometheus scraping is permitted end-to-end.
- **Grafana sidecar**: the deployed values do NOT override `grafana.sidecar.*`,
  so the kube-prometheus-stack chart **defaults** apply: dashboard ConfigMaps
  need the label `grafana_dashboard: "1"` and data keys ending in `.json`. The
  dashboards reference the chart-provisioned Prometheus datasource by
  `uid: prometheus` (the chart default reserve uid).

## Layout

```
observability/
├─ kustomization.yaml        aggregates servicemonitors + alerts + dashboards
├─ servicemonitors/          17 ServiceMonitors (namespace ecommerce)
├─ podmonitors/              README only — deliberately skipped (see file)
├─ dashboards/               spring-boot-overview.json, ecommerce-platform.json,
│                            service-sli.json (+ configMapGenerator)
├─ alerts/                   service-down, service-high-error-rate,
│                            service-high-latency, jvm-memory-pressure
├─ otel/                     guidance-only OTLP env reference (not applied)
└─ README.md
```

## Build & apply

```bash
kustomize build observability          # dry-run / render
kubectl apply -k observability         # direct apply (dev/demo)
```

### Wiring into GitOps (for the orchestrator — DO NOT edit cluster/ yourself)

Add to **every** `cluster/overlays/{dev,staging,prod}/kustomization.yaml`,
inside the existing `resources:` list:

```yaml
  # ── Observability app layer (Phase 8) ──
  - ../../../observability
```

Add after the `../../base` entry. Argo CD (or `kubectl apply -k cluster/overlays/<env>`)
then deploys the ServiceMonitors, PrometheusRules and Grafana dashboards for that
environment. The overlay's `namespaces:` / env-config resources stay untouched.

> The `observability` namespace itself already exists
> (`cluster/base/namespaces/namespaces.yaml`) — nothing to create.

## SLI definitions (used by dashboards + alerts)

All SLIs are computed over 5-minute windows, per service (`job` label):

| SLI | Expression |
|---|---|
| Availability | `avg(up{namespace="ecommerce"})` → 0..1 |
| Errors | `sum(rate(http_server_requests_seconds_count{namespace="ecommerce",status=~"5.."}[5m])) by (job) / clamp_min(sum(rate(...{namespace="ecommerce"}[5m])) by (job), 0.001)` |
| Latency p99 | `histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{namespace="ecommerce"}[5m])) by (le, job))` |
| JVM heap | `sum(jvm_memory_used_bytes{namespace="ecommerce",area="heap"}) by (job) / clamp_min(sum(jvm_memory_max_bytes{namespace="ecommerce",area="heap"}) by (job), 1)` |

Suggested SLO targets for the TP demo (adjust to your SLA):

| SLO | Target | Alert |
|---|---|---|
| Availability | ≥ 99.9% (avg up) | `ServiceDown` (up == 0, 1m) |
| Errors | ≤ 1% of requests | `ServiceHighErrorRate` (> 5%, 5m) |
| Latency | p99 ≤ 2s | `ServiceHighLatency` (> 2s, 5m) |
| Memory | heap ≤ 85% | `JvmMemoryPressure` (> 85%, 10m) |

## What is intentionally NOT here

- **PodMonitors** — see `podmonitors/README.md` (duplicate targets; ServiceMonitor suffices).
- **frontend ServiceMonitor** — the frontend (Next.js, `services/frontend`) runs a Node
  standalone server that does NOT expose Prometheus metrics (no `/actuator/prometheus`,
  no `/metrics`). Its NetworkPolicy still opens the observability scrape peer for
  consistency, but scraping would return the JSON health body (`/health`) in the wrong
  format, so no ServiceMonitor is created for it. The frontend is monitored via k8s
  liveness/readiness probes only.
- **OTLP fallback / agent sidecar** — see `otel/README.md` (collector already present; app-side change is pom + env).
- **Logs (Loki) and traces backend (Tempo)** — placeholders in the collector config; scoped to a later phase.