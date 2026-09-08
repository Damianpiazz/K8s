# ADR-0008: Observability stack — Prometheus, Grafana, OTel collector, SLIs

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: platform team

## Context

The demo must show the platform is observable: dashboards that prove traffic,
alerts that fire on real failures, and a scrape path every service honors.
Phase 3 installed kube-prometheus-stack as a Helm chart (Prometheus + Grafana
+ Alertmanager) with open selectors (`serviceMonitorSelector: {}`,
`ruleSelector: {}`); Phase 6 ships the SLI definitions and dashboards. Traces
and logs were left as placeholders in the OTel collector config.

## Decision

- **Metrics**: kube-prometheus-stack from the chart Application
  (`cluster/base/argocd/applications/kube-prometheus-stack.yaml`); app-layer
  scraping via 17 ServiceMonitors in `observability/servicemonitors/`
  (namespaceSelector → `ecommerce`, `jobLabel: app`, path
  `/actuator/prometheus`, port `http`).
- **Dashboards**: Grafana sidecar ConfigMaps (`grafana_dashboard: "1"`,
  `uid: prometheus` datasource) in `observability/dashboards/` —
  spring-boot-overview, ecommerce-platform, service-sli.
- **Alerts**: 4 PrometheusRules in `observability/alerts/` (down, 5xx rate,
  p99 latency, JVM heap pressure) aligned to the SLI table in
  `observability/README.md` over 5-minute windows.
- **Traces**: OTel Collector (`cluster/base/monitoring/opentelemetry-collector`)
  is the single pipeline entry; Tempo/Loki backends remain commented
  placeholders (no dedicated Application yet).
- SLI convention per service (`job` label): availability (up), error rate
  (`http_server_requests_seconds_count` 5xx), p99 latency, JVM heap.

## Consequences

- The observability namespace is the only scrape ingress (per-service
  NetworkPolicies already allow `observability` namespace +
  `app.kubernetes.io/name: prometheus` — `network-policies.mmd` shows it).
- Sample-budget-friendly: 30s scrape interval, small retention — fine for a
  single-node demo cluster.
- ServiceMonitors must live in `observability` with `matchNames: [ecommerce]`
  (the classic "monitor in its own namespace sees nothing" trap is
  documented in the file header and tested by `test-service-contract.py`).
- Grafana admin password ships as a values placeholder — swap it for
  external-secrets (ADR-0007) in real deployments.

## Alternatives considered

- **Metrics Server + kubectl top only**: proves HPA works but gives no demo
  dashboards or alerting; rejected.
- **Managed Azure Monitor / Prometheus**: the managed path exists for Azure,
  but the kube-prometheus-stack stays portable across minikube/kind/AKS and
  matches the reference repo — chosen for TP portability.
- **Jaeger/Zipkin for traces**: the reference repo used Zipkin; the OTel
  collector keeps the door open to both — Tempo chosen as the modern
  default placeholder.