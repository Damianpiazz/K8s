# PodMonitors — intentionally SKIPPED

The original Phase 8 brief asked for a PodMonitor targeting the edge services
(`api-gateway`, `bff-web`) "if needed for Spring Boot metrics that ServiceMonitor
can't reach".

**Decision: not needed — no PodMonitor is deployed.**

## Why

A PodMonitor is only required when a workload exposes metrics on a port that has
no backing Kubernetes `Service` (or whose metrics endpoint differs from the
service port). Neither edge service falls in that category:

- `api-gateway` (port **8080**) and `bff-web` (port **8090**) each define a
  regular `ClusterIP` Service with a named port `http` → `targetPort: http`
  (verified in `services/api-gateway/k8s/base/service.yaml` and
  `services/bff-web/k8s/base/service.yaml`).
- Both expose the same Actuator metrics endpoint as every other service:
  `/actuator/prometheus` (services/README.md: "Every service exposes
  `/actuator/prometheus`").

The `api-gateway` ServiceMonitor in `../servicemonitors/servicemonitors.yaml`
already covers this exact path. A PodMonitor selecting the same pods would create
**duplicate scrape targets** (same series, double the scrape load on a
$0-budget demo cluster with 6h retention).

## When you WOULD need a PodMonitor here

- If a future service exposes Prometheus metrics on a **non-Service port** or on
  **localhost/headless** endpoints (e.g. an OpenTelemetry SDK pushing to a
  sidecar, or a metrics port deliberately not published as a Service).
- If you switch the edge services to expose metrics on a separate management
  port that is not part of the `http` Service port.

If that happens: add the PodMonitor file here (selector `app in [api-gateway,
bff-web]`, namespace `ecommerce`, namespaceSelector matching `observability`),
list it in a `kustomization.yaml`, and remove the `enabled` flag stays as-is —
the ServiceMonitor keeps working.

## Wiring

Nothing to wire: this directory intentionally contains no resources. The
orchestrator should NOT add `observability/podmonitors` to any kustomization
(an empty kustomization adds nothing, but keeping it out keeps the build clean).