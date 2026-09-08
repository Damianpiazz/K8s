# observability/otel — OTLP wiring: guidance only

## Decision: no sidebar, no fallback collector, no reinvented agent

The Phase 8 brief considered a "fallback to OTLP if the cluster doesn't have
ServiceMonitor CRDs". That fallback is **not needed**:

1. **ServiceMonitor CRDs exist** — the kube-prometheus-stack chart (Phase 3)
   installs them, and the deployed values open
   `serviceMonitorSelector` / `serviceMonitorNamespaceSelector`
   (`cluster/base/monitoring/kube-prometheus-stack/values.yaml`).
2. **The OTLP endpoint already exists** — the Phase 3 OpenTelemetry Collector
   (`cluster/base/monitoring/opentelemetry-collector/collector.yaml` + `service.yaml`)
   listens on `:4317` (gRPC) and `:4318` (HTTP) in the `observability` namespace.
3. **The metrics path is already wired** — every service pom declares
   `spring-boot-starter-actuator` + `micrometer-registry-prometheus` (verified
   across all 17 `services/*/pom.xml`), which is exactly what the ServiceMonitors
   in `../servicemonitors` scrape at `/actuator/prometheus`.

## What this directory contains

- `otel-env.yaml` — a NOT-applied reference ConfigMap documenting the env vars
  a service needs to export OTLP (metrics/traces) to the collector, with the
  correct in-cluster endpoint:
  `http://otel-collector.observability.svc.cluster.local:4317`.

## What students need to do (application-side, not this directory)

- For **OTLP metrics**: add `micrometer-registry-otlp` to the service pom and
  set `OTEL_METRICS_EXPORTER=otlp` (+ `OTEL_EXPORTER_OTLP_ENDPOINT`). Micrometer
  then pushes to the collector's `prometheus` exporter (`:8889`), so the metrics
  ALSO land in Prometheus without a ServiceMonitor change.
- For **traces**: add the OpenTelemetry Java agent to the runtime image
  (`-javaagent:...`) with `OTEL_TRACES_EXPORTER=otlp`. The collector forwards
  traces to `otlphttp/tempo` (a placeholder until Tempo is deployed, see the
  collector ConfigMap comments).
- The existing Prometheus scrape remains the source of truth for the
  dashboards/alerts in this tree — OTLP is additive, not a replacement.

## Why NO agent sidecar manifest here

An `otel-agent-sidecar.yaml` would duplicate infrastructure the collector
already provides (it IS the agent for the cluster). The per-service change is
two pom lines + a few env vars — a sidecar would force image changes and
double the Java agent memory footprint on a $0-budget demo cluster. If a
future service cannot be instrumented at the app level, re-evaluate then.